(ns voice-fn.processors.interrupt-state
  (:require
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.protocol :as p]))

(defmethod pipeline/create-processor :processor.system/pipeline-interruptor
  [id]
  (reify p/Processor
    (processor-id [_] id)

    (processor-schema [_] :any)

    (accepted-frames [_] #{:frame.control/interrupt-start :frame.control/interrupt-stop})

    (make-processor-config [_ _ processor-config]
      processor-config)

    (process-frame [_ pipeline _ frame]
      (cond
        (frame/control-interrupt-start? frame)
        (do
          (t/log! {:level :debug :id type} "Setting pipeline interrupt state")
          (swap! pipeline assoc :pipeline/interrupted? true))

        (frame/control-interrupt-stop? frame)
        (do
          (t/log! {:level :debug :id type} "Clearing pipeline interrupt state")
          (swap! pipeline assoc :pipeline/interrupted? false))))))
