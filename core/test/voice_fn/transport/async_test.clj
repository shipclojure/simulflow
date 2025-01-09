(ns voice-fn.transport.async-test
  (:require
   [clojure.core.async :as a]
   [clojure.test :refer [are deftest is testing]]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.test-system :refer [create-test-pipeline default-pipeline-config valid-pipeline?]]
   [voice-fn.transport.async :as sut]
   [voice-fn.utils.audio :as au]))

(comment
  (p/open)
  (add-tap #'p/submit))

(defn create-test-audio
  "Creates a byte array of specified size filled with a repeating pattern
   for easy verification"
  [size]
  (let [arr (byte-array size)]
    (dotimes [i size]
      (aset-byte arr i (byte (mod i 128))))
    arr))

(deftest pipeline-creation
  (testing "Creates valid pipeline with audio output"
    (let [out-ch (a/chan)
          config-audio-chunk-duration 20 ;; 20ms
          pipeline (create-test-pipeline {:transport/in-ch (a/chan)
                                          :transport/out-ch out-ch}
                                         [{:processor/id :processor.transport/async-output
                                           :processor/config {:transport/duration-ms config-audio-chunk-duration}}])]
      (valid-pipeline? pipeline)
      (testing "Audio out processor takes correct config from pipeline config"
        (let [{:transport/keys [audio-chunk-duration
                                supports-interrupt?
                                audio-chunk-size
                                sample-rate
                                channels
                                sample-size-bits]} (get-in @pipeline [:pipeline/processors-m :processor.transport/async-output :config])]
          (are [a b] (= a b)
            sample-rate (:audio-out/sample-rate default-pipeline-config)
            supports-interrupt? (:pipeline/supports-interrupt? default-pipeline-config)
            sample-size-bits (:audio-out/sample-size-bits default-pipeline-config)
            channels (:audio-out/channels default-pipeline-config)
            audio-chunk-duration config-audio-chunk-duration
            audio-chunk-size (au/audio-chunk-size {:sample-rate (:audio-out/sample-rate default-pipeline-config)
                                                   :sample-size-bits (:audio-out/sample-size-bits default-pipeline-config)
                                                   :channels (:audio-out/channels default-pipeline-config)
                                                   :duration-ms config-audio-chunk-duration})))))))

(defn collect-until-timeout
  "Collects values from a channel into a vector until timeout occurs.
   Returns a channel that will receive the collected vector.

   Parameters:
   - ch: The input channel to collect values from
   - timeout-ms: Timeout duration in milliseconds"
  [ch timeout-ms]
  (let [result-ch (a/chan)]
    (let [timer (a/timeout timeout-ms)]
      (a/go-loop [collected []]
        (a/alt!
          ch ([v]
              (if (nil? v)
                (do (a/>! result-ch collected)
                    (a/close! result-ch))
                (recur (conj collected v))))

          timer ([_]
                 (a/>! result-ch collected)
                 (a/close! result-ch)))))
    result-ch))

(deftest buffered-output
  (testing "Buffered output of data every 10ms"
    (let [out-ch (a/chan)
          config-audio-chunk-duration 20 ;; 20ms
          pipeline (create-test-pipeline
                     {:transport/in-ch (a/chan)
                      :transport/out-ch out-ch}
                     [{:processor/id :processor.transport/async-output
                       :processor/config {:transport/duration-ms config-audio-chunk-duration}}])
          ;; Create test audio data - 3 chunks worth
          chunk-size (au/audio-chunk-size
                       {:sample-rate (:audio-out/sample-rate default-pipeline-config)
                        :sample-size-bits (:audio-out/sample-size-bits default-pipeline-config)
                        :channels (:audio-out/channels default-pipeline-config)
                        :duration-ms config-audio-chunk-duration})
          test-audio (create-test-audio (* chunk-size 20))]
      ;; Start the pipeline
      (pipeline/start-pipeline! pipeline)

      ;; Send test audio frame
      (pipeline/send-frame! pipeline (frame/audio-output-raw test-audio))

      ;; Collect frames for 100ms (should get at least 4-5 chunks)
      (let [output-frames (a/<!! (collect-until-timeout out-ch 200))]

        (tap> output-frames)
        (testing "Chunk sizes"
          (is (= chunk-size
                 (count (:frame/data (first output-frames))))
              "First chunk should be exactly one chunk size")

          (is (every? #(= chunk-size (count (:frame/data %)))
                      (butlast output-frames))
              "All but last chunk should be chunk-size")))

      ;; Test cleanup
      (testing "Cleanup on stop"
        (pipeline/stop-pipeline! pipeline)

        ;; Wait a bit to ensure any final frames are processed
        (a/<!! (a/timeout 50))

        (is (nil? (get-in @pipeline [:processor.transport/async-output :chunking?]))
            "Chunking flag should be cleared after stop")

        (is (nil? (get-in @pipeline [:processor.transport/async-output :audio-buffer]))
            "Audio buffer should be cleared after stop"))

      ;; Clean up
      (a/close! out-ch))))
