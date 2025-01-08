(ns voice-fn.processors.interrupt-state
  (:require
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]))

(defmethod pipeline/accepted-frames :processor/interrupt-state
  [_]
  #{:frame.control/interrupt-start :frame.control/interrupt-stop})

(defmethod pipeline/process-frame :processor/interrupt-state
  [type pipeline _ frame]
  (cond
    (frame/control-interrupt-start? frame)
    (do
      (t/log! {:level :debug :id type} "Setting pipeline interrupt state")
      (swap! pipeline assoc :pipeline/interrupted? true))

    (frame/control-interrupt-stop? frame)
    (do
      (t/log! {:level :debug :id type} "Clearing pipeline interrupt state")
      (swap! pipeline assoc :pipeline/interrupted? false))))
