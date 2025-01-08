(ns voice-fn.transport.async
  (:require
   [clojure.core.async :as a]
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
   [:transport/audio-chunk-size :int]])

(defmethod pipeline/create-processor :processor.transport/async-output
  [id]
  (reify p/Processor
    (processor-id [_] id)
    (processor-schema [_] AsyncOutputProcessorSchema)

    (accepted-frames [_] #{:frame.system/stop :frame.audio/output-raw :frame.system/start})

    (make-processor-config [_ pipeline-config processor-config]
      (let [{:audio-out/keys [sample-size-bits sample-rate channels]}
            pipeline-config
            duration-ms (or (:transport/duration-ms processor-config) 20)]
        {:transport/sample-rate sample-rate
         :transport/sample-size-bits sample-size-bits
         :transport/channels channels
         :transport/audio-chunk-duration duration-ms
         :transport/supports-interrupt? (:pipeline/supports-interrupt? pipeline-config)
         :transport/audio-chunk-size (au/audio-chunk-size {:sample-rate sample-rate
                                                           :sample-size-bits sample-size-bits
                                                           :channels channels
                                                           :duration-ms duration-ms})}))

    (process-frame [_ pipeline _processor-config frame]
      (let [{:transport/keys [out-ch serializer]} (:pipeline/config @pipeline)]
        (cond
          (frame/audio-output-raw? frame)
          (when-let [output (if serializer
                              (tp/serialize-frame serializer frame)
                              frame)]
            (a/put! out-ch output))
          (frame/system-stop? frame) (a/close! out-ch))))))
