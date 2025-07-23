(ns simulflow.utils.audio
  (:require [uncomplicate.clojure-sound.core :as sound]
            [uncomplicate.clojure-sound.sampled :as sampled])
  (:import (javax.sound.sampled AudioFormat AudioSystem DataLine$Info)))

(defn line-supported?
  [^DataLine$Info info]
  (AudioSystem/isLineSupported info))

(defn open-line!
  "Opens line with specified format. Returns the TargetDataLine or SourceDataLine"
  [line-type ^AudioFormat format]
  (assert (#{:target :source} line-type) "Invalid line type")
  (let [info (sampled/line-info line-type format)
        line (sampled/line info)]
    (when-not (line-supported? info)
      (throw (ex-info "Audio line not supported"
                      {:format format})))
    (sound/open! line format)
    (sound/start! line)
    line))

(defn audio-chunk-size
  "Calculates the size of an audio chunk in bytes based on audio parameters.

   Parameters:
   - sample-rate: The number of samples per second (Hz)
   - channels: Number of audio channels (1 for mono, 2 for stereo)
   - sample-size-bits: Bit depth of each sample (e.g., 16 for 16-bit audio)
   - duration-ms: Duration of the chunk in milliseconds

   Returns:
   Integer representing the chunk size in bytes.

   Example:
   (audio-chunk-size {:sample-rate 44100
                      :channels 2
                      :sample-size-bits 16
                      :duration-ms 20})
   ;; => 3528"
  [{:keys [sample-rate channels sample-size-bits duration-ms]}]
  (int (* sample-rate
          channels
          (/ sample-size-bits 8)        ; convert bits to bytes
          (/ duration-ms 1000))))
