(ns voice-fn.frames)

(defprotocol Frame
  "A frame is the basic data passed on the pipeline. Every pipeline processor
  expects one or multiple tipes of frames:
  - The transcription pipeline processor expects `UserAudioInputFrame` and generates `TranscriptionOutputFrame`
  - The LLM pipeline processor expects `TranscriptionOutputFrame` and generates `LLMOutputTokenFrame`"
  (frame-type [this] "Returns the type of frame")
  (timestamp [this] "Returns the timestamp")
  (payload [this] "Returns the frame's payload"))

;; Frame of user audio input. Used by transcription processors, turn-detection
;; processors and multimodal llm processors that support raw audio input
(defrecord UserRawAudioInputFrame  [ts data sample-rate channels]
  Frame
  (frame-type [_] :frame/user-audio-input)
  (timestamp [_] ts)
  (payload [_] data))

;; Frame of transcription output. Used by text-based llm processors
(defrecord TranscriptionOutputFrame [ts text confidence metadata]
  Frame
  (frame-type [_] :frame/transcription-output)
  (timestamp [_] ts)
  (payload [_] text))

(defn ->audio-frame
  [data & {:keys [sample-rate channels]
           :or {sample-rate 16000
                channels 1}}]
  (map->UserRawAudioInputFrame {:ts (System/currentTimeMillis)
                                :data data
                                :sample-rate sample-rate
                                :channels channels}))

(defn ->transcription-frame
  [text & {:keys [confidence metadata]
           :or {confidence 1.0
                metadata {}}}]
  (->TranscriptionOutputFrame (System/currentTimeMillis)
                              text
                              confidence
                              metadata))
