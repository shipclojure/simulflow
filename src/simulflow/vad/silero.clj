(ns simulflow.vad.silero
  (:require
   [simulflow.utils.audio :as audio]
   [simulflow.vad.core :as vad]
   [taoensso.telemere :as t])
  (:import
   (ai.onnxruntime OnnxTensor OrtEnvironment OrtSession$SessionOptions)
   (java.util HashMap)))

;; Constants
(def ^:private model-reset-time-ms 5000)
(def ^:private supported-sample-rates #{8000 16000})

;; ONNX Model wrapper
(defn- create-session-options []
  (doto (OrtSession$SessionOptions.)
    (.setInterOpNumThreads 1)
    (.setIntraOpNumThreads 1)
    (.addCPU true)))

(defn- validate-input
  "Validate and preprocess input audio data"
  [x sr]
  ;; Handle 1D input by expanding dimensions
  (let [x (if (vector? x)
            (if (number? (first x)) ; 1D vector
              [x] ; Wrap in outer dimension
              x) ; Already 2D
            x)]
    ;; Check dimension constraint
    (when (> (count x) 2)
      (throw (IllegalArgumentException.
               (str "Incorrect audio data dimension: " (count (first x))))))
    ;; Handle sample rate conversion if needed
    (let [[x sr] (if (and (not= sr 16000) (zero? (mod sr 16000)))
                   (let [step (quot sr 16000)]
                     [(mapv (fn [current]
                              (vec (for [j (range 0 (count current) step)]
                                     (nth current j))))
                            x)
                      16000])
                   [x sr])]
      ;; Validate sample rate
      (when-not (contains? supported-sample-rates sr)
        (throw (IllegalArgumentException.
                 (str "Only supports sample rates " supported-sample-rates
                      " (or multiples of 16000)"))))
      ;; Check minimum audio length
      (when (> (/ (float sr) (count (first x))) 31.25)
        (throw (IllegalArgumentException. "Input audio is too short")))
      {:x x :sr sr})))

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

(defn- to-float-array-2d
  "Convert nested vectors to 2D float array"
  [x]
  (if (instance? (Class/forName "[[F") x)
    x
    (let [rows (count x)
          cols (count (first x))
          arr (make-array Float/TYPE rows cols)]
      (dotimes [i rows]
        (dotimes [j cols]
          (aset arr i j (float (nth (nth x i) j)))))
      arr)))

(defprotocol SileroOnnxModel
  (call-model [this x sr] "Call the model for voice activity")
  (reset-states! [this] [this batch-size] "Reset model states")
  (close-model! [this] "Close the model"))

(defn- create-silero-onnx-model
  "Create a new Silero ONNX model instance"
  [model-path]
  (try
    (let [env (OrtEnvironment/getEnvironment)
          opts (create-session-options)
          session (.createSession env model-path opts)
          state (atom {:state (make-array Float/TYPE 2 1 128)
                       :context (make-array Float/TYPE 0 0)
                       :last-sr 0
                       :last-batch-size 0})]
      (reify SileroOnnxModel
        (reset-states! [_]
          (reset-states! _ 1))
        (reset-states! [_ batch-size]
          (reset! state {:state (make-array Float/TYPE 2 batch-size 128)
                         :context (make-array Float/TYPE batch-size 0)
                         :last-sr 0
                         :last-batch-size 0}))
        (call-model [this x sr]
          (let [{:keys [x sr]} (validate-input x sr)
                number-samples (if (= sr 16000) 512 256)
                batch-size (count x)
                context-size (if (= sr 16000) 64 32)]
            ;; Validate exact sample count
            (when (not= (count (first x)) number-samples)
              (throw (IllegalArgumentException.
                       (str "Provided number of samples is " (count (first x))
                            " (Supported values: 256 for 8000 sample rate, 512 for 16000)"))))
            ;; Handle state resets
            (let [current-state @state
                  need-reset? (or (zero? (:last-batch-size current-state))
                                  (and (not= (:last-sr current-state) 0)
                                       (not= (:last-sr current-state) sr))
                                  (and (not= (:last-batch-size current-state) 0)
                                       (not= (:last-batch-size current-state) batch-size)))]
              (when need-reset?
                (reset-states! this batch-size))
              ;; Re-read state after potential reset
              (let [current-state @state
                    context (if (zero? (count (:context current-state)))
                              (make-array Float/TYPE batch-size context-size)
                              (:context current-state))
                    x-with-context (concatenate-arrays context (to-float-array-2d x))
                    env (OrtEnvironment/getEnvironment)]
                (with-open [input-tensor (OnnxTensor/createTensor env x-with-context)
                            state-tensor (OnnxTensor/createTensor env (:state current-state))
                            sr-tensor (OnnxTensor/createTensor env (long-array [sr]))]
                  (let [inputs (doto (HashMap.)
                                 (.put "input" input-tensor)
                                 (.put "state" state-tensor)
                                 (.put "sr" sr-tensor))]
                    (with-open [outputs (.run session inputs)]
                      (let [output (.getValue (.get outputs 0))
                            new-state (.getValue (.get outputs 1))
                            new-context (get-last-columns x-with-context context-size)]
                        (swap! state assoc
                               :state new-state
                               :context new-context
                               :last-sr sr
                               :last-batch-size batch-size)
                        ;; Return confidence for first batch item
                        (aget output 0 0)))))))))
        (close-model! [_]
          (try
            (.close session)
            (catch Exception e
              (t/log! {:level :warn
                       :id :silero-onnx
                       :error e} "Error closing Silero ONNX model"))))))
    (catch Exception e
      (t/log! {:level :error
               :error e} "Failed to create Silero ONNX model")
      (throw e))))

(comment

  (defn test-model []
    (let [model (create-silero-onnx-model "resources/silero_vad.onnx")
          ;; Create 512 samples of silence
          silence (float-array 512 0.0)
          ;; Create 512 samples of noise
          noise (float-array 512 (repeatedly 512 #(- (rand 2.0) 1.0)))]
      (println "Silence confidence:" (call-model model [silence] 16000))
      (println "Noise confidence:" (call-model model [noise] 16000))
      (close-model! model)))

  (test-model))

;; VAD Analyzer implementation with audio accumulation
(defn update-vad-state
  "Pure function to update VAD state based on speaking detection.

   Args:
     current-state: Current VAD state map with :vad/state, :vad/starting-count, :vad/stopping-count
     speaking?: Boolean indicating if speech is currently detected
     start-frames: Number of frames required to confirm speech start
     stop-frames: Number of frames required to confirm speech stop

   Returns:
     Updated VAD state map"
  [current-state speaking? start-frames stop-frames]
  (case (:vad/state current-state)
    :vad.state/quiet
    (if speaking?
      (assoc current-state :vad/state :vad.state/starting :vad/starting-count 1)
      current-state)

    :vad.state/starting
    (if speaking?
      (if (>= (:vad/starting-count current-state) start-frames)
        (assoc current-state :vad/state :vad.state/speaking :vad/starting-count 0)
        (update current-state :vad/starting-count inc))
      (assoc current-state :vad/state :vad.state/quiet :vad/starting-count 0))

    :vad.state/speaking
    (if (not speaking?)
      (assoc current-state :vad/state :vad.state/stopping :vad/stopping-count 1)
      current-state)

    :vad.state/stopping
    (if speaking?
      (assoc current-state :vad/state :vad.state/speaking :vad/stopping-count 0)
      (if (>= (:vad/stopping-count current-state) stop-frames)
        (assoc current-state :vad/state :vad.state/quiet :vad/stopping-count 0)
        (update current-state :vad/stopping-count inc)))))

(defn create-silero-vad
  "Create a Silero VAD analyzer instance with audio accumulation.
   
   Options:
   - :model-path - Path to the silero_vad.onnx model file
   - :sample-rate - Audio sample rate (8000 or 16000 Hz, default: 16000)
   - :vad/min-confidence - Threshold for voice activity detection (default: 0.7)
   - :vad/min-speech-duration-ms - Minimum speech duration in ms (default: 200)
   - :vad/min-silence-duration-ms - Minimum silence duration in ms (default: 800)"
  ([] (create-silero-vad {}))
  ([{:keys [model-path sample-rate]
     :vad/keys [min-confidence min-speech-duration-ms min-silence-duration-ms]
     :or {sample-rate 16000
          model-path "resources/silero_vad.onnx"
          min-speech-duration-ms (:vad/min-speech-duration-ms vad/default-params)
          min-silence-duration-ms (:vad/min-silence-duration-ms vad/default-params)
          min-confidence (:vad/min-confidence vad/default-params)}}]
   (when-not (contains? supported-sample-rates sample-rate)
     (throw (IllegalArgumentException.
              (str "Sampling rate not supported, only available for " supported-sample-rates))))
   (let [model (create-silero-onnx-model (or model-path "resources/silero_vad.onnx"))
         frames-required (if (= sample-rate 16000) 512 256)
         bytes-per-frame 2 ; 16-bit PCM
         bytes-required (* frames-required bytes-per-frame)
         ;; Audio buffer for accumulation
         audio-buffer (atom (byte-array 0))
         ;; VAD state tracking
         vad-state (atom {:vad/state :vad.state/quiet
                          :vad/starting-count 0
                          :vad/stopping-count 0})
         ;; Timing calculations
         frames-per-sec (/ (float frames-required) sample-rate)
         start-frames (Math/round (/ min-speech-duration-ms 1000.0 frames-per-sec))
         stop-frames (Math/round (/ min-silence-duration-ms 1000.0 frames-per-sec))
         ;; Reset timer
         last-reset-time (atom (System/currentTimeMillis))]
     (reify vad/VADAnalyzer
       (analyze-audio [this audio-chunk]
         (t/log! {:id :silero-vad
                  :msg "Analysing audio chunk"
                  :level :debug
                  :sample 0.01})
         (try
           ;; Accumulate audio
           (swap! audio-buffer #(byte-array (concat (seq %) (seq audio-chunk))))
           ;; Process if we have enough data
           (let [current-buffer @audio-buffer]
             (if (>= (count current-buffer) bytes-required)
               (let [;; Take exactly the required bytes
                     process-bytes (byte-array bytes-required
                                               (take bytes-required current-buffer))
                     ;; Keep the rest for next time
                     remaining (byte-array (drop bytes-required current-buffer))]
                 ;; Update buffer with remaining data
                 (reset! audio-buffer remaining)
                 ;; Get confidence and make speaking decision
                 (let [confidence (vad/voice-confidence this process-bytes)
                       speaking? (>= confidence min-confidence)]
                   ;; State machine logic
                   ;; State machine logic using pure function
                   (swap! vad-state update-vad-state speaking? start-frames stop-frames)
                   (:vad/state @vad-state)))
               ;; Not enough data yet, return current state
               (:vad/state @vad-state)))
           (catch Exception e
             (t/log! {:level :error
                      :error e} "Error analyzing audio with Silero VAD")
             :vad/quiet)))
       (voice-confidence [_ audio-buffer]
         (try
           (let [audio-float32 (audio/pcm-bytes->floats audio-buffer)
                 ;; Call model with properly formatted input
                 confidence (call-model model [audio-float32] sample-rate)
                 current-time (System/currentTimeMillis)
                 time-diff (- current-time @last-reset-time)]
             ;; Reset model periodically to prevent memory growth
             (when (>= time-diff model-reset-time-ms)
               (reset-states! model 1)
               (reset! last-reset-time current-time))
             confidence)
           (catch Exception e
             (t/log! {:level :error
                      :error e} "Error calculating voice confidence with Silero VAD")
             0.0)))
       (cleanup [_]
         (close-model! model))))))
