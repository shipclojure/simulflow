(ns voice-fn.transport.async
  (:require
   [clojure.core.async :as a]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.protocol :as p]
   [voice-fn.schema :as schema]
   [voice-fn.transport.protocols :as tp]
   [voice-fn.utils.audio :as au]))

(def AsyncOutputProcessorSchema
  [:map
   [:transport/sample-rate schema/SampleRate]
   [:transport/sample-size-bits schema/SampleSizeBits]
   [:transport/channels schema/AudioChannels]
   [:transport/supports-interrupt? :boolean]
   [:transport/audio-chunk-duration :int] ;; duration in ms for each audio chunk. Default 20ms
   [:transport/audio-chunk-size :int]
   [:transport/out-ch schema/CoreAsyncChannel]])

(defn start-chunking-loop!
  "Starts a loop that sends audio chunks at regular intervals"
  [id pipeline processor-config]
  (let [{:transport/keys [out-ch audio-chunk-size
                          audio-chunk-duration]} processor-config
        chunk-interval (/ audio-chunk-duration 2)]

    (a/go-loop []
      (when (:chunking? (get @pipeline id))
        (let [buffer (:audio-buffer (get @pipeline id))
              buffer-size (count buffer)]

          (when (>= buffer-size audio-chunk-size)
            ;; Extract chunk and update buffer
            (let [chunk (byte-array audio-chunk-size)
                  remaining (byte-array (- buffer-size audio-chunk-size))]

              ;; Copy first chunk-size bytes to chunk
              (System/arraycopy buffer 0 chunk 0 audio-chunk-size)
              ;; Copy remaining bytes to new buffer
              (System/arraycopy buffer
                                audio-chunk-size
                                remaining
                                0
                                (- buffer-size audio-chunk-size))

              ;; Update buffer in state
              (swap! pipeline assoc-in [id :audio-buffer] remaining)

              ;; Send chunk
              (when out-ch
                (let [frame (frame/audio-output-raw chunk)
                      serializer (get-in @pipeline [:pipeline/config :transport/serializer])]
                  (a/put! out-ch
                          (if serializer
                            (tp/serialize-frame serializer frame)
                            frame))))))

          ;; Wait for next interval
          (a/<! (a/timeout chunk-interval))
          (recur))))))

(defmethod pipeline/create-processor :processor.transport/async-output
  [id]
  (reify p/Processor
    (processor-id [_] id)
    (processor-schema [_] AsyncOutputProcessorSchema)

    (accepted-frames [_] #{:frame.system/stop :frame.audio/output-raw :frame.system/start})

    (make-processor-config [_ pipeline-config processor-config]
      (let [{:audio-out/keys [sample-size-bits sample-rate channels]} pipeline-config
            duration-ms (or (:transport/duration-ms processor-config) 20)]
        {:transport/sample-rate sample-rate
         :transport/sample-size-bits sample-size-bits
         :transport/channels channels
         :transport/out-ch (:transport/out-ch pipeline-config)
         :transport/audio-chunk-duration duration-ms
         :transport/supports-interrupt? (:pipeline/supports-interrupt? pipeline-config)
         :transport/audio-chunk-size (au/audio-chunk-size {:sample-rate sample-rate
                                                           :sample-size-bits sample-size-bits
                                                           :channels channels
                                                           :duration-ms duration-ms})}))

    (process-frame [_ pipeline processor-config frame]
      (cond
        ;; Start frame - initialize chunking state and start chunking loop
        (frame/system-start? frame)
        (do
          (t/log! {:level :debug :id id} "Starting audio chunking")
          (swap! pipeline update-in [id]
                 merge {:audio-buffer (byte-array 0)
                        :chunking? true})
          ;; Start the independent chunking loop
          (start-chunking-loop! id pipeline processor-config))

        ;; Stop frame - clean up state and send remaining audio
        (frame/system-stop? frame)
        (do
          (t/log! {:level :debug :id id} "Stopping audio chunking")

          ;; Stop chunking loop by setting chunking? to false
          (swap! pipeline update id dissoc :audio-buffer :chunking?))

        ;; Audio frame - append to buffer
        (and (frame/audio-output-raw? frame)
             (:chunking? (get @pipeline id)))
        (let [current-buffer (:audio-buffer (get @pipeline id))
              current-size (count current-buffer)
              frame-audio (:frame/data frame)
              new-buffer (byte-array (+ (count current-buffer)
                                       (count frame-audio)))]
          ;; Copy existing buffer content
          (when (pos? current-size)
            (System/arraycopy current-buffer 0 new-buffer 0 current-size))
          ;; Append new audio data
          (System/arraycopy frame-audio 0 new-buffer current-size (count frame-audio))
          ;; Update buffer in state
          (swap! pipeline assoc-in [id :audio-buffer] new-buffer))))))
