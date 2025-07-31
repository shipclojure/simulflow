(ns simulflow.processors.audio-resampler-test
  "Comprehensive test suite for the audio resampler processor"
  (:require
   [clojure.core.async.flow :as flow]
   [clojure.test :refer [deftest is testing]]
   [simulflow.frame :as frame]
   [simulflow.processors.audio-resampler :as sut]))

;; Test Fixtures and Utilities

(defn- create-test-audio-data
  "Create test audio data as byte array"
  [size]
  (byte-array (map byte (take size (cycle (range -64 64))))))

(defn- create-ulaw-test-data
  "Create realistic μ-law test data"
  [size]
  ;; μ-law values are typically in a specific range
  (byte-array (map byte (take size (cycle (range -128 128 8))))))

;; Processor Multi-Arity Function Tests

(deftest test-processor-describe
  (testing "Processor description (0-arity)"
    (let [desc (sut/audio-resampler-fn)]
      (is (map? desc))
      (is (contains? desc :ins))
      (is (contains? desc :outs))
      (is (contains? desc :params))
      (is (= "Channel for audio-input-raw frames to be resampled" (get-in desc [:ins :in])))
      (is (= "Channel for resampled audio-input-raw frames" (get-in desc [:outs :out]))))))

(deftest test-processor-init
  (testing "Processor initialization (1-arity)"

    (testing "Default configuration"
      (let [state (sut/audio-resampler-fn {})]
        (is (map? state))
        (is (= 8000 (:audio-resample/source-sample-rate state)))
        (is (= 16000 (:audio-resample/target-sample-rate state)))
        (is (= :ulaw (:audio-resample/source-encoding state)))
        (is (= :pcm-signed (:audio-resample/target-encoding state)))
        (is (= 1 (:audio-resample/channels state)))
        (is (= 16 (:audio-resample/sample-size-bits state)))))

    (testing "Custom configuration"
      (let [config {:audio-resample/source-sample-rate 44100
                    :audio-resample/target-sample-rate 48000
                    :audio-resample/source-encoding :pcm-signed
                    :audio-resample/target-encoding :pcm-float
                    :audio-resample/channels 2
                    :audio-resample/sample-size-bits 24}
            state (sut/audio-resampler-fn config)]
        (is (= 44100 (:audio-resample/source-sample-rate state)))
        (is (= 48000 (:audio-resample/target-sample-rate state)))
        (is (= :pcm-signed (:audio-resample/source-encoding state)))
        (is (= :pcm-float (:audio-resample/target-encoding state)))
        (is (= 2 (:audio-resample/channels state)))
        (is (= 24 (:audio-resample/sample-size-bits state)))))))

(deftest test-processor-transform
  (testing "Processor transform function (3-arity)"
    (let [state {:audio-resample/source-sample-rate 8000
                 :audio-resample/target-sample-rate 16000
                 :audio-resample/source-encoding :ulaw
                 :audio-resample/target-encoding :pcm-signed
                 :audio-resample/channels 1
                 :audio-resample/sample-size-bits 16}]

      (testing "Audio frame processing"
        (let [test-data (create-ulaw-test-data 80)
              input-frame (frame/audio-input-raw test-data)
              [new-state output] (sut/audio-resampler-fn state :in input-frame)]

          (is (= state new-state))
          (is (map? output))
          (is (contains? output :out))
          (is (vector? (:out output)))
          (is (= 1 (count (:out output))))

          (let [output-frame (first (:out output))]
            (is (frame/audio-input-raw? output-frame))
            (is (not= (alength (:frame/data input-frame)) (alength (:frame/data output-frame))))
            (is (> (alength (:frame/data output-frame)) (alength (:frame/data input-frame))))
            ;; Timestamp should be preserved
            (is (= (:frame/ts input-frame) (:frame/ts output-frame))))))

      (testing "Non-audio frame pass-through"
        (let [system-frame (frame/system-start true)
              [new-state output] (sut/audio-resampler-fn state :in system-frame)]

          (is (= state new-state))
          (is (= {:out [system-frame]} output))))

      (testing "Wrong input port"
        (let [audio-frame (frame/audio-input-raw (create-test-audio-data 100))
              [new-state output] (sut/audio-resampler-fn state :wrong-port audio-frame)]

          (is (= state new-state))
          (is (= {:out [audio-frame]} output)))))))

(deftest test-audio-output-frame-handling
  (testing "Audio resampler handles audio-output-raw frames correctly"
    (let [state {:audio-resample/source-sample-rate 16000
                 :audio-resample/target-sample-rate 8000
                 :audio-resample/source-encoding :pcm-signed
                 :audio-resample/target-encoding :pcm-signed
                 :audio-resample/channels 1
                 :audio-resample/sample-size-bits 16}]

      (testing "Audio-output-raw frame returns same frame type"
        (let [test-data (create-test-audio-data 320) ; 16kHz PCM data
              input-frame (frame/audio-output-raw test-data)
              [new-state output] (sut/audio-resampler-fn state :in input-frame)]

          (is (= state new-state) "State should remain unchanged")
          (is (map? output) "Output should be a map")
          (is (contains? output :out) "Output should contain :out key")
          (is (vector? (:out output)) "Output :out should be a vector")
          (is (= 1 (count (:out output))) "Should output exactly one frame")

          (let [output-frame (first (:out output))]
            (is (frame/audio-output-raw? output-frame) "Output frame should be audio-output-raw type")
            (is (not (frame/audio-input-raw? output-frame)) "Output frame should NOT be audio-input-raw type")
            (is (not= (alength (:frame/data input-frame)) (alength (:frame/data output-frame)))
                "Data length should change due to resampling")
            (is (< (alength (:frame/data output-frame)) (alength (:frame/data input-frame)))
                "Output should be smaller (downsampling 16kHz -> 8kHz)")
            ;; Timestamp should be preserved
            (is (= (:frame/ts input-frame) (:frame/ts output-frame)) "Timestamp should be preserved"))))

      (testing "Both input and output audio frames are processed"
        (let [test-data (create-test-audio-data 160)
              input-frame (frame/audio-input-raw test-data)
              output-frame (frame/audio-output-raw test-data)

              [_ input-result] (sut/audio-resampler-fn state :in input-frame)
              [_ output-result] (sut/audio-resampler-fn state :in output-frame)]

          ;; Both should be processed (not passed through unchanged)
          (is (not= input-frame (first (:out input-result))) "Input frame should be transformed")
          (is (not= output-frame (first (:out output-result))) "Output frame should be transformed")

          ;; Frame types should be preserved
          (is (frame/audio-input-raw? (first (:out input-result))) "Input frame type preserved")
          (is (frame/audio-output-raw? (first (:out output-result))) "Output frame type preserved"))))))

(deftest test-buffer-size-and-endian-support
  (testing "Audio resampler supports buffer-size and endian parameters"
    (let [state {:audio-resample/source-sample-rate 16000
                 :audio-resample/target-sample-rate 8000
                 :audio-resample/source-encoding :pcm-signed
                 :audio-resample/target-encoding :pcm-signed
                 :audio-resample/channels 1
                 :audio-resample/sample-size-bits 16
                 :audio-resample/buffer-size 2048
                 :audio-resample/endian :big-endian}]

      (testing "Configuration includes buffer-size and endian"
        (let [config (sut/audio-resampler-fn {:audio-resample/buffer-size 2048
                                              :audio-resample/endian :big-endian})]
          (is (= 2048 (:audio-resample/buffer-size config)) "Buffer size should be set")
          (is (= :big-endian (:audio-resample/endian config)) "Endian should be set")))

      (testing "Transform function uses buffer-size and endian parameters"
        (let [test-data (create-test-audio-data 320)
              input-frame (frame/audio-input-raw test-data)
              [new-state output] (sut/audio-resampler-fn state :in input-frame)]

          (is (= state new-state) "State should remain unchanged")
          (is (= 1 (count (:out output))) "Should output one frame")
          (let [output-frame (first (:out output))]
            (is (frame/audio-input-raw? output-frame) "Frame type should be preserved")
            (is (not= (alength (:frame/data input-frame)) (alength (:frame/data output-frame)))
                "Audio data should be resampled"))))

      (testing "Default values for buffer-size and endian"
        (let [default-config (sut/audio-resampler-fn {})]
          (is (= 1024 (:audio-resample/buffer-size default-config)) "Default buffer size should be 1024")
          (is (= :little-endian (:audio-resample/endian default-config)) "Default endian should be little-endian"))))))

;; Integration Tests

(deftest test-processor-in-flow
  (testing "Audio resampler processor in flow context"
    (let [flow-config {:procs
                       {:resampler {:proc sut/process
                                    :args {:audio-resample/source-sample-rate 8000
                                           :audio-resample/target-sample-rate 16000
                                           :audio-resample/source-encoding :ulaw
                                           :audio-resample/target-encoding :pcm-signed}}}
                       :conns []}

          test-flow (flow/create-flow flow-config)]

      ;; Basic flow creation test
      (is (some? test-flow))

      ;; Start and stop the flow
      (flow/start test-flow)
      (flow/stop test-flow))))
