(ns voice-fn-examples.twilio-websocket
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [clojure.data.xml :as xml]
   [malli.core :as malli]
   [muuntaja.core :as mtj]
   [portal.api :as portal]
   [reitit.core]
   [reitit.dev.pretty :as pretty]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.adapter.jetty :as jetty]
   [ring.util.response :as r]
   [ring.websocket :as ws]
   [taoensso.telemere :as t]
   [voice-fn.processors.deepgram :as asr]
   [voice-fn.processors.elevenlabs :as tts]
   [voice-fn.processors.llm-context-aggregator :as context]
   [voice-fn.processors.openai :as llm]
   [voice-fn.scenario-manager :as sm]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.transport :as transport]
   [voice-fn.utils.core :as u]))

(t/set-min-level! :debug)

(comment
  (portal/open)
  (add-tap #'portal/submit))

(def wrap-exception
  (exception/create-exception-middleware
    (merge
      exception/default-handlers
      {;; print stack-traces for all exceptions
       ::exception/wrap               (fn [handler e request]
                                        (t/log! :error e)
                                        (handler e request))})))

(defn emit-xml-str
  "Emit the string for this xml declaration"
  [xml-data]
  (-> xml-data
      (xml/sexp-as-element)
      (xml/emit-str)))

(defn header
  [req header-name]
  (get (:headers req) header-name))

(defn host
  [req]
  (header req "host"))

(defn xml-response
  [body]
  (-> (r/response body)
      (r/content-type "text/xml")))

(defn twilio-inbound-handler
  "Handler to direct the incoming call to stream audio to our websocket handler"
  [req]
  (let [h (host req)
        ws-url (str "wss://" h "/ws")]
    ;; https://www.twilio.com/docs/voice/twiml/connect
    (xml-response
      (emit-xml-str [:Response
                     [:Connect
                      [:Stream {:url ws-url}]]]))))

(def dbg-flow (atom nil))

(comment
  (flow/ping @dbg-flow)
  ,)

(defn phone-flow
  "This example showcases a voice AI agent for the phone. Phone audio is usually
  encoded as MULAW at 8kHz frequency (sample rate) and it is mono (1 channel).

  N.B the graph connections: There must be a cycle for aggregators, to have the
  best results: user-context-aggregator -> llm -> assistant-context-aggregator
  -> user-context-aggregator"
  [{:keys [llm-context extra-procs in out extra-conns]
    :or {llm-context {:messages [{:role "system"
                                  :content "You are a helpful assistant "}]}
         extra-procs {}
         extra-conns []}}]
  (let [encoding :ulaw
        sample-rate 8000
        sample-size-bits 8
        channels 1 ;; mono
        chunk-duration-ms 20]
    {:procs
     (u/deep-merge
       {:transport-in {:proc transport/twilio-transport-in
                       :args {:transport/in-ch in}}
        :deepgram-transcriptor {:proc asr/deepgram-processor
                                :args {:transcription/api-key (secret [:deepgram :api-key])
                                       :transcription/interim-results? true
                                       :transcription/punctuate? false
                                       :transcription/vad-events? true
                                       :transcription/smart-format? true
                                       :transcription/model :nova-2
                                       :transcription/utterance-end-ms 1000
                                       :transcription/language :en
                                       :transcription/encoding :mulaw
                                       :transcription/sample-rate sample-rate}}
        :user-context-aggregator  {:proc context/user-aggregator-process
                                   :args {:llm/context llm-context
                                          :aggregator/debug? true}}
        :assistant-context-aggregator {:proc context/assistant-context-aggregator
                                       :args {:llm/context llm-context
                                              :debug? true}}
        :llm {:proc llm/openai-llm-process
              :args {:openai/api-key (secret [:openai :new-api-sk])
                     :llm/model "gpt-4o-mini"}}

        :llm-sentence-assembler {:proc (flow/step-process #'context/sentence-assembler)}
        :tts {:proc tts/elevenlabs-tts-process
              :args {:elevenlabs/api-key (secret [:elevenlabs :api-key])
                     :elevenlabs/model-id "eleven_flash_v2_5"
                     :elevenlabs/voice-id "7sJPxFeMXAVWZloGIqg2"
                     :voice/stability 0.5
                     :voice/similarity-boost 0.8
                     :voice/use-speaker-boost? true
                     :flow/language :en
                     :audio.out/encoding encoding
                     :audio.out/sample-rate sample-rate}}
        :audio-splitter {:proc transport/audio-splitter
                         :args {:audio.out/sample-rate sample-rate
                                :audio.out/sample-size-bits sample-size-bits
                                :audio.out/channels channels
                                :audio.out/duration-ms chunk-duration-ms}}
        :realtime-out {:proc transport/realtime-transport-out-processor
                       :args {:transport/out-chan out}}}
       extra-procs)

     :conns (concat
              [[[:transport-in :sys-out] [:deepgram-transcriptor :sys-in]]
               [[:transport-in :out] [:deepgram-transcriptor :in]]
               [[:deepgram-transcriptor :out] [:user-context-aggregator :in]]
               [[:user-context-aggregator :out] [:llm :in]]
               [[:llm :out] [:assistant-context-aggregator :in]]

               ;; cycle so that context aggregators are in sync
               [[:assistant-context-aggregator :out] [:user-context-aggregator :in]]
               [[:user-context-aggregator :out] [:assistant-context-aggregator :in]]

               [[:llm :out] [:llm-sentence-assembler :in]]
               [[:llm-sentence-assembler :out] [:tts :in]]

               [[:tts :out] [:audio-splitter :in]]
               [[:transport-in :sys-out] [:realtime-out :sys-in]]
               [[:audio-splitter :out] [:realtime-out :in]]]
              extra-conns)}))

(defn tool-use-example
  "Tools are specified in the :llm/context :tools vector.
  See `schema/LLMFunctionToolDefinitionWithHandling` for the full structure of a
  tool definition.

  A tool needs a description and a :handler. The LLM will issue a tol call
  request and voice-fn will call that function with the specified arguments,
  putting the result in the chat history for the llm to see."
  [in out]
  {:flow (flow/create-flow
           (phone-flow
             {:in in
              :out out
              :llm/context {:messages
                            [{:role "system"
                              :content "You are a voice agent operating via phone. Be
                       concise. The input you receive comes from a
                       speech-to-text (transcription) system that isn't always
                       efficient and may send unclear text. Ask for
                       clarification when you're unsure what the person said."}]
                            :tools
                            [{:type :function
                              :function
                              {:name "get_weather"
                               :handler (fn [{:keys [town]}] (str "The weather in " town " is 17 degrees celsius"))
                               :description "Get the current weather of a location"
                               :parameters {:type :object
                                            :required [:town]
                                            :properties {:town {:type :string
                                                                :description "Town for which to retrieve the current weather"}}
                                            :additionalProperties false}
                               :strict true}}]}}))})

(def scenario-config
  {:initial-node :start
   :nodes
   {:start
    {:role-messages [{:role :system
                      :content "You are a restaurant reservation assistant for La Maison, an upscale French restaurant. You must ALWAYS use one of the available functions to progress the conversation. This is a phone conversations and your responses will be converted to audio. Avoid outputting special characters and emojis. Be casual and friendly."}]
     :task-messages [{:role :system
                      :content "Warmly greet the customer and ask how many people are in their party."}]
     :functions [{:type :function
                  :function
                  {:name "record_party_size"
                   :handler (fn [{:keys [size]}] size)
                   :description "Record the number of people in the party"
                   :parameters
                   {:type :object
                    :properties
                    {:size {:type :integer
                            :description "The number of people that will dine."
                            :minimum 1
                            :maximum 12}}
                    :required [:size]}
                   :transition-to :get-time}}]}
    :get-time
    {:task-messages [{:role :system
                      :content "Ask what time they'd like to dine. Restaurant is open 5 PM to 10 PM. After they provide a time, confirm it's within operating hours before recording. Use 24-hour format for internal recording (e.g., 17:00 for 5 PM)."}]
     :functions [{:type :function
                  :function {:name "record_time"
                             :handler (fn [{:keys [time]}] time)
                             :description "Record the requested time"
                             :parameters {:type :object
                                          :properties {:time {:type :string
                                                              :pattern "^(17|18|19|20|21|22):([0-5][0-9])$"
                                                              :description "Reservation time in 24-hour format (17:00-22:00)"}}
                                          :required [:time]}
                             :transition-to :confirm}}]}

    :confirm
    {:task-messages [{:role :system
                      :content "Confirm the reservation details and end the conversation. Say back to the client the details of the reservation: \"Ok! So a reservation for X people at Y PM. Is this corret?\" "}]
     :functions [{:type :function
                  :function {:name "end"
                             :description "End the conversation"
                             :parameters {:type :object, :properties {}}
                             :transition-to :end}}]}
    :end {:task-messages [{:role :system, :content "Thank them and end the conversation."}]
          :functions []
          :post-actions [{:type :end-conversation}]}}})

(defn scenario-example
  "A scenario is a predefined, highly structured conversation. LLM performance
  degrades when it has a big complex prompt to enact, so to ensure a consistent
  output use scenarios that transition the LLM into a new scenario node with a clear
  instruction for the current node."
  [in out]
  (let [flow
        (flow/create-flow
          (phone-flow
            {:in in
             :out out
             :llm/context {:messages []
                           :tools []}

             ;; add gateway process for scenario
             :extra-procs {:scenario {:proc (flow/step-process #'sm/scenario-in-process)}}
             :extra-conns [[[:scenario :speak-out] [:tts :in]]
                           [[:scenario :context-out] [:user-context-aggregator :in]]]}))

        s (sm/scenario-manager {:flow flow
                                :flow-in-coord [:scenario :scenario-in] ;; scenario-manager will inject frames through this channel
                                :scenario-config scenario-config})]

    {:flow flow
     :scenario s}))

(defn make-twilio-ws-handler
  [make-flow]
  (fn [req]
    (assert (ws/upgrade-request? req) "Must be a websocket request")
    (let [in (a/chan 1024)
          out (a/chan 1024)
          {fl :flow
           s :scenario} (make-flow in out)
          call-ongoing? (atom true)]
      (reset! dbg-flow fl)
      {::ws/listener
       {:on-open (fn on-open [socket]
                   (let [{:keys [report-chan error-chan]} (flow/start fl)]
                     ;; start scenario if it was provided
                     (t/log! "Starting monitoring flow")
                     (a/go-loop []
                       (when @call-ongoing?
                         (when-let [[msg c] (a/alts! [report-chan error-chan])]
                           (when (map? msg)
                             (t/log! {:level :debug :id (if (= c error-chan) :error :report)} msg))
                           (recur))))
                     (a/go-loop []
                       (when @call-ongoing? (when-let [output (a/<! out)]
                                              (ws/send socket output)
                                              (recur)))))
                   (flow/resume fl)
                   (when s (sm/start s))
                   nil)
        :on-message (fn on-text [_ws payload]
                      (a/put! in payload))
        :on-close (fn on-close [_ws _status-code _reason]
                    (t/log! "Call closed")
                    (flow/stop fl)
                    (a/close! in)
                    (a/close! out)
                    (reset! call-ongoing? false))
        :on-error (fn on-error [ws error]
                    (prn error)
                    (t/log! :debug error))
        :on-ping (fn on-ping [ws payload]
                   (ws/send ws payload))}})))

(def routes
  [["/inbound-call" {:summary "Webhook where a call is made"
                     :post {:handler twilio-inbound-handler}}]
   ["/ws" {:summary "Websocket endpoint to receive a twilio call"
           ;; Change param to make-twilio-ws-handler to change example flow used
           :get {:handler (make-twilio-ws-handler scenario-example)}}]])

(def app
  (ring/ring-handler
    (ring/router
      routes
      {:exception pretty/exception
       :data {:muuntaja mtj/instance
              :middleware [;; query-params & form-params
                           parameters/parameters-middleware
                           ;; content-negotiation
                           muuntaja/format-negotiate-middleware
                           ;; encoding response body
                           muuntaja/format-response-middleware
                           ;; exception handling
                           wrap-exception
                           ;; decoding request body
                           muuntaja/format-request-middleware
                           ;; coercing response bodys
                           coercion/coerce-response-middleware
                           ;; coercing request parameters
                           coercion/coerce-request-middleware]}})
    (ring/create-default-handler)))

(defn start [& {:keys [port] :or {port 3000}}]
  (println (str "server running in port " port))
  (jetty/run-jetty #'app {:port port, :join? false}))

(comment

  (def server (start :port 8080))
  (.stop server)

  ,)
