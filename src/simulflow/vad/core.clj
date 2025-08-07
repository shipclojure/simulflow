(ns simulflow.vad.core)

(defprotocol VADAnalyzer
  "The standard protocol for Voice Activity Detection Analizers."
  (analyze-audio [this audio-buffer]
    "Analize audio and give back the vad state associated with the current audio-buffer.
     Args:
       this - the VADAnalizer reification
       audio-buffer - byte array representing 16kHz PCM mono audio

     Returns:
       `:vad.state/speaking` `:vad.state/starting` `:vad.state/quiet` `:vad.state/stopping`")

  (voice-confidence [this audio-buffer]
    "Calculates voice activity confidence for the given audio buffer.
     Args:
      this - the VADAnalizer reification
      audio-buffer - byte array representing 16kHz PCM mono audio

     Returns:
      Voice confidence score between 0.0 and 1.0"))

(def default-params
  "Default parameters for VAD.
   :vad/min-confidence - minimum confidence threshold for voice detection
   :vad/min-speech-duration-ms - duration to wait before confirming voice start
   :vad/min-silence-duration-ms - duration to wait before confirming voice end"
  {:vad/min-confidence 0.7
   :vad/min-speech-duration-ms 200
   :vad/min-silence-duration-ms 800})

(defn transition?
  [vad-state]
  (or (= vad-state :vad.state/stopping)
      (= vad-state :vad.state/starting)))
