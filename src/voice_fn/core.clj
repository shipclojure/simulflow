(ns voice-fn.core
  (:require
   [taoensso.telemere :as t]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.processors.deepgram]
   [voice-fn.transport.local.audio]))

(def pipeline-config
  [{:type :transport/local-audio
    :accepted-frames #{:system/start :system/stop}
    :generates-frames #{:audio/raw-input}
    :config {:sample-rate 16000
             :channels 1}}
   {:type :log/text-input
    :accepted-frames #{:text/input}
    :config {}}])

(defmethod pipeline/process-frame :log/text-input
  [_ _ _ frame]
  (t/log! {:level :info
           :id :log/text-input} ["Frame" (:data frame)]))

(t/set-min-level! :debug)

(comment
  (def p (pipeline/create-pipeline pipeline-config))

  @p

  (pipeline/start-pipeline! p)
  (pipeline/stop-pipeline! p)

  ,)
