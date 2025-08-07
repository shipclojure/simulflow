(ns simulflow.vad.core)

(def default-params
  "Default parameters for VAD.
   :vad/min-confidence - minimum confidence threshold for voice detection
   :vad/min-speech-duration-ms - duration to wait before confirming voice start
   :vad/min-silence-duration-ms  - duration to wait before confirming voice end
   :vad/min-volume - minimum audio volume threshold for voice detection

   Note: min-volume is calibrated for RMS-based volume calculation (0.0-1.0 range).
   Typical speech levels: quiet=0.01-0.05, normal=0.05-0.15, loud=0.15-0.35"
  {:vad/min-confidence 0.7
   :vad/min-speech-duration-ms 200
   :vad/min-silence-duration-ms 800
   :vad/min-volume 0.01})
