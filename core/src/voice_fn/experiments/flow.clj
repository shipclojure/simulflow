(ns voice-fn.experiments.flow
  (:require
   [clojure.core.async.flow :as flow]
   [hato.websocket :as ws]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.processors.deepgram :as deepgram]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.transport.serializers :refer [make-twilio-serializer]]
   [voice-fn.utils.core :as u])
  (:import
   (java.nio HeapCharBuffer)))

(def real-gdef
  {:procs
   {;; transport in receives base64 encoded audio and sends it to the next
    ;; processor (usually a transcription processor)
    :transport-in
    {:proc (flow/process
             {:describe (fn [] {:ins {:in "Channel for audio input "}
                                :outs {:sys-out "Channel for system messages that have priority"
                                       :out "Channel on which audio frames are put"}})

              :transform (fn [state _ input]
                           (let [data (u/parse-if-json input)]
                             (case (:event data)
                               "start" (when-let [stream-sid (:streamSid data)]
                                         [state {:system [(frame/system-config-change {:twilio/stream-sid stream-sid
                                                                                       :transport/serializer (make-twilio-serializer stream-sid)})]}])
                               "media"
                               [state {:out [(frame/audio-input-raw
                                               (u/decode-base64 (get-in data [:media :payload])))]}]

                               "close"
                               [state {:system [(frame/system-stop true)]}]
                               nil)))})}
    ;; transcription processor that will receive audio frames from transport in,
    ;; send them to ws-conn and send down to sink new transcriptions from websocket
    :deepgram-transcriptor
    {:proc
     (flow/process
       {:describe
        (fn [] {:ins {:sys-in "Channel for system messages that take priority"
                      :in "Channel for audio input frames (from transport-in) "}
                :outs {:sys-out "Channel for system messages that have priority"
                       :out "Channel on which transcription frames are put"}
                :params {:deepgram/api-key "Api key for deepgram"
                         :processor/supports-interrupt? "Wether this processor should send interrupt start/stop events on the pipeline"}
                :workload :io})
        :init
        (fn [args]
          (let [websocket-url (deepgram/make-websocket-url {:transcription/api-key (:deepgram/api-key args)
                                                            :transcription/interim-results? true
                                                            :transcription/punctuate? false
                                                            :transcription/vad-events? true
                                                            :transcription/smart-format? true
                                                            :transcription/model :nova-2
                                                            :transcription/utterance-end-ms 1000
                                                            :transcription/language :en
                                                            :transcription/encoding :mulaw
                                                            :transcription/sample-rate 8000})
                conn-config {:headers {"Authorization" (str "Token " (:deepgram/api-key args))}
                             :on-open (fn [ws]
                                        ;; Send a keepalive message every 3 seconds to maintain websocket connection
                                        #_(a/go-loop []
                                            (a/<! (a/timeout 3000))
                                            (when (get-in @pipeline [type :websocket/conn])
                                              (t/log! {:level :debug :id type} "Sending keep-alive message")
                                              (ws/send! ws keep-alive-payload)
                                              (recur)))
                                        (t/log! :info "Deepgram websocket connection open"))
                             :on-message (fn [_ws ^HeapCharBuffer data _last?]
                                           (let [m (u/parse-if-json (str data))]

                                             (cond
                                               (deepgram/speech-started-event? m)
                                               ;; (send-frame! pipeline (frame/user-speech-start true))
                                               (prn "Send speech started frame down the pipeline")

                                               (deepgram/utterance-end-event? m)
                                               ;; (send-frame! pipeline (frame/user-speech-stop true))
                                               (prn "Send speech stopped frame down the pipeline")

                                               (deepgram/final-transcript? m)
                                               ;; (send-frame! pipeline (frame/transcription trsc))
                                               (prn "send transcription frame down the pipeline")

                                               (deepgram/interim-transcript? m)
                                               ;; (send-frame! pipeline (send-frame! pipeline (frame/transcription-interim trsc)))
                                               (prn "send interim transcription frame down the pipeline"))))
                             :on-error (fn [_ e]
                                         (t/log! {:level :error :id :deepgram-transcriptor} ["Error" e]))
                             :on-close (fn [_ws code reason]
                                         (t/log! {:level :info :id :deepgram-transcriptor} ["Deepgram websocket connection closed" "Code:" code "Reason:" reason]))}
                _ (t/log! "Connecting to transcription websocket")
                ws-conn @(ws/websocket
                           websocket-url
                           conn-config)]
            {:websocket/conn ws-conn}))

        ;; Close ws when pipeline stops
        :transition (fn [{conn :websocket/conn} transition]
                      (prn "This got called")
                      (when (and (= transition ::flow/stop)
                                 conn)
                        (t/log! {:id :deepgram-transcriptor :level :debug} "Closing transcription websocket connection")
                        (ws/send! conn deepgram/close-connection-payload)
                        (ws/close! conn)))

        :transform (fn [{:websocket/keys [conn]} in-name frame]
                     (cond
                       (frame/audio-input-raw? frame)
                       (when conn (ws/send! (:frame/data frame)))))})}
    :print-sink
    {:proc (flow/process
             {:describe (fn [] {:ins {:in "Channel for receiving transcriptions"}})
              :transform (fn [_ _ frame]
                           (when (frame/transcription? frame)
                             (t/log! {:id :print-sink :level :info} ["Transcript: " (:frame/data frame)])))})}}
   :conns [[[:transport-in :sys-out] [:deepgram-transcriptor :sys-in]]
           [[:transport-in :out] [:deepgram-transcriptor :in]]
           [[:deepgram-transcriptor :out] [:print-sink :in]]]
   :args {:deepgram/api-key (secret [:deepgram :api-key])}})

(comment
  (def g (flow/create-flow real-gdef))

  (def res (flow/start g))

  (flow/resume g)
  (flow/stop g))
