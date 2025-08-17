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
                                       audio/ulaw8k->pcm16k))
    nil))

(defn make-twilio-serializer [stream-sid & {:keys [convert-audio?]
                                            :or {convert-audio? false}}]
  (reify
    p/FrameSerializer
    (serialize-frame [_ frame]
      ;; Convert pipeline frame to Twilio-specific format
      (if (frame/audio-output-raw? frame)
        (let [{:keys [sample-rate audio]} (:frame/data frame)]
          (u/json-str {:event "media"
                       :streamSid stream-sid
                       :media {:payload (u/encode-base64 (if convert-audio?
                                                           (audio/pcm->ulaw8k audio sample-rate)
                                                           audio))}}))
        frame))))
