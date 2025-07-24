(ns simulflow-examples.twilio-websocket
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [clojure.data.xml :as xml]
   [muuntaja.core :as mtj]
   [portal.api :as portal]
   [reitit.dev.pretty :as pretty]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.adapter.jetty :as jetty]
   [ring.util.response :as r]
   [ring.websocket :as ws]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.processors.activity-monitor :as activity-monitor]
   [simulflow.processors.deepgram :as asr]
   [simulflow.processors.elevenlabs :as tts]
   [simulflow.processors.llm-context-aggregator :as context]
   [simulflow.processors.openai :as llm]
   [simulflow.scenario-manager :as sm]
   [simulflow.secrets :refer [secret]]
   [simulflow.transport :as transport]
   [simulflow.transport.out :as transport-out]
   [simulflow.utils.core :as u]
   [taoensso.telemere :as t]))

(comment
  (portal/open)
  (add-tap #'portal/submit))

(def wrap-exception
  (exception/create-exception-middleware
   (merge
    exception/default-handlers
    {;; print stack-traces for all exceptions
     ::exception/wrap (fn [handler e request]
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
  encoded as MULAW at 8kHz frequency (sample rate) and it is mono (1 channel)."
  [{:keys [llm-context extra-procs in out extra-conns language]
    :or {llm-context {:messages [{:role "system"
                                  :content "You are a helpful assistant "}]}
         extra-procs {}
         language :en
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
       :transcriptor {:proc asr/deepgram-processor
                      :args {:transcription/api-key (secret [:deepgram :api-key])
                             :transcription/interim-results? true
                             :transcription/punctuate? false
                             :transcription/vad-events? true
                             :transcription/smart-format? true
                             :transcription/model :nova-2
                             :transcription/utterance-end-ms 1000
                             :transcription/language language
                             :transcription/encoding :ulaw
                             :transcription/sample-rate sample-rate}}
       :context-aggregator {:proc context/context-aggregator
                            :args {:llm/context llm-context
                                   :aggregator/debug? false}}

       :llm {:proc llm/openai-llm-process
             :args {:openai/api-key (secret [:openai :new-api-sk])
                    :llm/model "gpt-4o-mini"}}

       :assistant-context-assembler {:proc context/assistant-context-assembler
                                     :args {:debug? false}}

       :llm-sentence-assembler {:proc context/llm-sentence-assembler}
       :tts {:proc tts/elevenlabs-tts-process
             :args {:elevenlabs/api-key (secret [:elevenlabs :api-key])
                    :elevenlabs/model-id "eleven_flash_v2_5"
                    :elevenlabs/voice-id (secret [:elevenlabs :voice-id])
                    :voice/stability 0.5
                    :voice/similarity-boost 0.8
                    :voice/use-speaker-boost? true
                    :flow/language language
                    :audio.out/encoding encoding
                    :audio.out/sample-rate sample-rate}}
       :audio-splitter {:proc transport/audio-splitter
                        :args {:audio.out/sample-rate sample-rate
                               :audio.out/sample-size-bits sample-size-bits
                               :audio.out/channels channels
                               :audio.out/duration-ms chunk-duration-ms}}
       :realtime-out {:proc transport-out/realtime-out-processor
                      :args {:audio.out/chan out
                             :audio.out/sending-interval 20}}
       :activity-monitor {:proc activity-monitor/process
                          :args {::activity-monitor/timeout-ms 5000}}

       :prn-sink {:proc (flow/process (fn
                                        ([] {:ins {:in "gimme stuff to print!"}})
                                        ([_] nil)
                                        ([_ _] nil)
                                        ([_ _ v] (t/log! {:id :prn-sink :data v}))))}}
      extra-procs)

     :conns (concat
             [[[:transport-in :sys-out] [:transcriptor :sys-in]]
              [[:transport-in :out] [:transcriptor :in]]

              [[:transcriptor :out] [:context-aggregator :in]]
              [[:context-aggregator :out] [:llm :in]]

              ;; Aggregate full context
              [[:llm :out] [:assistant-context-assembler :in]]
              [[:assistant-context-assembler :out] [:context-aggregator :in]]

              ;; Assemble sentence by sentence for fast speech
              [[:llm :out] [:llm-sentence-assembler :in]]
              [[:llm-sentence-assembler :out] [:tts :in]]

              [[:tts :out] [:audio-splitter :in]]
              [[:transport-in :sys-out] [:realtime-out :sys-in]]
              [[:audio-splitter :out] [:realtime-out :in]]

              ;; Activity monitor connections - basically check if there is
              ;; activity on the pipeline
              [[:realtime-out :out] [:activity-monitor :in]]
              [[:transcriptor :out] [:activity-monitor :in]]
              [[:activity-monitor :out] [:context-aggregator :in]]
              [[:activity-monitor :out] [:tts :in]]]
             extra-conns)}))

(defn tool-use-example
  "Tools are specified in the :llm/context :tools vector.
  See `schema/LLMFunctionToolDefinitionWithHandling` for the full structure of a
  tool definition.

  A tool needs a description and a :handler. The LLM will issue a tol call
  request and simulflow will call that function with the specified arguments,
  putting the result in the chat history for the llm to see."
  [in out]
  {:flow
   (flow/create-flow
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
                     (t/log! "Starting monitoring flow!")
                     (vthread-loop []
                       (when @call-ongoing?
                         (when-let [[msg c] (a/alts!! [report-chan error-chan])]
                           (when (map? msg)
                             (t/log! {:level :debug :id (if (= c error-chan) :error :report)} msg))
                           (recur))))
                     (vthread-loop []
                       (when @call-ongoing?
                         (when-let [output (a/<!! out)]
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
        :on-error (fn on-error [_ error]
                    (prn error)
                    (t/log! :debug error))
        :on-ping (fn on-ping [ws payload]
                   (ws/send ws payload))}})))

(def routes
  [["/inbound-call" {:summary "Webhook where a call is made"
                     :post {:handler twilio-inbound-handler}}]
   ["/ws" {:summary "Websocket endpoint to receive a twilio call"
           ;; Change param to make-twilio-ws-handler to change example flow used
           :get {:handler (make-twilio-ws-handler tool-use-example)}}]])

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
