(ns voice-fn.transport.websocket.transport
  (:require
   [clojure.core.async :as a]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.transport.websocket.protocols :as p]))

(defmethod pipeline/process-frame :transport/websocket
  [_ pipeline {:keys [websocket serializer]} frame]
  (case (:frame/type frame)
    :audio/output
    (let [serialized (p/serialize-frame serializer frame)]
      ;; TODO see http kit websocket
      (http/send! websocket serialized))

    :audio/raw-input
    (when-let [data (:data frame)]
      (let [deserialized (p/deserialize-frame serializer data)]
        (a/put! (:pipeline/main-ch @pipeline) deserialized)))

    nil))
