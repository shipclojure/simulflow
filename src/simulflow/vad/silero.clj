(ns simulflow.vad.silero
  (:require
   [simulflow.protocols :as p]
   [taoensso.telemere :as t])
  (:import
   (ai.onnxruntime OnnxTensor OrtEnvironment OrtSession$SessionOptions)
   (java.util HashMap)))

;; Constants
(def ^:private model-reset-time-ms 5000)
(def ^:private supported-sample-rates #{8000 16000})
(def ^:private threshold-gap 0.15)

;; ONNX Model wrapper
(defn- create-session-options []
  (doto (OrtSession$SessionOptions.)
    (.setInterOpNumThreads 1)
    (.setIntraOpNumThreads 1)
    (.addCPU true)))

(defn- validate-input
  "Validate and preprocess input audio data"
  [x sr]
  (let [x (if (= (count (first x)) 1) x [x])]
    (when (> (count x) 2)
      (throw (IllegalArgumentException. (str "Incorrect audio data dimension: " (count x)))))

    ;; Handle sample rate conversion if needed
    (let [x (if (and (not= sr 16000) (zero? (mod sr 16000)))
              (let [step (/ sr 16000)]
                (mapv (fn [current]
                        (let [indices (range 0 (count current) step)]
                          (mapv #(nth current %) indices)))
                      x))
              x)
          sr (if (and (not= sr 16000) (zero? (mod sr 16000))) 16000 sr)]

      (when-not (contains? supported-sample-rates sr)
        (throw (IllegalArgumentException.
                 (str "Only supports sample rates " supported-sample-rates " (or multiples of 16000)"))))

      (when (> (/ (float sr) (count (first x))) 31.25)
        (throw (IllegalArgumentException. "Input audio is too short")))

      [x sr])))

(defn- reset-states!
  "Reset the internal model states"
  [model batch-size]
  (-> model
      (assoc :state (make-array Float/TYPE 2 batch-size 128))
      (assoc :context (make-array Float/TYPE batch-size 0))
      (assoc :last-sr 0)
      (assoc :last-batch-size 0)))

(defn- concatenate-arrays
  "Concatenate two 2D float arrays along the column axis"
  [a b]
  (let [rows (count a)
        cols-a (count (first a))
        cols-b (count (first b))
        result (make-array Float/TYPE rows (+ cols-a cols-b))]
    (dotimes [i rows]
      (System/arraycopy (nth a i) 0 (nth result i) 0 cols-a)
      (System/arraycopy (nth b i) 0 (nth result i) cols-a cols-b))
    result))

(defn- get-last-columns
  "Get the last N columns from a 2D array"
  [array context-size]
  (let [rows (count array)
        cols (count (first array))
        result (make-array Float/TYPE rows context-size)]
    (when (> context-size cols)
      (throw (IllegalArgumentException. "contextSize cannot be greater than the number of columns")))
    (dotimes [i rows]
      (System/arraycopy (nth array i) (- cols context-size) (nth result i) 0 context-size))
    result))

(defprotocol SileroOnnxModel
  (call-model [this x sr] "Call the model for voice activity")
  (close-model! [this] "Close the model"))

(defn reset-model-state
  [state batch-size]
  (-> state
    (assoc :state (make-array Float/TYPE 2 batch-size 128))
    (assoc :context (make-array Float/TYPE batch-size 0))
    (assoc :last-sr 0)
    (assoc :last-batch-size 0)))

(defn- create-silero-onnx-model
  "Create a new Silero ONNX model instance"
  [model-path]
  (try
    (let [env (OrtEnvironment/getEnvironment)
          opts (create-session-options)
          session (.createSession env model-path opts)
          initial-state {:session session
                         :state (make-array Float/TYPE 2 1 128)
                         :context (make-array Float/TYPE 0 0)
                         :last-sr 0
                         :last-batch-size 0}
          state (atom initial-state)
          reset-states! #(swap! state reset-model-state %)]
      (reify SileroOnnxModel
        (call-model [this x sr]
          (let [[x sr] (validate-input x sr)
                number-samples (if (= sr 16000) 512 256)
                batch-size (count x)
                context-size (if (= sr 16000) 64 32)]

            (when (not= (count (first x)) number-samples)
              (throw (IllegalArgumentException.
                       (str "Provided number of samples is " (count (first x))
                            " (Supported values: 256 for 8000 sample rate, 512 for 16000)"))))

            ;; Handle state resets
            (let [model-state (cond
                                (zero? (:last-batch-size @state)) (reset-states! batch-size)
                                (and (not= (:last-sr @state) 0) (not= (:last-sr this) sr)) (reset-states! batch-size)
                                (and (not= (:last-batch-size @state) 0) (not= (:last-batch-size this) batch-size)) (reset-states! batch-size)
                                :else @state)

                  context (if (zero? (count (:context model-state)))
                            (make-array Float/TYPE batch-size context-size)
                            (:context model-state))

                  x (concatenate-arrays context x)
                  env (OrtEnvironment/getEnvironment)]

              (with-open [input-tensor (OnnxTensor/createTensor env x)
                          state-tensor (OnnxTensor/createTensor env (:state model-state))
                          sr-tensor (OnnxTensor/createTensor env (long-array [sr]))]

                (let [inputs (doto (HashMap.)
                               (.put "input" input-tensor)
                               (.put "state" state-tensor)
                               (.put "sr" sr-tensor))]

                  (with-open [outputs (.run (:session model-state) inputs)]
                    (let [output (.getValue (.get outputs 0))
                          new-state (.getValue (.get outputs 1))
                          new-context (get-last-columns x context-size)
                          updated-model (swap! state assoc
                                               :state new-state
                                               :context new-context
                                               :last-sr sr
                                               :last-batch-size batch-size)]
                      [(first (first output)) updated-model])))))))

        (close-model! [_]
          (try
            (when-let [s (:session @state)]
              (.close s))
            (catch Exception e
              (t/log! {:level :warn
                       :id :silero-onnx
                       :error e} "Error closing Silero ONNX model"))))))
    (catch Exception e
      (t/log! {:level :error
               :error e} "Failed to create Silero ONNX model")
      (throw e))))

;; VAD Analyzer implementation
(defn create-silero-vad
  "Create a Silero VAD analyzer instance.
   
   Options:
   - :model-path - Path to the silero_vad.onnx model file
   - :sample-rate - Audio sample rate (8000 or 16000 Hz, default: 16000)
   - :threshold - Threshold for voice activity detection (default: 0.5)
   - :min-speech-duration-ms - Minimum speech duration in ms (default: 250)
   - :max-speech-duration-seconds - Maximum speech duration in seconds (default: infinity)
   - :min-silence-duration-ms - Minimum silence duration in ms (default: 100)
   - :speech-pad-ms - Speech padding in ms (default: 30)"

  ([] (create-silero-vad {}))

  ([{:keys [model-path sample-rate threshold min-speech-duration-ms
            max-speech-duration-seconds min-silence-duration-ms speech-pad-ms]
     :or {sample-rate 16000
          threshold 0.5
          min-speech-duration-ms 250
          max-speech-duration-seconds Float/POSITIVE_INFINITY
          min-silence-duration-ms 100
          speech-pad-ms 30}}]

   (when-not (contains? supported-sample-rates sample-rate)
     (throw (IllegalArgumentException.
              (str "Sampling rate not supported, only available for " supported-sample-rates))))

   (let [model (create-silero-onnx-model model-path)
         neg-threshold (- threshold threshold-gap)
         min-speech-samples (* sample-rate (/ min-speech-duration-ms 1000.0))
         speech-pad-samples (* sample-rate (/ speech-pad-ms 1000.0))
         max-speech-samples (- (* sample-rate max-speech-duration-seconds)
                               (if (= sample-rate 16000) 512 256)
                               (* 2 speech-pad-samples))
         min-silence-samples (* sample-rate (/ min-silence-duration-ms 1000.0))
         min-silence-samples-at-max-speech (* sample-rate (/ 98 1000.0))
         last-reset-time (atom (System/currentTimeMillis))]

     (reify p/VADAnalyzer
       (analyze-audio [this audio-buffer]
         (try
           (let [confidence (p/voice-confidence this audio-buffer)]
             ;; Simple state machine based on confidence
             (cond
               (>= confidence threshold) :vad/speaking
               (<= confidence neg-threshold) :vad/quiet
               :else :vad/starting)) ; intermediate state
           (catch Exception e
             (t/log! {:level :error
                      :error e} "Error analyzing audio with Silero VAD")
             :vad/quiet)))

       (voice-confidence [_this audio-buffer]
         (try
           ;; Convert byte array to float array (assuming 16-bit PCM)
           (let [audio-int16 (let [shorts (short-array (/ (count audio-buffer) 2))]
                               (dotimes [i (count shorts)]
                                 (let [low-byte (bit-and (nth audio-buffer (* i 2)) 0xff)
                                       high-byte (nth audio-buffer (inc (* i 2)))]
                                   (aset shorts i (short (bit-or low-byte (bit-shift-left high-byte 8))))))
                               shorts)
                 audio-float32 (mapv #(/ (float %) 32768.0) audio-int16)
                 [confidence updated-model] (call-model model [audio-float32] sample-rate)]

             ;; Reset model periodically to prevent memory growth
             (let [current-time (System/currentTimeMillis)
                   time-diff (- current-time @last-reset-time)]
               (when (>= time-diff model-reset-time-ms)
                 (reset-states! updated-model 1)))

             confidence)
           (catch Exception e
             (t/log! {:level :error
                      :error e} "Error calculating voice confidence with Silero VAD")
             0.0)))))))
