(ns voice-fn.processors.protocols)

(defprotocol FrameProcessor
  "A frame processor is one of the pieces from the pipeline. Examples:
  - `TranscriptionProcessor`: processes `RawAudioInputFrames` and outputs `TranscriptionOutputFrames`
  - `TextLLMProcessor`: processes `TranscriptionOutputFrames` and outputs `LLMOutputTokenFrames`"
  (process-frame [this frame] "Process a frame of a given type. May output 0, 1 or multiple frames in the pipeline"))

(defprotocol ProcessorLifecycle
  (start [this start-frame] "Start the processor. Optionally push a start frame further in the pipeline")
  (stop [this end-frame] "Stop the processor. Optionally push a stop frame further in the pipeline."))
