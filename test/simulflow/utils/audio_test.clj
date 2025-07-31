(ns simulflow.utils.audio-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.utils.audio :as audio])
  (:import
   (javax.sound.sampled AudioFormat$Encoding)))

(deftest create-encoding-steps
  (testing "Returns expected result for µ-law to PCM conversion"
    (is (= (audio/create-encoding-steps {:sample-rate 8000 :encoding :ulaw :channels 1 :sample-size-bits 8}
                                        {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16})
           [{:sample-rate 8000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}])))
  (testing "Does nothing on same audio config"
    (is (= (audio/create-encoding-steps {:sample-rate 8000 :encoding :ulaw :channels 1 :sample-size-bits 8}
                                        {:sample-rate 8000 :encoding :ulaw :channels 1 :sample-size-bits 8})
           []))))

(deftest test-create-encoding-steps
  (testing "Audio encoding steps creation"

    (testing "µ-law to PCM conversion (realistic case)"
      (let [source {:sample-rate 8000 :encoding :ulaw :channels 1 :sample-size-bits 8}
            target {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            steps (audio/create-encoding-steps source target)]

        (is (= 2 (count steps)))
        ;; µ-law must convert directly to 16-bit PCM (Java Audio System constraint)
        (is (= {:sample-rate 8000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
               (first steps)))
        (is (= {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
               (second steps)))))

    (testing "Same source and target"
      (let [config {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            steps (audio/create-encoding-steps config config)]
        (is (empty? steps))))

    (testing "Single property difference"
      (let [source {:sample-rate 8000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            target {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            steps (audio/create-encoding-steps source target)]

        (is (= 1 (count steps)))
        (is (= target (first steps)))))

    (testing "Channel conversion"
      (let [source {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            target {:sample-rate 16000 :encoding :pcm-signed :channels 2 :sample-size-bits 16}
            steps (audio/create-encoding-steps source target)]

        (is (= 1 (count steps)))
        (is (= target (first steps)))))

    (testing "Normal PCM conversion (non-µ-law)"
      (let [source {:sample-rate 44100 :encoding :pcm-signed :channels 2 :sample-size-bits 16}
            target {:sample-rate 8000 :encoding :pcm-signed :channels 1 :sample-size-bits 8}
            steps (audio/create-encoding-steps source target)]

        (is (= 3 (count steps)))
        ;; Should follow normal transformation order: sample-rate, sample-size-bits, channels
        (is (= 8000 (:sample-rate (first steps))))
        (is (= 8 (:sample-size-bits (second steps))))
        (is (= 1 (:channels (nth steps 2))))))

    (testing "µ-law direct conversion (same sample rate and bit depth)"
      (let [source {:sample-rate 8000 :encoding :ulaw :channels 1 :sample-size-bits 8}
            target {:sample-rate 8000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            steps (audio/create-encoding-steps source target)]

        (is (= 1 (count steps)))
        ;; Should convert directly to 16-bit PCM
        (is (= target (first steps)))))

    (testing "Multiple properties with µ-law conversion"
      (let [source {:sample-rate 8000 :encoding :ulaw :channels 1 :sample-size-bits 8}
            target {:sample-rate 48000 :encoding :pcm-signed :channels 2 :sample-size-bits 24}
            steps (audio/create-encoding-steps source target)]

        (is (> (count steps) 1))
        ;; First step should be µ-law -> 16-bit PCM
        (is (= {:sample-rate 8000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
               (first steps)))
        ;; Last step should be target
        (is (= target (last steps)))))))

(defn- create-test-audio-data
  "Create test audio data as byte array"
  [size]
  (byte-array (map byte (take size (cycle (range -64 64))))))

(defn- create-ulaw-test-data
  "Create realistic μ-law test data"
  [size]
  ;; μ-law values are typically in a specific range
  (byte-array (map byte (take size (cycle (range -128 128 8))))))

;; Pure Function Tests

(deftest test-audio-format-creation
  (testing "AudioFormat creation with various configurations"

    (testing "8kHz μ-law format"
      (let [format (audio/create-audio-format {:sample-rate 8000 :encoding :ulaw :channels 1 :sample-size-bits 8})]
        (is (= 8000.0 (.getSampleRate format)))
        (is (= AudioFormat$Encoding/ULAW (.getEncoding format)))
        (is (= 8 (.getSampleSizeInBits format)))
        (is (= 1 (.getChannels format)))
        (is (= 1 (.getFrameSize format)))))

    (testing "16kHz PCM format"
      (let [format (audio/create-audio-format {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16})]
        (is (= 16000.0 (.getSampleRate format)))
        (is (= AudioFormat$Encoding/PCM_SIGNED (.getEncoding format)))
        (is (= 16 (.getSampleSizeInBits format)))
        (is (= 1 (.getChannels format)))
        (is (= 2 (.getFrameSize format)))))

    (testing "Default values"
      (let [format (audio/create-audio-format {})]
        (is (= 16000.0 (.getSampleRate format)))
        (is (= AudioFormat$Encoding/PCM_SIGNED (.getEncoding format)))
        (is (= 16 (.getSampleSizeInBits format)))
        (is (= 1 (.getChannels format)))))))

(deftest test-audio-resampling
  (testing "Audio data resampling"

    (testing "8kHz μ-law to 16kHz PCM (realistic Twilio use case)"
      (let [test-data (create-ulaw-test-data 160) ; ~20ms of 8kHz audio
            source-config {:sample-rate 8000 :encoding :ulaw :channels 1 :sample-size-bits 8}
            target-config {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            resampled (audio/resample-audio-data test-data source-config target-config)]

        (is (not= (alength test-data) (alength resampled)))
        (is (> (alength resampled) (alength test-data)))
        ;; Should be roughly 4x size (2x sample rate, 2x bit depth)
        (let [ratio (double (/ (alength resampled) (alength test-data)))]
          (is (< 3.5 ratio 5.0) "Size ratio should be approximately 4x"))))

    (testing "Same format (no conversion needed)"
      (let [test-data (create-test-audio-data 100)
            config {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            resampled (audio/resample-audio-data test-data config config)
            ;; Should be similar size (minor differences due to processing)
            ratio (double (/ (alength resampled) (alength test-data)))]
        (is (< 0.9 ratio 1.1) "Size should be approximately the same")))

    (testing "PCM sample rate conversion only"
      (let [test-data (create-test-audio-data 320) ; 16-bit samples
            source-config {:sample-rate 8000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            target-config {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            resampled (audio/resample-audio-data test-data source-config target-config)
            ;; Should be roughly 2x size (2x sample rate, same bit depth)
            ratio (double (/ (alength resampled) (alength test-data)))]
        (is (< 1.8 ratio 2.2) "Size ratio should be approximately 2x")))

    (testing "Downsampling"
      (let [test-data (create-test-audio-data 1000)
            source-config {:sample-rate 44100 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            target-config {:sample-rate 16000 :encoding :pcm-signed :channels 1 :sample-size-bits 16}
            resampled (audio/resample-audio-data test-data source-config target-config)]

        ;; Should be smaller (downsampling)
        (is (< (alength resampled) (alength test-data)))))))
