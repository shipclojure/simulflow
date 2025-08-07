(ns simulflow.protocols)

(defprotocol VADAnalyzer
  "The standard protocol for Voice Activity Detection Analizers."
  (analyze-audio [this audio-buffer]
    "Analize audio and give back the vad state associated with the current audio-buffer.
     Args:
       this - the VADAnalizer reification
       audio-buffer - byte array representing 16kHz PCM mono audio

     Returns:
       `:vad/speaking` `:vad/starting` `:vad/quiet` `:vad/stopping`")

  (voice-confidence [this audio-buffer]
    "Calculates voice activity confidence for the given audio buffer.
     Args:
      this - the VADAnalizer reification
      audio-buffer - byte array representing 16kHz PCM mono audio

     Returns:
      Voice confidence score between 0.0 and 1.0"))
