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

(defn mono-time
  "Monotonic time in milliseconds. Used to check if we should send the next chunk
  of audio."
  []
  (int (/ (System/nanoTime)  1e6)))

(def AsyncOutputProcessorSchema
  [:map
   [:transport/sample-rate schema/SampleRate]
   [:transport/sample-size-bits schema/SampleSizeBits]
   [:transport/channels schema/AudioChannels]
   [:transport/supports-interrupt? :boolean]
   [:transport/audio-chunk-duration :int] ;; duration in ms for each audio chunk. Default 20ms
   [:transport/audio-chunk-size :int]
   [:transport/out-ch schema/CoreAsyncChannel]])

(defn onto-chunks-chan!
  "Split the audio-frame into chunks of chunk-size and put them onto chunks-ch"
  [chunks-ch chunk-size audio-frame]
  (a/go-loop [audio (:frame/data audio-frame)]
    (let [audio-size (count audio)
          chunk-actual-size (min chunk-size audio-size)
          chunk (byte-array chunk-actual-size)]
      ;; Copy chunk-size amount of data into next chunk
      (System/arraycopy audio 0 chunk 0 chunk-actual-size)
      (a/>! chunks-ch (frame/audio-output-raw chunk))
      (when (> audio-size chunk-actual-size)
        (let [new-audio-size (- audio-size chunk-actual-size)
              remaining-audio (byte-array new-audio-size)]
          (System/arraycopy audio chunk-actual-size remaining-audio 0 new-audio-size)
          (recur remaining-audio))))))

(defn start-realtime-output-loop!
  "Starts a loop that sends audio chunks at regular intervals"
  [id pipeline processor-config]
  (let [{:transport/keys [out-ch audio-chunk-duration]} processor-config
        sending-interval (/ audio-chunk-duration 2)]
    (a/go-loop []
      (let [{:keys [chunks-ch next-send-time streaming?]} (get @pipeline id)]
        (when streaming?
          ;; Take a frame from the chunks channel
          (when-let [frame (a/<! chunks-ch)]
            (let [now (mono-time)]

              ;; Wait sending-interval or less until we can send again
              (a/<! (a/timeout (- next-send-time now)))

              (let [serializer (get-in @pipeline [:pipeline/config :transport/serializer])]
                (a/>! out-ch
                      (if serializer
                        (tp/serialize-frame serializer frame)
                        frame))
                ;; Set next send time
                (swap! pipeline assoc-in [id :next-send-time] (+ now sending-interval))
                (recur)))))))))

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
        (let [now (mono-time)]
          (do
            (t/log! {:level :debug :id id} "Starting audio chunking")
            (swap! pipeline update-in [id] merge {:next-send-time now
                                                  :chunks-ch (a/chan 1024)
                                                  :streaming? true})
            ;; Start streaming audio output
            (start-realtime-output-loop! id pipeline processor-config)))

        ;; Stop frame - clean up state and send remaining audio
        (frame/system-stop? frame)
        (do
          (t/log! {:level :debug :id id} "Stopping audio chunking")

          ;; Stop chunking loop by setting chunking? to false
          (when-let [chunks-ch (get-in @pipeline [id :chunks-ch])]
            (a/close! chunks-ch)
            (swap! pipeline dissoc id))
          (swap! pipeline update id dissoc :streaming?))

        ;; Audio frame - append to buffer
        (and (frame/audio-output-raw? frame)
             (:streaming? (get @pipeline id)))
        (let [chunk-size (:transport/audio-chunk-size processor-config)]
          (when-let [chunks-ch (get-in @pipeline [id :chunks-ch])]
            (onto-chunks-chan! chunks-ch chunk-size frame)))))))
