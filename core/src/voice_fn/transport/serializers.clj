(ns voice-fn.transport.serializers
  (:require
   [voice-fn.frames :as frames]
   [voice-fn.transport.protocols :as p]
   [voice-fn.utils.core :as u]))

;; Example Twilio serializer
(defn make-twilio-serializer [stream-sid]
  (reify
    p/FrameSerializer
    (serialize-frame [_ frame]
      ;; Convert pipeline frame to Twilio-specific format
      (case (:frame/type frame)
        :audio/output (u/json-str {:event "media"
                                   :streamSid stream-sid
                                   :media {:payload (:data frame)}})
        nil))

    (deserialize-frame [_ raw-data]
      ;; Convert Twilio message to pipeline frame
      (let [data (u/parse-if-json raw-data)]
        (case (:event data)
          ;; TODO more cases
          "media" (frames/audio-input-frame (u/decode-base64 (:payload (:media data))))
          nil)))))
