(ns voice-fn.transport.websocket.transport
  (:require
   [clojure.core.async :as a]
   [ring.websocket :as ws]
   [taoensso.telemere :as t]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.transport.websocket.protocols :as p]))

(defmethod pipeline/process-frame :transport/websocket-input
  [_ pipeline {:keys [websocket serializer]} frame]
  (case (:frame/type frame)
    :system/start
    (do
      (t/log! :debug "Starting websocket transport input"))

    nil))

(defmethod pipeline/process-frame :transport/websocket-output
  [_ pipeline {:keys [websocket serializer]} frame]
  (case (:frame/type frame)

    nil))
