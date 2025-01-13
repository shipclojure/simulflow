(ns voice-fn-examples.twilio-websocket
  (:require
   [clojure.core.async :as a]
   [clojure.data.xml :as xml]
   [muuntaja.core :as m]
   [portal.api :as p]
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
   [voice-fn.core]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.processors.elevenlabs]
   [voice-fn.processors.interrupt-state]
   [voice-fn.processors.llm-context-aggregator]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.transport.twilio]))

(t/set-min-level! :debug)
(comment
  (p/open)
  (add-tap #'p/submit))

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

(defn create-twilio-ai-pipeline
  [in out]
  {:pipeline/config {:audio-in/sample-rate 8000
                     :audio-in/encoding :ulaw
                     :audio-in/channels 1
                     :audio-in/sample-size-bits 8
                     :audio-out/sample-rate 8000
                     :audio-out/encoding :ulaw
                     :audio-out/sample-size-bits 8
                     :audio-out/channels 1
                     :pipeline/supports-interrupt? true
                     :pipeline/language :ro
                     :llm/context [{:role "system" :content  "Ești un agent vocal care funcționează prin telefon. Răspunde doar în limba română și fii succint. Inputul pe care îl primești vine dintr-un sistem de speech to text (transcription) care nu este intotdeauna eficient și poate trimite text neclar. Cere clarificări când nu ești sigur pe ce a spus omul."}]
                     :transport/in-ch in
                     :transport/out-ch out}
   :pipeline/processors [;; Twilio transport
                         {:processor/id :processor.transport/twilio-input}

                         ;; Transcription with deepgram
                         {:processor/id :processor.transcription/deepgram
                          :processor/config {:transcription/api-key (secret [:deepgram :api-key])
                                             :transcription/interim-results? true
                                             :transcription/punctuate? false
                                             :transcription/vad-events? true
                                             :transcription/smart-format? true
                                             :transcription/model :nova-2
                                             :transcription/utterance-end-ms 1000}}
                         ;; Aggregate User responses
                         {:processor/id :context.aggregator/user
                          :processor/config {:aggregator/debug? true}}
                         {:processor/id :processor.llm/groq
                          :processor/config {:llm/model "llama-3.3-70b-versatile"
                                             :groq/api-key (secret [:groq :api-key])}}
                         ;; aggregate AI responses
                         {:processor/id :context.aggregator/assistant
                          :processor/config {}}
                         ;; text to speech elevenlabs
                         {:processor/id :processor.speech/elevenlabs
                          :processor/config {:elevenlabs/api-key (secret [:elevenlabs :api-key])
                                             :elevenlabs/model-id "eleven_flash_v2_5"
                                             :elevenlabs/voice-id "7sJPxFeMXAVWZloGIqg2"
                                             :voice/stability 0.5
                                             :voice/similarity-boost 0.8
                                             :voice/use-speaker-boost? true}}
                         ;; Simple core.async channel output
                         {:processor/id :processor.transport/async-output}]})

(comment
  (pipeline/validate-pipeline (create-twilio-ai-pipeline (a/chan) (a/chan)))

  (let [processors-config (:pipeline/processors (create-twilio-ai-pipeline (a/chan) (a/chan)))])

  (let [pipeline (pipeline/create-pipeline (create-twilio-ai-pipeline (a/chan) (a/chan)))]
    (pipeline/start-pipeline! pipeline))

  ,)

;; Using ring websocket protocols to setup a websocket server
(defn twilio-ws-handler
  [req]
  (assert (ws/upgrade-request? req))
  (prn "Got here")
  (let [in (a/chan 1024)
        out (a/chan 1024)
        pipeline (pipeline/create-pipeline (create-twilio-ai-pipeline in out))
        start-pipeline (fn [socket]
                         ;; listen on the output channel we provided to send
                         ;; that audio back to twilio
                         (a/go-loop []
                           (when-let [output (a/<! out)]
                             (ws/send socket output)
                             (recur)))
                         (pipeline/start-pipeline! pipeline))]
    {::ws/listener
     {:on-open (fn on-open [socket]
                 (prn "Got on open")
                 (try
                   (start-pipeline socket)
                   (catch Exception err
                     (prn err)))

                 nil)
      :on-message (fn on-text [_ws payload]
                    (a/put! in payload))
      :on-close (fn on-close [_ws _status-code _reason]
                  (pipeline/stop-pipeline! pipeline))
      :on-error (fn on-error [ws error]
                  (prn error)
                  (t/log! :debug error))
      :on-ping (fn on-ping [ws payload]
                 (ws/send ws payload))}}))

(def routes
  [["/inbound-call" {:summary "Webhook where a call is made"
                     :post {:handler twilio-inbound-handler}}]
   ["/ws" {:summary "Websocket endpoint to receive a twilio call"
           :get {:handler twilio-ws-handler}}]])

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
