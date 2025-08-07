(ns simulflow.utils.audio
  (:require
   [taoensso.telemere :as t]
   [uncomplicate.clojure-sound.core :as sound]
   [uncomplicate.clojure-sound.sampled :as sampled])
  (:import
   (java.io ByteArrayInputStream ByteArrayOutputStream)
   (java.nio ByteBuffer ByteOrder)
   (javax.sound.sampled AudioFormat AudioSystem DataLine$Info)))

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
          (/ sample-size-bits 8) ; convert bits to bytes
          (/ duration-ms 1000))))

(defn create-audio-format
  "Create a javax.sound.sampled.AudioFormat from configuration"
  [{:keys [sample-rate encoding channels sample-size-bits endian buffer-size]
    :or {sample-rate 16000 encoding :pcm-signed channels 1 sample-size-bits 16 endian :little-endian buffer-size 1024}}]
  (sampled/audio-format encoding
                        sample-rate
                        sample-size-bits
                        channels
                        (int (* channels (/ sample-size-bits 8)))
                        (float sample-rate)
                        endian))

(defn create-encoding-steps
  "Prepares the plan to do audio conversion step by step. Returns an array of
  audio configs that is the gradual difference between source and target, target
  being the last in the array.

  Example:
  (create-encoding-steps
   {:sample-rate 8000 :encoding :ulaw :channels 1 :sample-size-bits 8}
   {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16})
  =>
  [{:sample-rate 8000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
   {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}]
  "
  [source target]
  (if (= source target)
    []
    ;; Special case: µ-law to PCM conversion must go directly to 16-bit
    ;; because Java Audio System doesn't support µ-law to 8-bit PCM
    (if (and (= (:encoding source) :ulaw)
             (= (:encoding target) :pcm-signed))
      ;; For µ-law -> PCM conversion, force bit depth to 16 in first step
      (let [intermediate (assoc source
                                :encoding :pcm-signed
                                :sample-size-bits 16)
            remaining-steps (if (= intermediate target)
                              []
                              (create-encoding-steps intermediate target))]
        (if (empty? remaining-steps)
          [intermediate]
          (cons intermediate remaining-steps)))

      ;; Normal transformation order for other cases
      (let [transformation-order [:encoding :sample-rate :sample-size-bits :channels :endian]
            steps (reduce
                    (fn [acc property]
                      (let [current-config (or (last acc) source)
                            target-value (get target property)
                            current-value (get current-config property)]
                        (if (= current-value target-value)
                          acc
                          (conj acc (assoc current-config property target-value)))))
                    []
                    transformation-order)]
        steps))))

(defn convert-with-steps
  "Given the conversion steps, create an AudioInputStream from source to each
  encoding step until reaching the final conversion step. Returns the
  AudioInputStream resulted from the last step

  source - AudioInputStream for source data
  steps - array of audio encoding configs through which to take the conversion"
  [source steps]
  (loop [steps steps
         src source]
    (if (empty? steps)
      src
      (let [format (create-audio-format (first steps))
            next (sampled/audio-input-stream format src)]
        (recur (rest steps) next)))))

(defn bytes->audio-input-stream
  [audio-bytes format]
  (let [;; Create input stream from source audio
        byte-input-stream (ByteArrayInputStream. audio-bytes)]
    (sampled/audio-input byte-input-stream format (alength audio-bytes))))

(defn resample-audio-data
  "Resample audio data from source format to target format using javax.sound.

   Args:
   - audio-data: byte array of source audio
   - source-config: map with source audio parameters
   - target-config: map with target audio parameters

   Returns:
   - byte array of resampled audio"
  [audio-data source-config target-config]
  (try
    (let [source-audio-stream (bytes->audio-input-stream
                                audio-data
                                (create-audio-format source-config))

          conversion-steps (create-encoding-steps source-config target-config)

          ;; Get resampled audio stream
          target-audio-stream (convert-with-steps source-audio-stream conversion-steps)

          ;; Read all bytes from resampled stream
          output-stream (ByteArrayOutputStream.)
          buffer-size (or (:buffer-size target-config) 1024)
          buffer (byte-array buffer-size)]

      (loop []
        (let [bytes-read (.read target-audio-stream buffer)]
          (when (pos? bytes-read)
            (.write output-stream buffer 0 bytes-read)
            (recur))))

      (.close target-audio-stream)
      (.close source-audio-stream)
      (.toByteArray output-stream))

    (catch Exception e
      (t/log! {:level :error :id :audio-resampler :error e}
              "Failed to resample audio data")
      audio-data)))

(defn pcms16->pcmf32
  "Convert a byte array of PCM signed shorts,
  return a byte array of pcm signed 32 bit floats.
  "
  [^bytes bs]
  (let [inbuf (doto (ByteBuffer/wrap bs)
                (.order (ByteOrder/nativeOrder)))
        buf (doto (ByteBuffer/allocate (* 2 (alength bs)))
              (.order (ByteOrder/nativeOrder)))
        fbuf (.asFloatBuffer buf)]
    (doseq [i (range (quot (alength bs) 2))]
      (.put fbuf
            (float (/ (.getShort inbuf)
                      Short/MAX_VALUE))))
    (.array buf)))

(defn bytes->floats [^bytes bs]
  (let [buf (doto (ByteBuffer/wrap bs)
              (.order (ByteOrder/nativeOrder)))
        fbuf (.asFloatBuffer buf)
        flts (float-array (quot (alength bs)
                                4))]
    (.get fbuf flts)
    flts))

(defn downsample
  "Convert the sound samples, `bs` from `from-sample-rate` to `to-sample-rate`.

  The sound samples must be pcmf32."
  [^bytes bs from-sample-rate to-sample-rate]
  (assert (< to-sample-rate from-sample-rate))
  (let [num-samples (dec
                      (long
                        (* (quot (alength bs) 4)
                           (/ to-sample-rate
                              from-sample-rate))))
        inbuf (doto (ByteBuffer/wrap bs)
                (.order (ByteOrder/nativeOrder)))
        outbuf (doto (ByteBuffer/allocate (* 4 num-samples))
                 (.order (ByteOrder/nativeOrder)))]
    (dotimes [i  num-samples]
      (let [index (double (* i (/ from-sample-rate to-sample-rate)))
            i0 (long index)
            i1 (inc i0)
            f (- index (Math/floor index))]
        #_(.putFloat outbuf (* 4 i) (.getFloat inbuf (* 4 i0)))
        (.putFloat outbuf (* 4 i)
                   (+ (.getFloat inbuf (* 4 i0))
                      (* f (- (.getFloat inbuf (* 4 i1))
                              (.getFloat inbuf (* 4 i0))))))))
    (.array outbuf)))
