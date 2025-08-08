(ns simulflow.transport.codecs
  (:require
   [simulflow.frame :as frame]
   [simulflow.transport.protocols :as p]
   [simulflow.utils.audio :as audio]
   [simulflow.utils.core :as u]))

(defn deserialize-twilio-data
  "Convert twilio message to pipeline frame"
  [json-data]
  (case (:event json-data)
    ;; TODO more cases
    "media" (frame/audio-input-raw (-> json-data
                                       :media
                                       :payload
                                       u/decode-base64
                                       audio/ulaw->pcm16k))
    nil))

(defn make-twilio-serializer [stream-sid]
  (reify
    p/FrameSerializer
    (serialize-frame [_ frame]
      ;; Convert pipeline frame to Twilio-specific format
      (when (frame/audio-output-raw? frame)
        (u/json-str {:event "media"
                     :streamSid stream-sid
                     :media {:payload (u/encode-base64 (:frame/data frame))}})))))
