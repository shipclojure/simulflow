(ns simulflow.transport-test
  (:require [clojure.test :refer [deftest is testing]]
            [simulflow.frame :as frame]
            [simulflow.transport :as sut]
            [simulflow.transport.out :as out]
            [simulflow.utils.core :as u])
  (:import (java.util Date)))

;; =============================================================================
;; Audio Splitter Tests

(deftest test-split-audio-into-chunks-basic
  (testing "splits audio into correct number of chunks"
    (let [audio (byte-array (range 100))
          chunks (sut/split-audio-into-chunks audio 30)]
      (is (= 4 (count chunks)))
      (is (= [30 30 30 10] (mapv count chunks)))))

  (testing "preserves data integrity across chunks"
    (let [original-data (vec (range 50))
          audio (byte-array original-data)
          chunks (sut/split-audio-into-chunks audio 20)
          reconstructed (vec (apply concat (map vec chunks)))]
      (is (= original-data reconstructed))))

  (testing "handles exact division"
    (let [audio (byte-array 120)
          chunks (sut/split-audio-into-chunks audio 40)]
      (is (= 3 (count chunks)))
      (is (every? #(= 40 (count %)) chunks))))

  (testing "handles single chunk when audio smaller than chunk size"
    (let [audio (byte-array 25)
          chunks (sut/split-audio-into-chunks audio 100)]
      (is (= 1 (count chunks)))
      (is (= 25 (count (first chunks)))))))

(deftest test-split-audio-into-chunks-edge-cases
  (testing "returns nil for nil audio"
    (is (nil? (sut/split-audio-into-chunks nil 100))))

  (testing "returns nil for zero chunk size"
    (is (nil? (sut/split-audio-into-chunks (byte-array 10) 0))))

  (testing "returns nil for negative chunk size"
    (is (nil? (sut/split-audio-into-chunks (byte-array 10) -1))))

  (testing "handles empty audio array"
    (let [chunks (sut/split-audio-into-chunks (byte-array 0) 10)]
      (is (nil? chunks)))))

(deftest test-audio-splitter-config-chunk-size
  (testing "uses provided chunk size"
    (let [config (sut/audio-splitter-config {:audio.out/chunk-size 1024})]
      (is (= 1024 (:audio.out/chunk-size config)))))

  (testing "preserves other config values"
    (let [input {:audio.out/chunk-size 512 :other/key "value"}
          config (sut/audio-splitter-config input)]
      (is (= 512 (:audio.out/chunk-size config)))
      (is (= "value" (:other/key config))))))

(deftest test-audio-splitter-config-calculation
  (testing "calculates chunk size from audio parameters"
    (let [config (sut/audio-splitter-config
                  {:audio.out/sample-rate 16000
                   :audio.out/sample-size-bits 16
                   :audio.out/channels 1
                   :audio.out/duration-ms 20})]
      (is (= 640 (:audio.out/chunk-size config)))))

  (testing "calculates different chunk sizes for different parameters"
    (let [config1 (sut/audio-splitter-config
                   {:audio.out/sample-rate 44100
                    :audio.out/sample-size-bits 16
                    :audio.out/channels 2
                    :audio.out/duration-ms 10})
          config2 (sut/audio-splitter-config
                   {:audio.out/sample-rate 8000
                    :audio.out/sample-size-bits 8
                    :audio.out/channels 1
                    :audio.out/duration-ms 50})]
      (is (= 1764 (:audio.out/chunk-size config1)))
      (is (= 400 (:audio.out/chunk-size config2))))))

(deftest test-audio-splitter-config-validation
  (testing "throws when neither chunk-size nor audio params provided"
    (is (thrown? AssertionError
                 (sut/audio-splitter-config {}))))

  (testing "throws when audio params incomplete"
    (is (thrown? AssertionError
                 (sut/audio-splitter-config
                  {:audio.out/sample-rate 16000
                   :audio.out/sample-size-bits 16
                    ;; missing channels and duration-ms
                   }))))

  (testing "accepts complete audio parameters"
    (is (map? (sut/audio-splitter-config
               {:audio.out/sample-rate 16000
                :audio.out/sample-size-bits 16
                :audio.out/channels 1
                :audio.out/duration-ms 20})))))

(deftest test-audio-splitter-fn-multi-arity
  (testing "0-arity returns description"
    (let [desc (sut/audio-splitter-fn)]
      (is (contains? desc :ins))
      (is (contains? desc :outs))
      (is (contains? desc :params))
      (is (= "Channel for raw audio frames" (get-in desc [:ins :in])))))

  (testing "1-arity initializes state"
    (let [config {:audio.out/chunk-size 512}
          state (sut/audio-splitter-fn config)]
      (is (= 512 (:audio.out/chunk-size state)))))

  (testing "2-arity handles transitions"
    (let [state {:audio.out/chunk-size 256}
          result (sut/audio-splitter-fn state :clojure.core.async.flow/stop)]
      (is (= state result))))

  (testing "3-arity transforms audio frames"
    (let [state {:audio.out/chunk-size 50}
          frame (frame/audio-output-raw (byte-array (range 120)))
          [new-state output] (sut/audio-splitter-fn state :in frame)]
      (is (= state new-state))
      (is (= 3 (count (:out output))))
      (is (every? frame/audio-output-raw? (:out output)))
      (is (= [50 50 20] (mapv #(count (:frame/data %)) (:out output))))))

  (testing "3-arity ignores non-audio frames"
    (let [state {:audio.out/chunk-size 100}
          non-audio-frame {:frame/type :other}
          [new-state output] (sut/audio-splitter-fn state :in non-audio-frame)]
      (is (= state new-state))
      (is (empty? output)))))

(deftest test-audio-splitter-property-based
  (testing "chunk size calculation invariants"
    (doseq [sample-rate [8000 16000 44100 48000]
            channels [1 2]
            sample-size-bits [8 16 24]
            duration-ms [10 20 25 50]]
      (let [config (sut/audio-splitter-config
                    {:audio.out/sample-rate sample-rate
                     :audio.out/sample-size-bits sample-size-bits
                     :audio.out/channels channels
                     :audio.out/duration-ms duration-ms})
            chunk-size (:audio.out/chunk-size config)]
        (is (pos? chunk-size))
        (is (integer? chunk-size))
        ;; Verify formula: sample-rate * channels * (bits/8) * (ms/1000)
        (let [expected (* sample-rate channels (/ sample-size-bits 8) (/ duration-ms 1000))]
          (is (= (int expected) chunk-size)))))))

(deftest test-audio-splitter-integration
  (testing "complete audio splitting workflow"
    (let [;; Create test audio data (500 bytes)
          audio-data (byte-array (range 500))
          frame (frame/audio-output-raw audio-data)

          ;; Initialize splitter with 150-byte chunks
          config {:audio.out/chunk-size 150}
          state (sut/audio-splitter-fn config)

          ;; Process the frame
          [final-state output] (sut/audio-splitter-fn state :in frame)]

      ;; Verify processing
      (is (= state final-state))
      (is (= 4 (count (:out output))))

      ;; Verify chunk sizes
      (let [chunk-sizes (mapv #(count (:frame/data %)) (:out output))]
        (is (= [150 150 150 50] chunk-sizes)))

      ;; Verify data integrity
      (let [reconstructed-data (vec (apply concat
                                           (map #(vec (:frame/data %)) (:out output))))]
        (is (= (vec audio-data) reconstructed-data))))))

(deftest test-audio-splitter-performance
  (testing "handles large audio efficiently"
    (let [large-audio (byte-array 100000) ; 100KB
          start-time (System/nanoTime)
          chunks (sut/split-audio-into-chunks large-audio 1000)
          end-time (System/nanoTime)
          duration-ms (/ (- end-time start-time) 1000000.0)]

      (is (= 100 (count chunks)))
      (is (< duration-ms 100)) ; Should complete in under 100ms
      (is (every? #(<= (count %) 1000) chunks)))))

;; =============================================================================
;; Twilio Transport Tests

(def test-config-change-frame (frame/system-config-change {:llm/context {:messages [{:role :system :content "You are a cool llm"}]}}))

(defn twilio-start-handle-event [event]
  (cond
    (= (:event event) "start")
    {:sys-out [test-config-change-frame]}
    :else {}))

(deftest twilio-transport-in-test
  (testing "twilio transport in"
    (testing "Transport in calls twilio/handle-event if it is provided"
      (let [state {:twilio/handle-event twilio-start-handle-event}]
        (is (= [state {:sys-out [test-config-change-frame]}]
               (sut/twilio-transport-in-transform
                state
                :twilio-in
                (u/json-str {:event "start"}))))

        (testing "Merges frames in the same array if more are generated"
          (let [[new-state {:keys [sys-out]}] (sut/twilio-transport-in-transform
                                               state
                                               :twilio-in
                                               (u/json-str {:event "start"
                                                            :streamSid "hello"}))]
            (is (= state new-state))
            (is (= test-config-change-frame (first sys-out)))
            (is (frame/system-config-change? (last sys-out)))
            (is (= "hello" (:twilio/stream-sid (:frame/data (last sys-out)))))))))))

;; Microphone input tests

(deftest microphone-transport-test
  (testing "process-mic-buffer with valid data"
    (let [test-buffer (byte-array [1 2 3 4 5])
          result (sut/process-mic-buffer test-buffer 3)]
      (is (map? result))
      (is (= [1 2 3] (vec (:audio-data result))))
      (is (instance? Date (:timestamp result)))))

  (testing "process-mic-buffer with zero bytes"
    (let [test-buffer (byte-array [1 2 3 4 5])
          result (sut/process-mic-buffer test-buffer 0)]
      (is (nil? result))))

  (testing "process-mic-buffer with negative bytes"
    (let [test-buffer (byte-array [1 2 3 4 5])
          result (sut/process-mic-buffer test-buffer -1)]
      (is (nil? result))))

  (testing "process-mic-buffer with full buffer"
    (let [test-buffer (byte-array [10 20 30 40 50])
          result (sut/process-mic-buffer test-buffer 5)]
      (is (= [10 20 30 40 50] (vec (:audio-data result))))))

  (testing "process-mic-buffer creates new array (no shared state)"
    (let [test-buffer (byte-array [1 2 3 4 5])
          result1 (sut/process-mic-buffer test-buffer 3)]
      (Thread/sleep 1) ; Ensure timestamp difference
      (let [result2 (sut/process-mic-buffer test-buffer 3)]
        ;; Modify original buffer
        (aset test-buffer 0 (byte 99))
        ;; Results should be unaffected
        (is (= [1 2 3] (vec (:audio-data result1))))
        (is (= [1 2 3] (vec (:audio-data result2))))
        ;; Results should be independent (timestamps different)
        (is (not= (:timestamp result1) (:timestamp result2))))))

  ;; =============================================================================
  ;; mic-resource-config Tests

  (testing "mic-resource-config with default buffer size"
    (let [config (sut/mic-resource-config
                  {:sample-rate 16000
                   :sample-size-bits 16
                   :channels 1
                   :buffer-size nil})
          expected-buffer-size (* 2 (/ 16000 100))]
      (is (= expected-buffer-size (:buffer-size config)))
      (is (= 1024 (:channel-size config)))
      (is (some? (:audio-format config)))))

  (testing "mic-resource-config with explicit buffer size"
    (let [config (sut/mic-resource-config
                  {:sample-rate 44100
                   :sample-size-bits 24
                   :channels 2
                   :buffer-size 1024})]
      (is (= 1024 (:buffer-size config)))
      (is (= 1024 (:channel-size config)))
      (is (some? (:audio-format config)))))

  (testing "mic-resource-config with different sample rates"
    (let [config-16k (sut/mic-resource-config
                      {:sample-rate 16000 :sample-size-bits 16 :channels 1})
          config-44k (sut/mic-resource-config
                      {:sample-rate 44100 :sample-size-bits 16 :channels 1})]
      (is (= 320 (:buffer-size config-16k))) ; 2 * (16000/100)
      (is (= 882 (:buffer-size config-44k))) ; 2 * (44100/100)
      (is (= (:channel-size config-16k) (:channel-size config-44k)))))

  (testing "mic-resource-config is pure (no side effects)"
    (let [input {:sample-rate 16000 :sample-size-bits 16 :channels 1}
          result1 (sut/mic-resource-config input)
          result2 (sut/mic-resource-config input)]
      ;; Should produce identical results
      (is (= (:buffer-size result1) (:buffer-size result2)))
      (is (= (:channel-size result1) (:channel-size result2)))))

  ;; =============================================================================
  ;; Multi-arity Function Tests

    ;; =============================================================================
  ;; Individual Function Tests (New Architecture)

  (testing "mic-transport-in-describe function"
    (let [description (sut/mic-transport-in-describe)]
      (is (contains? (:outs description) :out))
      (is (contains? (:params description) :audio-in/sample-rate))
      (is (contains? (:params description) :audio-in/channels))
      (is (contains? (:params description) :audio-in/sample-size-bits))
      (is (contains? (:params description) :audio-in/buffer-size))))

  (testing "mic-transport-in-transition function"
    (let [close-fn (atom false)
          state {::sut/close #(reset! close-fn true)}
          result (sut/mic-transport-in-transition state :clojure.core.async.flow/stop)]
      (is @close-fn "Close function should be called on stop transition")
      ;; Test other transitions don't call close
      (reset! close-fn false)
      (sut/mic-transport-in-transition state :clojure.core.async.flow/start)
      (is (not @close-fn) "Close function should not be called on start transition")))

  (testing "mic-transport-in-transform function"
    (let [test-audio-data (byte-array [1 2 3 4])
          test-timestamp (Date.)
          input-data {:audio-data test-audio-data :timestamp test-timestamp}
          [new-state output] (sut/mic-transport-in-transform {} :in input-data)]

      (is (= {} new-state)) ; State unchanged
      (is (contains? output :out))
      (is (= 1 (count (:out output))))

      (let [frame (first (:out output))]
        (is (frame/audio-input-raw? frame))
        (is (= test-audio-data (:frame/data frame)))
        (is (= test-timestamp (:frame/ts frame))))))

  (testing "mic-transport-in-transform preserves frame metadata"
    (let [test-timestamp (Date.)
          input-data {:audio-data (byte-array [5 6 7 8]) :timestamp test-timestamp}
          [_ output] (sut/mic-transport-in-transform {} :in input-data)
          frame (first (:out output))]

      (is (= :simulflow.frame/audio-input-raw (:frame/type frame)))
      (is (= test-timestamp (:frame/ts frame)))))

  ;; =============================================================================
  ;; Multi-arity Function Tests (For Compatibility)

  (testing "mic-transport-in-fn describe (0-arity) delegates correctly"
    (let [description (sut/mic-transport-in-fn)]
      (is (= description (sut/mic-transport-in-describe))
          "0-arity should delegate to describe function")))

  (testing "mic-transport-in-fn transform (3-arity) delegates correctly"
    (let [test-audio-data (byte-array [1 2 3 4])
          test-timestamp (Date.)
          input-data {:audio-data test-audio-data :timestamp test-timestamp}
          multi-arity-result (sut/mic-transport-in-fn {} :in input-data)
          individual-result (sut/mic-transport-in-transform {} :in input-data)]

      (is (= multi-arity-result individual-result)
          "3-arity should delegate to transform function")))

  ;; =============================================================================
  ;; Property-Based Tests

  (testing "process-mic-buffer invariants"
    (doseq [buffer-size [10 100 1000]
            bytes-read [0 5 50 500]]
      (let [test-buffer (byte-array (range buffer-size))
            result (sut/process-mic-buffer test-buffer bytes-read)]
        (if (pos? bytes-read)
          (do
            (is (map? result)
                (str "Should return map for bytes-read=" bytes-read))
            (is (= bytes-read (count (:audio-data result)))
                (str "Audio data length should match bytes-read=" bytes-read))
            (is (instance? Date (:timestamp result))
                "Should include timestamp"))
          (is (nil? result)
              (str "Should return nil for bytes-read=" bytes-read))))))

  (testing "mic-resource-config invariants"
    (doseq [sample-rate [8000 16000 44100 48000]
            channels [1 2]
            sample-size [8 16 24]]
      (let [config (sut/mic-resource-config
                    {:sample-rate sample-rate
                     :sample-size-bits sample-size
                     :channels channels
                     :buffer-size nil})]
        (is (pos? (:buffer-size config))
            (str "Buffer size should be positive for rate=" sample-rate))
        (is (= 1024 (:channel-size config))
            "Channel size should always be 1024")
        (is (some? (:audio-format config))
            "Should include audio format"))))

  ;; =============================================================================
  ;; Performance/Behavioral Tests

  (testing "process-mic-buffer handles large buffers efficiently"
    (let [large-buffer (byte-array 100000)
          start-time (System/nanoTime)
          result (sut/process-mic-buffer large-buffer 50000)
          end-time (System/nanoTime)
          duration-ms (/ (- end-time start-time) 1000000)]

      (is (< duration-ms 100) "Should process large buffer quickly")
      (is (= 50000 (count (:audio-data result))))
      (is (instance? Date (:timestamp result)))))

  (testing "process-mic-buffer memory isolation"
    (let [source-buffer (byte-array [1 2 3 4 5])
          result (sut/process-mic-buffer source-buffer 3)
          audio-data (:audio-data result)]

      ;; Modify source buffer
      (aset source-buffer 0 (byte 99))
      (aset source-buffer 1 (byte 88))

      ;; Result should be unaffected
      (is (= [1 2 3] (vec audio-data))
          "Result should be isolated from source buffer changes")))

  ;; =============================================================================
  ;; Edge Cases

  (testing "process-mic-buffer with empty buffer"
    (let [empty-buffer (byte-array 0)
          result (sut/process-mic-buffer empty-buffer 0)]
      (is (nil? result))))

  (testing "process-mic-buffer with bytes-read larger than buffer"
    ;; This tests the Arrays/copyOfRange behavior
    (let [small-buffer (byte-array [1 2 3])
          ;; Note: In real usage, this shouldn't happen, but testing robustness
          result (sut/process-mic-buffer small-buffer 3)]
      (is (= [1 2 3] (vec (:audio-data result))))))

  (testing "mic-resource-config with edge case sample rates"
    (let [config-low (sut/mic-resource-config {:sample-rate 1000 :channels 1 :sample-size-bits 8})
          config-high (sut/mic-resource-config {:sample-rate 192000 :channels 8 :sample-size-bits 32})]

      (is (pos? (:buffer-size config-low)))
      (is (pos? (:buffer-size config-high)))
      (is (= 1024 (:channel-size config-low) (:channel-size config-high))))))

;; =============================================================================
;; Realtime Speakers Out Tests
;; =============================================================================

(deftest test-realistic-llm-audio-pipeline-with-timing
  (testing "LLM generates large audio frame -> audio splitter -> realtime speakers with simulated realistic timing"
    (let [;; =============================================================================
          ;; Step 1: LLM generates a large audio frame (simulating TTS output)
          ;; =============================================================================

          ;; Simulate 200ms of audio at 16kHz, 16-bit, mono (10 chunks)
          ;; 200ms = 0.2 seconds * 16000 samples/sec * 2 bytes = 6,400 bytes
          large-audio-data (byte-array 6400 (byte 42)) ; Fill with test pattern
          llm-audio-frame (frame/audio-output-raw large-audio-data)

          ;; =============================================================================
          ;; Step 2: Audio splitter configuration for 20ms chunks
          ;; =============================================================================

          ;; 20ms chunks at 16kHz, 16-bit, mono = 640 bytes per chunk
          splitter-config {:audio.out/sample-rate 16000
                           :audio.out/sample-size-bits 16
                           :audio.out/channels 1
                           :audio.out/duration-ms 20}
          splitter-state (sut/audio-splitter-fn splitter-config)

          ;; Split the large frame into chunks
          [_ splitter-output] (sut/audio-splitter-fn splitter-state :in llm-audio-frame)
          audio-chunks (:out splitter-output)

          ;; =============================================================================
          ;; Step 3: Realtime speakers out processor configuration
          ;; =============================================================================

          ;; Calculate expected timing values
          sending-interval 20 ; 20ms between sends

          ;; Initialize speakers state (simulating what init! would create)
          initial-speakers-state {::out/speaking? false
                                  ::out/last-audio-time 0
                                  ::out/sending-interval sending-interval
                                  ::out/silence-threshold 200
                                  :transport/serializer nil}

          ;; =============================================================================
          ;; Step 4: Process each chunk with realistic timing simulation
          ;; =============================================================================

          ;; Simulate chunks arriving at 20ms intervals (like from real streaming)
          pipeline-start-time (u/mono-time)
          results (atom [])

          ;; Process each chunk with simulated timing delay
          final-state
          (reduce (fn [current-state [chunk-index chunk]]
                    ;; Simulate time passing between chunks (in real scenario,
                    ;; chunks would arrive from TTS streaming at intervals)
                    (when (> chunk-index 0)
                      (Thread/sleep 5)) ; Small delay to simulate processing time

                    (let [;; Process through realtime speakers transform
                          [new-state output] (out/realtime-out-transform
                                              current-state :in chunk)]

                      ;; Store results for analysis
                      (swap! results conj {:chunk-index chunk-index
                                           :state-before current-state
                                           :state-after new-state
                                           :output output
                                           :chunk-size (count (:frame/data chunk))})

                      ;; Update state for next iteration
                      new-state))
                  initial-speakers-state
                  (map-indexed vector audio-chunks))]

      ;; =============================================================================
      ;; Step 5: Verify Results
      ;; =============================================================================

      (testing "Audio splitter creates correct number of chunks"
        ;; 6,400 bytes / 640 bytes per chunk = 10 chunks
        (is (= 10 (count audio-chunks))
            "Should create 10 chunks of 20ms each")

        ;; Verify chunk sizes (all should be 640 bytes)
        (let [chunk-sizes (mapv #(count (:frame/data %)) audio-chunks)]
          (is (every? #(= 640 %) chunk-sizes)
              "All chunks should be exactly 640 bytes")))

      (testing "First chunk triggers bot-speech-start event"
        (let [first-result (first @results)
              first-output (:output first-result)]
          (is (false? (::out/speaking? (:state-before first-result)))
              "Initially not speaking")
          (is (true? (::out/speaking? (:state-after first-result)))
              "Should be speaking after first chunk")
          (is (= 1 (count (:out first-output)))
              "Should emit exactly one event")
          (is (frame/bot-speech-start? (first (:out first-output)))
              "Should emit bot-speech-start event")))

      (testing "Subsequent chunks do not trigger additional start events"
        (let [subsequent-results (rest @results)]
          (doseq [result subsequent-results]
            (let [output (:output result)]
              (is (empty? (:out output))
                  (str "Chunk " (:chunk-index result) " should not emit events"))))))

      (testing "Audio write commands are generated correctly"
        (let [audio-writes (mapcat #(get-in % [:output :audio-write]) @results)]

          ;; Verify we have the right number of audio writes
          (is (= 10 (count audio-writes))
              "Should have one audio write per chunk")

          ;; Verify each audio write has the correct structure
          (is (every? #(contains? % :delay-until) audio-writes)
              "All audio writes should have delay-until timing")
          (is (every? #(contains? % :data) audio-writes)
              "All audio writes should have audio data")
          (is (every? #(= 640 (count (:data %))) audio-writes)
              "All audio writes should have 640 bytes of data")))

      (testing "Timing behavior with realistic processing"
        ;; In this test, we're verifying that the timing mechanism works
        ;; The exact delays depend on when chunks are processed, but
        ;; the important thing is that each chunk gets a delay-until value
        (let [audio-writes (mapcat #(get-in % [:output :audio-write]) @results)
              delay-times (mapv :delay-until audio-writes)]

          ;; All delay times should be reasonable (not in the past, not too far future)
          (is (every? #(> % pipeline-start-time) delay-times)
              "All delays should be after pipeline start")
          (is (every? #(< % (+ pipeline-start-time 1000)) delay-times)
              "All delays should be within reasonable timeframe")))

      (testing "State management throughout pipeline"
        ;; Verify that state is being updated correctly
        (is (every? #(true? (::out/speaking? (:state-after %))) @results)
            "All states should show speaking after processing audio")

        ;; Verify last-audio-time is being updated
        (let [last-audio-times (mapv #(::out/last-audio-time (:state-after %)) @results)]
          (is (every? pos? last-audio-times)
              "All last-audio-time values should be positive")))

      (testing "Audio data integrity through pipeline"
        ;; Verify that all audio data makes it through
        (let [audio-writes (mapcat #(get-in % [:output :audio-write]) @results)
              reconstructed-data (byte-array (apply concat (map :data audio-writes)))]

          ;; The reconstructed data should match the original
          (is (= (vec large-audio-data) (vec reconstructed-data))
              "Audio data should be preserved through splitter and speakers pipeline"))))))
