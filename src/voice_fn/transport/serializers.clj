(ns voice-fn.transport.serializers
  (:require
   [voice-fn.frame :as frame]
   [voice-fn.transport.protocols :as p]
   [voice-fn.utils.core :as u]))

;; Example Twilio serializer
(defn make-twilio-serializer [stream-sid]
  (reify
    p/FrameSerializer
    (serialize-frame [_ frame]
      ;; Convert pipeline frame to Twilio-specific format
      (when (frame/audio-output-raw? frame)
        (u/json-str {:event "media"
                     :streamSid stream-sid
                     :media {:payload (u/encode-base64 (:data frame))}})))

    (deserialize-frame [_ raw-data]
      ;; Convert Twilio message to pipeline frame
      (let [data (u/parse-if-json raw-data)]
        (case (:event data)
          ;; TODO more cases
          "media" (frame/audio-input-raw (u/decode-base64 (:payload (:media data))))
          nil)))))
