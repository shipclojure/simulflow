(ns voice-fn.core
  (:require
   [taoensso.telemere :as t]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.processors.deepgram]
   [voice-fn.transport.local.audio]))

(def pipeline-config
  [{:type :transport/local-audio
    :accepted-frames #{:system/start :system/stop}
    :config {:sample-rate 16000
             :channels 1}}
   {:type :transcription/deepgram
    :accepted-frames #{:system/start :system/stop :audio/raw-input}
    :config {:api-key "458e3de27b9f4fd7bb3c55d6aadb69565640062e"}}
   {:type :log/text-input
    :accepted-frames #{:text/input}
    :config {}}])

(defmethod pipeline/process-frame :log/text-input
  [_ _ _ frame]
  (t/log! :info ["Transcription" (:text frame)]))

(comment
  (def p (pipeline/create-pipeline pipeline-config))

  (pipeline/start-pipeline! p)
  (pipeline/stop-pipeline! p)

  ,)
