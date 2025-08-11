(ns simulflow.processors.audio-resampler
  "Audio resampling processor for converting between different sample rates and encodings.
   
   Transforms audio-input-raw frames from one format to another (e.g., 8kHz μ-law to 16kHz PCM).
   Useful for adapting audio from transport layers to transcription requirements."
  (:require
   [clojure.core.async.flow :as flow]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]
   [simulflow.utils.audio :as audio]
   [taoensso.telemere :as t]))

;; Configuration Schema
(def AudioResamplerConfigSchema
  [:map
   [:audio-resample/source-sample-rate
    {:default 8000
     :optional true
     :description "Input sample rate in Hz"}
    schema/SampleRate]
   [:audio-resample/target-sample-rate
    {:default 16000
     :optional true
     :description "Output sample rate in Hz"}
    schema/SampleRate]
   [:audio-resample/source-encoding
    {:default :ulaw
     :optional true
     :description "Input encoding format"}
    schema/AudioEncoding]
   [:audio-resample/target-encoding
    {:default :pcm-signed
     :optional true
     :description "Output encoding format"}
    schema/AudioEncoding]
   [:audio-resample/channels
    {:default 1
     :optional true
     :description "Number of audio channels"}
    schema/AudioChannels]
   [:audio-resample/sample-size-bits
    {:default 16
     :optional true
     :description "Bit depth of samples"}
    schema/SampleSizeBits]
   [:audio-resample/buffer-size
    {:default 1024
     :optional true
     :description "Audio buffer size in bytes"}
    schema/BufferSize]
   [:audio-resample/endian
    {:default :little-endian
     :optional true
     :description "Audio byte order (endianness)"}
    schema/AudioEndian]])

(defn audio-frame?
  [frame]
  (or
    (frame/audio-input-raw? frame)
    (frame/audio-output-raw? frame)))

(defn transform
  "Transform function that resamples audio-input-raw frames"
  [state input-port frame]
  (if (and (= input-port :in)
           (audio-frame? frame))
    (let [{:audio-resample/keys [source-sample-rate target-sample-rate
                                 source-encoding target-encoding
                                 channels sample-size-bits buffer-size endian]} state

          audio-data (:frame/data frame)

          source-config {:sample-rate source-sample-rate
                         :encoding source-encoding
                         :channels channels
                         :sample-size-bits (if (= source-encoding :ulaw) 8 sample-size-bits)
                         :buffer-size buffer-size
                         :endian endian}

          target-config {:sample-rate target-sample-rate
                         :encoding target-encoding
                         :channels channels
                         :sample-size-bits sample-size-bits
                         :buffer-size buffer-size
                         :endian endian}

          resampled-data (audio/resample-audio-data audio-data source-config target-config)

          output-frame (assoc frame :frame/data resampled-data)]

      (t/log! {:level :debug
               :id :audio-resampler
               :msg (format "Resampled audio: %d bytes -> %d bytes (%dkHz %s -> %dkHz %s)"
                            (alength audio-data) (alength resampled-data)
                            (/ source-sample-rate 1000) source-encoding
                            (/ target-sample-rate 1000) target-encoding)
               :sample 0.05})

      [state {:out [output-frame]}])

    ;; Pass through non-audio frames unchanged
    [state {:out [frame]}]))

(def describe
  {:ins {:in "Channel for audio-input-raw frames to be resampled"}
   :outs {:out "Channel for resampled audio-input-raw frames"}
   :params (schema/->describe-parameters AudioResamplerConfigSchema)})

(defn init!
  "Initialize the audio resampler with configuration"
  [params]
  (let [config (schema/parse-with-defaults AudioResamplerConfigSchema params)]
    (t/log! {:level :info :id :audio-resampler :data config}
            (format "Initialized audio resampler: %dkHz %s -> %dkHz %s"
                    (/ (:audio-resample/source-sample-rate config) 1000)
                    (:audio-resample/source-encoding config)
                    (/ (:audio-resample/target-sample-rate config) 1000)
                    (:audio-resample/target-encoding config)))
    config))

(defn transition
  "Handle processor lifecycle transitions"
  [state trs]
  (when (= trs ::flow/stop)
    (t/log! {:level :info :id :audio-resampler} "Audio resampler stopped"))
  state)

;; Multi-arity Processor Function

(defn audio-resampler-fn
  "Multi-arity function following simulflow processor pattern"
  ([] describe)
  ([params] (init! params))
  ([state trs] (transition state trs))
  ([state input-port frame] (transform state input-port frame)))

;; Flow Process

(def process
  "Audio resampling processor for flow integration"
  (flow/process audio-resampler-fn))
