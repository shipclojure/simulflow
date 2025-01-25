(ns voice-fn-examples.twilio-websocket
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [clojure.data.xml :as xml]
   [muuntaja.core :as m]
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
   [voice-fn.frame :as frame]
   [voice-fn.processors.deepgram :as asr]
   [voice-fn.processors.elevenlabs :as tts]
   [voice-fn.processors.llm-context-aggregator :as context]
   [voice-fn.processors.openai :as llm]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.transport :as transport]))

(t/set-min-level! :debug)
(comment
  (portal/open)
  (add-tap #'portal/submit))

(defn emit-xml-str
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

(defn make-twilio-flow
  [in out]
  (let [encoding :ulaw
        sample-rate 8000
        sample-size-bits 8
        channels 1 ;; mono
        chunk-duration-ms 20]
    {:procs
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
      :user-context-aggregator  {:proc context/context-aggregator-process
                                 :args {:messages/role "user"
                                        :llm/context {:messages [{:role "system"
                                                                  :content  "You are a voice agent operating via phone. Be concise. The input you receive comes from a speech-to-text (transcription) system that isn't always efficient and may send unclear text. Ask for clarification when you're unsure what the person said."}]
                                                      :tools [{:type :function
                                                               :function
                                                               {:name "get_weather"
                                                                :description "Get the current weather of a location"
                                                                :parameters {:type :object
                                                                             :required [:town]
                                                                             :properties {:town {:type :string
                                                                                                 :description "Town for which to retrieve the current weather"}}
                                                                             :additionalProperties false}
                                                                :strict true}}]}
                                        :aggregator/start-frame? frame/user-speech-start?
                                        :aggregator/end-frame? frame/user-speech-stop?
                                        :aggregator/accumulator-frame? frame/transcription?
                                        :aggregator/interim-results-frame? frame/transcription-interim?
                                        :aggregator/handles-interrupt? false}} ;; User speaking shouldn't be interrupted
      :assistant-context-aggregator {:proc context/context-aggregator-process
                                     :args {:messages/role "assistant"
                                            :llm/context {:messages [{:role "system"
                                                                      :content  "You are a voice agent operating via phone. Be concise. The input you receive comes from a speech-to-text (transcription) system that isn't always efficient and may send unclear text. Ask for clarification when you're unsure what the person said."}]
                                                          :tools [{:type :function
                                                                   :function
                                                                   {:name "get_weather"
                                                                    :description "Get the current weather of a location"
                                                                    :parameters {:type :object
                                                                                 :required [:town]
                                                                                 :properties {:town {:type :string
                                                                                                     :description "Town for which to retrieve the current weather"}}
                                                                                 :additionalProperties false}
                                                                    :strict true}}]}
                                            :aggregator/start-frame? frame/llm-full-response-start?
                                            :aggregator/end-frame? frame/llm-full-response-end?
                                            :aggregator/accumulator-frame? frame/llm-text-chunk?}}
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

     :conns [[[:transport-in :sys-out] [:deepgram-transcriptor :sys-in]]
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
             [[:audio-splitter :out] [:realtime-out :in]]]}))

(defn twilio-ws-handler-flow
  [req]
  (assert (ws/upgrade-request? req) "Must be a websocket request")
  (let [in (a/chan 1024)
        out (a/chan 1024)
        fl (flow/create-flow (make-twilio-flow in out))]
    (reset! dbg-flow fl)
    {::ws/listener
     {:on-open (fn on-open [socket]
                 (let [{:keys [report-chan error-chan]} (flow/start fl)]
                   (a/go-loop []
                     (when-let [[msg c] (a/alts! [report-chan error-chan])]
                       (when (map? msg)
                         (t/log! {:level :debug :id (if (= c error-chan) :error :report)} msg))
                       (recur)))
                   (a/go-loop []
                     (when-let [output (a/<! out)]
                       (ws/send socket output)
                       (recur))))
                 (flow/resume fl)
                 nil)
      :on-message (fn on-text [_ws payload]
                    (a/put! in payload))
      :on-close (fn on-close [_ws _status-code _reason]
                  (t/log! "Call closed")
                  (flow/stop fl)
                  (a/close! in)
                  (a/close! out))
      :on-error (fn on-error [ws error]
                  (prn error)
                  (t/log! :debug error))
      :on-ping (fn on-ping [ws payload]
                 (ws/send ws payload))}}))

(def routes
  [["/inbound-call" {:summary "Webhook where a call is made"
                     :post {:handler twilio-inbound-handler}}]
   ["/ws" {:summary "Websocket endpoint to receive a twilio call"
           :get {:handler twilio-ws-handler-flow}}]])

(def app
  (ring/ring-handler
    (ring/router
      routes
      {:exception pretty/exception
       :data {:muuntaja m/instance
              :middleware [;; query-params & form-params
                           parameters/parameters-middleware
                           ;; content-negotiation
                           muuntaja/format-negotiate-middleware
                           ;; encoding response body
                           muuntaja/format-response-middleware
                           ;; exception handling
                           exception/exception-middleware
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

  (def server (start :port 3000))
  (.stop server)

  ,)
