(ns voice-fn-examples.local
  (:require
   [clojure.core.async.flow :as flow]
   [clojure.pprint :as pprint]
   [voice-fn.processors.deepgram :as asr]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.transport.local.audio :refer [local-transport-in]]))

(def dummy-flow
  (flow/create-flow
    {:procs {:transport-in {:proc local-transport-in
                            :args {}}
             :transcriptor {:proc asr/deepgram-processor
                            :args {:transcription/api-key (secret [:deepgram :api-key])
                                   :transcription/interim-results? true
                                   :transcription/punctuate? false
                                   :transcription/vad-events? true
                                   :transcription/smart-format? true
                                   :transcription/model :nova-2
                                   :transcription/utterance-end-ms 1000
                                   :transcription/language :en
                                   :transcription/encoding :linear16
                                   :transcription/sample-rate 16000}}
             :sink {:proc (flow/process {:describe (fn [] {:ins {:in "Input channel"}})
                                         :transform (fn [_ _ msg]
                                                      (pprint/pprint (:frame/data msg)))})}}
     :conns [[[:transport-in :out] [:transcriptor :in]]
             [[:transcriptor :out] [:sink :in]]]}))

(comment

  (let [{:keys [report-chan error-chan]} (flow/start dummy-flow)]
    (a/go-loop []
      (when-let [[msg c] (a/alts! [report-chan error-chan])]
        (when (map? msg)
          (t/log! {:level :debug :id (if (= c error-chan) :error :report)} msg))
        (recur))))

  (flow/resume dummy-flow)
  (flow/stop dummy-flow)

  ,)
