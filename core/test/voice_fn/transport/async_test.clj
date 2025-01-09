(ns voice-fn.transport.async-test
  (:require
   [clojure.core.async :as a]
   [clojure.test :refer [are deftest testing]]
   [voice-fn.test-system :refer [create-test-pipeline default-pipeline-config valid-pipeline?]]
   [voice-fn.utils.audio :as au]))

(defn create-test-audio
  "Creates a byte array of specified size filled with a repeating pattern
   for easy verification"
  [size]
  (let [arr (byte-array size)]
    (dotimes [i size]
      (aset-byte arr i (byte (mod i 256))))
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
