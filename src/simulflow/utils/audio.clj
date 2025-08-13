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
    (cond
      ;; Special case: µ-law to PCM conversion must go directly to 16-bit
      ;; because Java Audio System doesn't support µ-law to 8-bit PCM
      (and (= (:encoding source) :ulaw)
           (= (:encoding target) :pcm-signed))
      (let [intermediate (assoc source
                                :encoding :pcm-signed
                                :sample-size-bits 16)
            remaining-steps (if (= intermediate target)
                              []
                              (create-encoding-steps intermediate target))]
        (if (empty? remaining-steps)
          [intermediate]
          (cons intermediate remaining-steps)))

      ;; Special case: PCM to µ-law conversion requires downsampling first
      ;; Must downsample to 8kHz before converting to µ-law
      (and (= (:encoding source) :pcm-signed)
           (= (:encoding target) :ulaw)
           (not= (:sample-rate source) (:sample-rate target)))
      (let [;; First downsample while keeping PCM format
            downsampled (assoc source :sample-rate (:sample-rate target))
            ;; Then convert encoding to µ-law
            final-step (assoc downsampled :encoding :ulaw :sample-size-bits 8)
            steps [downsampled]]
        (if (= final-step target)
          (conj steps final-step)
          (concat steps (create-encoding-steps final-step target))))

      ;; Normal transformation order for other cases
      :else
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
          buffer-size (or (:buffer-size target-config) 2048)
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

(defn ulaw8k->pcm16k
  [audio-data]
  (resample-audio-data
    audio-data
    {:sample-rate 8000 :encoding :ulaw :channels 1 :sample-size-bits 8}
    {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}))

(defn pcm->ulaw8k
  "Convert from source signed PCM at source sample rate to ulaw 8k"
  [audio-data source-sample-rate]
  (resample-audio-data
    audio-data
    {:sample-rate source-sample-rate :encoding :pcm-signed :channels 1 :sample-size-bits 16}
    {:sample-rate 8000 :encoding :ulaw :channels 1 :sample-size-bits 8}))

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

(defn pcm-bytes->floats
  "Convert byte array to float array (assuming 16-bit PCM little-endian)"
  [^bytes audio-buffer]
  (let [num-samples (/ (count audio-buffer) 2)
        audio-float32 (float-array num-samples)]
    (dotimes [i num-samples]
      (let [low-byte (bit-and (aget audio-buffer (* i 2)) 0xff)
            high-byte (aget audio-buffer (inc (* i 2)))]
        ;; Combine bytes (little-endian) and normalize to [-1, 1]
        (aset audio-float32 i
              (/ (float (short (bit-or low-byte (bit-shift-left high-byte 8))))
                 32768.0))))
    audio-float32))

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
    (dotimes [i num-samples]
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

(defn calculate-volume
  "Calculate volume level (RMS) for 16-bit PCM audio buffer.
   Returns a value between 0.0 and 1.0."
  [^bytes audio-buffer]
  (if (or (nil? audio-buffer) (zero? (count audio-buffer)))
    0.0
    (let [;; Convert bytes to 16-bit signed integers more efficiently
          num-samples (/ (count audio-buffer) 2)
          sum-squares (loop [i 0
                             sum 0.0]
                        (if (< i (- (count audio-buffer) 1))
                          (let [byte1 (aget audio-buffer i)
                                byte2 (aget audio-buffer (inc i))
                                ;; Combine bytes into 16-bit signed integer (little-endian)
                                sample (unchecked-short (bit-or (bit-and byte1 0xff)
                                                                (bit-shift-left byte2 8)))]
                            (recur (+ i 2) (+ sum (* sample sample))))
                          sum))
          ;; Calculate RMS (Root Mean Square)
          rms (Math/sqrt (/ sum-squares num-samples))
          ;; Normalize to 0.0-1.0 range (32767 is max value for 16-bit signed)
          normalized-volume (/ rms 32767.0)]
      (min 1.0 normalized-volume))))

(defn normalize-value
  "Normalize a value to the range [0, 1] and clamp it to bounds.

   Args:
     value: The value to normalize
     min-value: The minimum value of the input range
     max-value: The maximum value of the input range

   Returns:
     Normalized value clamped to the range [0, 1]"
  [value min-value max-value]
  (let [normalized (/ (- value min-value) (- max-value min-value))
        clamped (max 0.0 (min 1.0 normalized))]
    clamped))

(defn exp-smoothing
  "Apply exponential smoothing to a value.

   Exponential smoothing is used to reduce noise in time-series data by
   giving more weight to recent values while still considering historical data.

   Args:
     value: The new value to incorporate
     prev-value: The previous smoothed value
     factor: Smoothing factor between 0 and 1. Higher values give more
             weight to the new value

   Returns:
     The exponentially smoothed value"
  [value prev-value factor]
  (+ prev-value (* factor (- value prev-value))))

;; Normal speech usually results in many samples between ±500 to ±5000, depending on loudness and mic gain.
;; So we are using a threshold that is well below what real speech produces.
(def ^:private speaking-threshold 20)

(defn silence?
  "Determine if an audio sample contains silence by checking amplitude levels.

   This function analyzes raw PCM audio data to detect silence by comparing
   the maximum absolute amplitude against a predefined threshold. The audio
   is expected to be clean speech or complete silence without background noise.

   Args:
     pcm-bytes: Raw PCM audio data as bytes (16-bit signed integers)

   Returns:
     true if the audio sample is considered silence (below threshold),
     false otherwise

   Note:
     Normal speech typically produces amplitude values between ±500 to ±5000,
     depending on factors like loudness and microphone gain. The threshold
     is set well below typical speech levels to reliably detect silence vs. speech."
  [^bytes pcm-bytes]
  (if (or (nil? pcm-bytes) (zero? (count pcm-bytes)))
    true
    (let [max-value (loop [i 0
                           max-val 0]
                      (if (< i (- (count pcm-bytes) 1))
                        (let [byte1 (aget pcm-bytes i)
                              byte2 (aget pcm-bytes (inc i))
                              sample (Math/abs (int (unchecked-short
                                                      (bit-or (bit-and byte1 0xff)
                                                              (bit-shift-left byte2 8)))))]
                          (recur (+ i 2) (max max-val sample)))
                        max-val))]
      (<= max-value speaking-threshold))))

(defn mix-audio
  "Mix two audio streams together by adding their samples.

   Both audio streams are assumed to be 16-bit signed integer PCM data.
   If the streams have different lengths, the shorter one is zero-padded
   to match the longer stream.

   Args:
     audio1: First audio stream as raw bytes (16-bit signed integers)
     audio2: Second audio stream as raw bytes (16-bit signed integers)

   Returns:
     Mixed audio data as raw bytes with samples clipped to 16-bit range"
  [^bytes audio1 ^bytes audio2]
  (let [len1 (count audio1)
        len2 (count audio2)
        max-len (max len1 len2)
        ;; Ensure even number of bytes (complete 16-bit samples)
        max-len (if (odd? max-len) (inc max-len) max-len)
        result (byte-array max-len)]

    (loop [i 0]
      (when (< i (- max-len 1))
        (let [;; Get samples from both streams (with zero padding)
              sample1 (if (< i len1)
                        (let [b1 (aget audio1 i)
                              b2 (if (< (inc i) len1) (aget audio1 (inc i)) 0)]
                          (unchecked-short (bit-or (bit-and b1 0xff)
                                                   (bit-shift-left b2 8))))
                        0)
              sample2 (if (< i len2)
                        (let [b1 (aget audio2 i)
                              b2 (if (< (inc i) len2) (aget audio2 (inc i)) 0)]
                          (unchecked-short (bit-or (bit-and b1 0xff)
                                                   (bit-shift-left b2 8))))
                        0)
              ;; Mix samples and clamp to 16-bit range
              mixed (+ (int sample1) (int sample2))
              clamped (max -32768 (min 32767 mixed))
              ;; Convert back to bytes (little-endian)
              low-byte (unchecked-byte (bit-and clamped 0xff))
              high-byte (unchecked-byte (bit-shift-right clamped 8))]
          (aset result i low-byte)
          (aset result (inc i) high-byte)
          (recur (+ i 2)))))
    result))
