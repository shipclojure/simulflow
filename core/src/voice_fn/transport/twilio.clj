(ns voice-fn.transport.twilio
  (:require
   [clojure.core.async :as a]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.transport.serializers :refer [make-twilio-serializer]]
   [voice-fn.utils.core :as u]))

(defmethod pipeline/accepted-frames :transport/twilio-input
  [_]
  #{:frame.system/stop :frame.system/start})

(defmethod pipeline/process-frame :transport/twilio-input
  [processor-type pipeline _ frame]
  (let [{:transport/keys [in-ch]} (:pipeline/config @pipeline)
        running? (atom false)]
    (cond
      (frame/system-start? frame)
      (do
        (t/log! {:level :info
                 :id processor-type} "Staring transport input")
        (reset! running? true)
        (a/go-loop []
          (when running?
            (when-let [input (a/<! in-ch)]
              (let [data (u/parse-if-json input)]
                (case (:event data)
                  "start" (let [stream-sid (:streamSid data)]
                            (swap! pipeline update-in  [:pipeline/config]
                                   assoc :twilio/stream-sid stream-sid :transport/serializer (make-twilio-serializer stream-sid)))
                  "media" (a/put! (:pipeline/main-ch @pipeline)
                                  (frame/audio-input-raw
                                    (u/decode-base64 (get-in data [:media :payload]))))
                  "close" (reset! running? false)
                  nil))
              (recur)))))
      (frame/system-stop? frame)
      (do (t/log! {:level :info
                   :id processor-type} "Stopping transport input")
          (reset! running? false)))))
