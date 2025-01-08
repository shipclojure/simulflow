(ns voice-fn.transport.twilio
  (:require
   [clojure.core.async :as a]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.protocol :as p]
   [voice-fn.transport.serializers :refer [make-twilio-serializer]]
   [voice-fn.utils.core :as u]))

(defmethod pipeline/create-processor :processor.transport/twilio-input
  [id]
  (reify p/Processor
    (processor-id [_] id)

    (processor-schema [_] :any)

    (accepted-frames [_] #{:frame.system/start :frame.system/stop})

    (make-processor-config [_ _ processor-config]
      processor-config)

    (process-frame [_ pipeline _ frame]
      (let [{:transport/keys [in-ch]} (:pipeline/config @pipeline)]
        (cond
          (frame/system-start? frame)
          (do
            (t/log! {:level :info
                     :id id} "Staring transport input")
            (swap! pipeline assoc-in [id :running?] true)

            (a/go-loop []
              (when (get-in @pipeline [id :running?])
                (when-let [input (a/<! in-ch)]
                  (let [data (u/parse-if-json input)]
                    (case (:event data)
                      "start" (let [stream-sid (:streamSid data)]
                                (swap! pipeline update-in  [:pipeline/config]
                                       assoc :twilio/stream-sid stream-sid :transport/serializer (make-twilio-serializer stream-sid)))
                      "media" (a/put! (:pipeline/main-ch @pipeline)
                                      (frame/audio-input-raw
                                        (u/decode-base64 (get-in data [:media :payload]))))
                      "close" (swap! pipeline assoc-in [id :running?] false)
                      nil))
                  (recur)))))
          (frame/system-stop? frame)
          (do (t/log! {:level :info
                       :id id} "Stopping transport input")
              (swap! pipeline assoc-in [id :running?] false)))))))
