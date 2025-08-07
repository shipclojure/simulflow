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
                    :audio.out/sample-size-bits 16}))))
  ;; missing channels and duration-ms

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
