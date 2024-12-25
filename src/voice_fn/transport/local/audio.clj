(ns voice-fn.transport.local.audio
  (:require
   [clojure.core.async :as async]
   [uncomplicate.clojure-sound.core :as cs]
   [uncomplicate.clojure-sound.sampled :as sampled]
   [uncomplicate.commons.core :refer [with-release]]
   [voice-fn.frames :as frames]
   [voice-fn.processors.protocols :refer [FrameProcessor ProcessorLifecycle]])
  (:import
   (javax.sound.sampled AudioFormat AudioSystem DataLine$Info SourceDataLine TargetDataLine)))

(comment
  (with-release [mxr (sampled/mixer)
                 target (sampled/line mxr (sampled/line-info :target (sampled/audio-format 44100.0 16 2)))
                 src (sampled/line (sampled/line-info :source (sampled/audio-format 44100.0 16 2)))
                 finished? (promise)]
    (cs/listen! target (partial deliver finished?) :stop)))

(comment
  (defrecord LocalAudioParams [audio-in-sample-rate
                               audio-in-channels
                               audio-out-sample-rate
                               audio-out-channels])

  (defrecord LocalAudioInputTransport [params audio-line buffer-size control-chan]
    FrameProcessor
    (process-frame [this frame direction]
      (async/go
        (case (type frame)
          frames/StartFrame (do
                              (.start audio-line)
                              (push-frame! this frame))
          frames/EndFrame (do
                            (.stop audio-line)
                            (.close audio-line)
                            (push-frame! this frame))
          frames/CancelFrame (do
                               (.stop audio-line)
                               (.close audio-line)
                               (push-frame! this frame))
          ;; Default - pass through
          (push-frame! this frame))))

    ProcessorLifecycle
    (start [this start-frame]
      (let [format (AudioFormat. (:audio-in-sample-rate params)
                     16  ;; sample size bits
                     (:audio-in-channels params)
                     true ;; signed
                     true) ;; big endian
            info (DataLine$Info. TargetDataLine format)
            line (AudioSystem/getLine info)]
        (.open line format)
        (reset! audio-line line)
        ;; Start capture loop
        (async/go-loop []
          (when-not (async/poll! control-chan)
            (let [buffer (byte-array buffer-size)
                  bytes-read (.read @audio-line buffer 0 buffer-size)]
              (when (pos? bytes-read)
                (push-frame! this (frames/->InputAudioRawFrame
                                    (Arrays/copyOf buffer bytes-read)
                                    (:audio-in-sample-rate params)
                                    (:audio-in-channels params))))
              (recur))))))

    (stop [this]
      (async/put! control-chan :stop)))

  (defrecord LocalAudioOutputTransport [params audio-line buffer-size]
    FrameProcessor
    (process-frame [this frame direction]
      (async/go
        (case (type frame)
          frames/StartFrame (do
                              (.start audio-line)
                              (push-frame! this frame))
          frames/EndFrame (do
                            (.drain audio-line)
                            (.stop audio-line)
                            (.close audio-line)
                            (push-frame! this frame))
          frames/OutputAudioRawFrame (do
                                       (.write audio-line
                                               (:audio frame)
                                               0
                                               (alength (:audio frame)))
                                       (push-frame! this frame))
          ;; Default - pass through
          (push-frame! this frame))))

    ProcessorLifecycle
    (start [this start-frame]
      (let [format (AudioFormat. (:audio-out-sample-rate params)
                                 16 ;; sample size bits
                                 (:audio-out-channels params)
                                 true  ;; signed
                                 true) ;; big endian
            info (DataLine$Info. SourceDataLine format)
            line (AudioSystem/getLine info)]
        (.open line format)
        (reset! audio-line line))))

  ,)
