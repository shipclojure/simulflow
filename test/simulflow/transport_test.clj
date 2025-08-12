(ns simulflow.transport-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.frame :as frame]
   [simulflow.transport :as sut]
   [simulflow.transport.out :as out]
   [simulflow.utils.core :as u]))

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

(deftest test-audio-splitter-config-duration-ms
  (testing "uses provided duration-ms"
    (let [config (sut/audio-splitter-config {:audio.out/duration-ms 25})]
      (is (= 25 (:audio.out/duration-ms config)))))

  (testing "applies default duration-ms when not provided"
    (let [config (sut/audio-splitter-config {})]
      (is (= 40 (:audio.out/duration-ms config)))))

  (testing "preserves other config values"
    (let [input {:audio.out/duration-ms 30 :other/key "value"}
          config (sut/audio-splitter-config input)]
      (is (= 30 (:audio.out/duration-ms config)))
      (is (= "value" (:other/key config))))))

(deftest test-audio-splitter-config-validation
  (testing "validates duration-ms range - minimum"
    (is (thrown? Exception
                 (sut/audio-splitter-config {:audio.out/duration-ms 0}))))

  (testing "validates duration-ms range - maximum"
    (is (thrown? Exception
                 (sut/audio-splitter-config {:audio.out/duration-ms 2000}))))

  (testing "accepts valid duration-ms values"
    (is (map? (sut/audio-splitter-config {:audio.out/duration-ms 20})))
    (is (map? (sut/audio-splitter-config {:audio.out/duration-ms 100})))))

(deftest test-audio-splitter-fn-multi-arity
  (testing "0-arity returns description"
    (let [desc (sut/audio-splitter-fn)]
      (is (contains? desc :ins))
      (is (contains? desc :outs))
      (is (contains? desc :params))
      (is (= "Channel for raw audio frames" (get-in desc [:ins :in])))))

  (testing "1-arity initializes state"
    (let [config {:audio.out/duration-ms 25}
          state (sut/audio-splitter-fn config)]
      (is (= 25 (:audio.out/duration-ms state)))))

  (testing "2-arity handles transitions"
    (let [state {:audio.out/duration-ms 30}
          result (sut/audio-splitter-fn state :clojure.core.async.flow/stop)]
      (is (= state result))))

  (testing "3-arity transforms audio frames"
    (let [state {:audio.out/duration-ms 20}
          ;; Create proper audio-output-raw frame with sample rate
          audio-data (byte-array (range 120))
          frame (frame/audio-output-raw {:audio audio-data :sample-rate 16000})
          [new-state output] (sut/audio-splitter-fn state :in frame)]
      (is (= state new-state))
      ;; With 20ms at 16kHz: chunk size = 16000 * 1 * 2 * 0.02 = 640 bytes
      ;; 120 bytes / 640 = 0.1875, so we get 1 chunk of 120 bytes
      (is (= 1 (count (:out output))))
      (is (every? frame/audio-output-raw? (:out output)))
      (is (= [120] (mapv #(count (:audio (:frame/data %))) (:out output))))
      ;; Check sample rate is preserved
      (is (every? #(= 16000 (:sample-rate (:frame/data %))) (:out output)))))

  (testing "3-arity ignores non-audio frames"
    (let [state {:audio.out/duration-ms 25}
          non-audio-frame {:frame/type :other}
          [new-state output] (sut/audio-splitter-fn state :in non-audio-frame)]
      (is (= state new-state))
      (is (empty? output)))))

(deftest test-audio-splitter-property-based
  (testing "chunk size calculation with different sample rates and durations"
    (doseq [sample-rate [8000 16000 24000 44100 48000]
            duration-ms [10 20 25 40 50]]
      (let [;; Create test audio frame
            test-audio (byte-array 5000)
            frame (frame/audio-output-raw {:audio test-audio :sample-rate sample-rate})
            ;; Configure splitter 
            config {:audio.out/duration-ms duration-ms}
            state (sut/audio-splitter-config config)
            ;; Process frame
            [_ output] (sut/audio-splitter-fn state :in frame)]

        ;; Verify chunks were created
        (is (seq (:out output))
            (str "Should create chunks for " sample-rate "Hz, " duration-ms "ms"))

        ;; Verify sample rates are preserved
        (is (every? #(= sample-rate (:sample-rate (:frame/data %))) (:out output))
            "Sample rates should be preserved in all chunks")

        ;; Calculate expected chunk size and verify first chunk
        (let [expected-chunk-size (* sample-rate 1 2 (/ duration-ms 1000.0))
              first-chunk-size (count (:audio (:frame/data (first (:out output)))))]
          (is (or (= (int expected-chunk-size) first-chunk-size)
                  (< first-chunk-size (int expected-chunk-size)))
              (str "First chunk size should match expected size for "
                   sample-rate "Hz, " duration-ms "ms")))))))

(deftest test-audio-splitter-integration
  (testing "complete audio splitting workflow with new API"
    (let [;; Create test audio data (2000 bytes)
          audio-data (byte-array (range 2000))
          frame (frame/audio-output-raw {:audio audio-data :sample-rate 16000})

          ;; Configure splitter for 20ms chunks at 16kHz
          ;; Expected chunk size: 16000 * 1 * 2 * 0.02 = 640 bytes
          config {:audio.out/duration-ms 20}
          state (sut/audio-splitter-config config)

          ;; Process the frame
          [final-state output] (sut/audio-splitter-fn state :in frame)]

      ;; Verify processing
      (is (= state final-state))
      ;; 2000 bytes / 640 bytes per chunk = 3.125, so 4 chunks total
      (is (= 4 (count (:out output))))

      ;; Verify chunk sizes: [640, 640, 640, 80]
      (let [chunk-sizes (mapv #(count (:audio (:frame/data %))) (:out output))]
        (is (= [640 640 640 80] chunk-sizes)))

      ;; Verify sample rates are preserved
      (is (every? #(= 16000 (:sample-rate (:frame/data %))) (:out output))
          "All chunks should preserve the original sample rate")

      ;; Verify data integrity
      (let [reconstructed-data (vec (apply concat
                                           (map #(vec (:audio (:frame/data %))) (:out output))))]
        (is (= (vec audio-data) reconstructed-data)
            "Audio data should be preserved through splitting")))))

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
          llm-audio-frame (frame/audio-output-raw {:audio large-audio-data :sample-rate 16000})

          ;; =============================================================================
          ;; Step 2: Audio splitter configuration for 20ms chunks
          ;; =============================================================================

          ;; Configure splitter for 20ms chunks (will calculate chunk size based on frame's sample rate)
          splitter-config {:audio.out/duration-ms 20}
          splitter-state (sut/audio-splitter-config splitter-config)

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
                                  ::out/last-send-time 0
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
          _final-state
          (reduce (fn [current-state [chunk-index chunk]]
                    ;; Simulate time passing between chunks (in real scenario,
                    ;; chunks would arrive from TTS streaming at intervals)
                    (when (> chunk-index 0)
                      (Thread/sleep 5)) ; Small delay to simulate processing time

                    (let [;; Process through realtime speakers transform
                          [new-state output] (out/base-realtime-out-transform
                                               current-state :in chunk)]

                      ;; Store results for analysis
                      (swap! results conj {:chunk-index chunk-index
                                           :state-before current-state
                                           :state-after new-state
                                           :output output
                                           :chunk-size (count (:audio (:frame/data chunk)))})

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
        (let [chunk-sizes (mapv #(count (:audio (:frame/data %))) audio-chunks)]
          (is (every? #(= 640 %) chunk-sizes)
              "All chunks should be exactly 640 bytes"))

        ;; Verify sample rates are preserved
        (is (every? #(= 16000 (:sample-rate (:frame/data %))) audio-chunks)
            "All chunks should preserve the original 16kHz sample rate"))

      (testing "First chunk triggers bot-speech-start event"
        (let [first-result (first @results)
              first-output (:output first-result)]
          (is (false? (::out/speaking? (:state-before first-result)))
              "Initially not speaking")
          (is (true? (::out/speaking? (:state-after first-result)))
              "Should be speaking after first chunk")
          (is (= 1 (count (:sys-out first-output)))
              "Should emit exactly one event")
          (is (frame/bot-speech-start? (first (:sys-out first-output)))
              "Should emit bot-speech-start event")))

      (testing "Subsequent chunks do not trigger additional start events"
        (let [subsequent-results (rest @results)]
          (doseq [result subsequent-results]
            (let [output (:output result)]
              (is (empty? (:sys-out output))
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

          (is (every? #(> % pipeline-start-time) (rest delay-times))
              "All delays should be after pipeline start")
          (is (every? #(< % (+ pipeline-start-time 1000)) delay-times)
              "All delays should be within reasonable timeframe")))

      (testing "State management throughout pipeline"
        ;; Verify that state is being updated correctly
        (is (every? #(true? (::out/speaking? (:state-after %))) @results)
            "All states should show speaking after processing audio")

        ;; Verify last-send-time is being updated
        (let [last-send-time (mapv #(::out/last-send-time (:state-after %)) @results)]
          (is (every? pos? last-send-time)
              "All last-send-time values should be positive")))

      (testing "Audio data integrity through pipeline"
        ;; Verify that all audio data makes it through
        (let [audio-writes (mapcat #(get-in % [:output :audio-write]) @results)
              reconstructed-data (byte-array (apply concat (map :data audio-writes)))]

          ;; The reconstructed data should match the original
          (is (= (vec large-audio-data) (vec reconstructed-data))
              "Audio data should be preserved through splitter and speakers pipeline"))))))
