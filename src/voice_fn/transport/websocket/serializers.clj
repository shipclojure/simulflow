(ns voice-fn.transport.websocket.serializers
  (:require
   [voice-fn.frames :as frames]
   [voice-fn.transport.websocket.protocols :as p]
   [voice-fn.utils.core :as u]))

;; Example Twilio serializer
(defrecord TwilioSerializer [stream-sid]
  p/FrameSerializer
  (serialize-frame [_ frame]
    ;; Convert pipeline frame to Twilio-specific format
    (case (:frame/type frame)
      :audio/output {:event "media"
                     :streamSid stream-sid
                     :media {:payload (:data frame)}}))

  (deserialize-frame [_ raw-data]
    ;; Convert Twilio message to pipeline frame
    (let [data (u/parse-if-json raw-data)]
      (case (:event data)
        ;; TODO more cases
        "media" (frames/audio-input-frame (:payload (:media data)))))))
