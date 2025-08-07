(ns simulflow.vad.core)

(def default-params
  "Default parameters for VAD.
   :vad/confidence - minimum confidence threshold for voice detection
   :vad/start-secs - duration to wait before confirming voice start
   :vad/stop-secs  - duration to wait before confirming voice end
   :vad/min-volume - minimuim audio volume threshold for voice detection"
  {:vad/confidence 0.7
   :vad/start-secs 0.2
   :vad/stop-secs 0.8
   :vad/min-volume 0.6})
