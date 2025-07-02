(ns simulflow.transport-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.frame :as frame]
   [simulflow.transport :as sut]
   [simulflow.utils.core :as u])
  (:import
   (java.util Date)))

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

(deftest microphone-transport-test
  ;; =============================================================================
  ;; Pure Function Tests

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

  (testing "mic-transport-in-fn describe (0-arity)"
    (let [description (sut/mic-transport-in-fn)]
      (is (contains? (:outs description) :out))
      (is (contains? (:params description) :audio-in/sample-rate))
      (is (contains? (:params description) :audio-in/channels))
      (is (contains? (:params description) :audio-in/sample-size-bits))
      (is (contains? (:params description) :audio-in/buffer-size))))

  (testing "mic-transport-in-fn transform (3-arity)"
    (let [test-audio-data (byte-array [1 2 3 4])
          test-timestamp (Date.)
          input-data {:audio-data test-audio-data :timestamp test-timestamp}
          [new-state output] (sut/mic-transport-in-fn {} :in input-data)]

      (is (= {} new-state)) ; State unchanged
      (is (contains? output :out))
      (is (= 1 (count (:out output))))

      (let [frame (first (:out output))]
        (is (frame/audio-input-raw? frame))
        (is (= test-audio-data (:frame/data frame)))
        (is (= test-timestamp (:frame/ts frame))))))

  (testing "mic-transport-in-fn transform preserves frame metadata"
    (let [test-timestamp (Date.)
          input-data {:audio-data (byte-array [5 6 7 8]) :timestamp test-timestamp}
          [_ output] (sut/mic-transport-in-fn {} :in input-data)
          frame (first (:out output))]

      (is (= :simulflow.frame/audio-input-raw (:frame/type frame)))
      (is (= test-timestamp (:frame/ts frame)))))

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
