(ns voice-fn.experiments.flow
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [clojure.datafy :refer [datafy]]
   [hato.websocket :as ws]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.processors.deepgram :as deepgram]
   [voice-fn.processors.llm-context-aggregator :as ca]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.transport.serializers :refer [make-twilio-serializer]]
   [voice-fn.utils.core :as u])
  (:import
   (java.nio HeapCharBuffer)))

(t/set-min-level! :debug)

(def transport-in
  (flow/process
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
                      nil)))}))

(def deepgram-processor
  (flow/process
    {:describe (fn [] {:ins {:sys-in "Channel for system messages that take priority"
                             :in "Channel for audio input frames (from transport-in) "}
                       :outs {:sys-out "Channel for system messages that have priority"
                              :out "Channel on which transcription frames are put"}
                       :params {:transcription/api-key "Api key required for deepgram connection"
                                :transcription/interim-results? "Wether deepgram should send interim transcriptions back"
                                :transcription/punctuate? "If transcriptions are punctuated or not. Not required if transcription/smart-format is true"
                                :transcription/vad-events? "Enable this for deepgram to send speech-start/utterance end events"
                                :transcription/smart-format? "Enable smart format"
                                :transcription/model "Model used for transcription"
                                :transcription/utterance-end-ms "silence time after speech in ms until utterance is considered ended"
                                :transcription/language "Language for speech"
                                :transcription/encoding "Audio encoding of the input audio"
                                :transcription/sample-rate "Sample rate of the input audio"}
                       :workload :io})
     :init (fn [args]
             (let [websocket-url (deepgram/make-websocket-url args)
                   ws-read-chan (a/chan 1024)
                   ws-write-chan (a/chan 1024)
                   alive? (atom true)
                   conn-config {:headers {"Authorization" (str "Token " (:deepgram/api-key args))}
                                :on-open (fn [_]
                                           (t/log! :info "Deepgram websocket connection open"))
                                :on-message (fn [_ws ^HeapCharBuffer data _last?]
                                              (a/go (a/>! ws-read-chan (str data))))
                                :on-error (fn [_ e]
                                            (t/log! {:level :error :id :deepgram-transcriptor} ["Error" e]))
                                :on-close (fn [_ws code reason]
                                            (reset! alive? false)
                                            (t/log! {:level :info :id :deepgram-transcriptor} ["Deepgram websocket connection closed" "Code:" code "Reason:" reason]))}

                   _ (t/log! {:level :info :id :deepgram-transcriptor} "Connecting to transcription websocket")
                   ws-conn @(ws/websocket
                              websocket-url
                              conn-config)

                   write-to-ws #(loop []
                                  (when @alive?
                                    (when-let [msg (a/<!! ws-write-chan)]
                                      (cond
                                        (and (frame/audio-input-raw? msg) @alive?)
                                        (do
                                          (ws/send! ws-conn (:frame/data msg))
                                          (recur))))))
                   keep-alive #(loop []
                                 (when @alive?
                                   (a/<!! (a/timeout 3000))
                                   (t/log! {:level :debug :id :deepgram} "Sending keep-alive message")
                                   (ws/send! ws-conn deepgram/keep-alive-payload)
                                   (recur)))]
               ((flow/futurize write-to-ws :exec :io))
               ((flow/futurize keep-alive :exec :io))

               {:websocket/conn ws-conn
                :websocket/alive? alive?
                ::flow/in-ports {:ws-read ws-read-chan}
                ::flow/out-ports {:ws-write ws-write-chan}}))

     ;; Close ws when pipeline stops
     :transition (fn [{:websocket/keys [conn] :as state} transition]
                   (t/log! {:level :debug} ["TRANSITION" transition])
                   (when (= transition ::flow/stop)
                     (t/log! {:id :deepgram-transcriptor :level :info} "Closing transcription websocket connection")
                     (reset! (:websocket/alive? state) false)
                     (when conn
                       (ws/send! conn deepgram/close-connection-payload)
                       (ws/close! conn))

                     state)
                   state)

     :transform (fn [state in-name msg]
                  (if (= in-name :ws-read)
                    (let [m (u/parse-if-json msg)
                          frames (deepgram/deepgram-event->frames m)]
                      [state {:out frames}])
                    (cond
                      (frame/audio-input-raw? msg)
                      [state {:ws-write [msg]}])))}))

(def context-aggregator-process
  (flow/process
    {:describe (fn [] {:ins {:in "Channel for aggregation messages"}
                       :outs {:out "Channel where new context aggregations are put"}})
     :params {:llm/context "Initial LLM context. See schema/LLMContext"
              :messages/role "Role that this processor aggregates"
              :aggregator/start-frame? "Predicate checking if the frame is a start-frame?"
              :aggregator/end-frame? "Predicate checking if the frame is a end-frame?"
              :aggregator/accumulator-frame? "Predicate checking the main type of frame we are aggregating"
              :aggregator/interim-results-frame? "Optional predicate checking if the frame is an interim results frame"
              :aggregator/handles-interrupt? "Wether this aggregator should handle or not interrupts"
              :aggregator/debug? "When true, debug logs will be called"}
     :workload :compute
     :init identity
     :transform ca/aggregator-transform}))

(def gdef
  {:procs
   {:transport-in {:proc transport-in}
    :deepgram-transcriptor {:proc deepgram-processor
                            :args {:transcription/api-key (secret [:deepgram :api-key])
                                   :transcription/interim-results? true
                                   :transcription/punctuate? false
                                   :transcription/vad-events? true
                                   :transcription/smart-format? true
                                   :transcription/model :nova-2
                                   :transcription/utterance-end-ms 1000
                                   :transcription/language :ro
                                   :transcription/encoding :mulaw
                                   :transcription/sample-rate 8000}}
    :user-context-aggregator  {:proc context-aggregator-process
                               :args {:messages/role "user"
                                      :aggregator/start-frame? frame/user-speech-start?
                                      :aggregator/end-frame? frame/user-speech-stop?
                                      :aggregator/accumulator-frame? frame/transcription?
                                      :aggregator/interim-results-frame? frame/transcription-interim?
                                      :aggregator/handles-interrupt? false ;; User speaking shouldn't be interrupted
                                      :aggregator/debug? true}}

    :print-sink {:proc (flow/process
                         {:describe (fn [] {:ins {:in "Channel for receiving transcriptions"}})
                          :transform (fn [_ _ frame]
                                       (t/log! {:id :print-sink :level :info} ["RESULT: " (:frame/data frame)]))})}}

   :conns [[[:transport-in :sys-out] [:deepgram-transcriptor :sys-in]]
           [[:transport-in :out] [:deepgram-transcriptor :in]]
           [[:deepgram-transcriptor :out] [:user-context-aggregator :in]]
           [[:user-context-aggregator :out] [:print-sink :in]]]})

(comment
  (datafy (:proc (:deepgram-transcriptor (:procs gdef))))

  (def g (flow/create-flow gdef))

  (def res (flow/start g))

  res

  (flow/resume g)
  (flow/stop g)

  ,)
