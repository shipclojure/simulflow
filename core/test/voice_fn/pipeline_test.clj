(ns voice-fn.pipeline-test
  (:require
   [clojure.core.async :as a]
   [clojure.test :refer [deftest is testing]]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as sut]
   [voice-fn.protocol :as p]
   [voice-fn.test-system :refer [create-test-pipeline valid-pipeline?]]))

;; Test Fixtures and Helpers

(defn make-test-channels []
  {:in (a/chan 1)
   :out (a/chan 1)})

(defn make-minimal-config []
  {:pipeline/config
   {:audio-in/sample-rate 16000
    :audio-in/channels 1
    :audio-in/encoding :pcm-signed
    :audio-in/sample-size-bits 16
    :audio-out/sample-rate 16000
    :audio-out/channels 1
    :audio-out/encoding :pcm-signed
    :audio-out/sample-size-bits 16
    :pipeline/language :en
    :llm/context [{:role "system" :content "Test system"}]
    :transport/in-ch nil
    :transport/out-ch nil}})

;; Tests

(deftest pipeline-config-validation-test
  (testing "validates minimal valid configuration"
    (let [channels (make-test-channels)
          config (-> (make-minimal-config)
                     (assoc-in [:pipeline/config :transport/in-ch] (:in channels))
                     (assoc-in [:pipeline/config :transport/out-ch] (:out channels)))]
      (is (:valid? (sut/validate-pipeline config))
          "Minimal config should be valid")))

  (testing "catches missing required fields"
    (let [invalid-config (dissoc (make-minimal-config) :pipeline/config)]
      (is (not (:valid? (sut/validate-pipeline invalid-config)))
          "Should catch missing config")
      (is (get-in (sut/validate-pipeline invalid-config) [:errors :pipeline])
          "Should provide validation errors")))

  (testing "validates processor configurations"
    (let [channels (make-test-channels)
          config (-> (make-minimal-config)
                     (assoc-in [:pipeline/config :transport/in-ch] (:in channels))
                     (assoc-in [:pipeline/config :transport/out-ch] (:out channels))
                     (assoc :pipeline/processors
                            [{:processor/id :test/processor
                              :processor/config {:test/config "valid"}}]))]
      (is (:valid? (sut/validate-pipeline config))
          "Valid processor config should validate")

      (let [invalid-config (assoc-in config [:pipeline/processors 0 :processor/config :test/config] 123)]
        (is (not (:valid? (sut/validate-pipeline invalid-config)))
            "Invalid processor config should fail validation")
        (is (= {:processors
                [{:id :test/processor
                  :errors #:test{:config ["should be a string"]}}]}
          (:errors (sut/validate-pipeline invalid-config)))
        "Pipeline validation should output validation errors")))))

(deftest pipeline-creation-test
  (testing "creates pipeline with valid config"
    (let [pipeline (create-test-pipeline {:transport/in-ch (a/chan)
                                          :transport/out-ch (a/chan)}
                     [{:processor/id :test/processor
                       :processor/config {:test/config "test"}}])]
      (valid-pipeline? pipeline)))

  (testing "throws on invalid config"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Invalid pipeline configuration"
          (sut/create-pipeline (make-minimal-config)))
        "Should throw on invalid config")))

(deftest pipeline-frame-processing-test
  (testing "processes frames through processor chain"
    (let [pipeline (create-test-pipeline
                     {:transport/in-ch (a/chan)
                      :transport/out-ch (a/chan)}
                     [{:processor/id :test/processor
                       :processor/config {:test/config "test"}}])
          test-frame (frame/create-frame :frame.test/type "test-data")]

      (sut/start-pipeline! pipeline)

      (sut/send-frame! pipeline test-frame)

      (Thread/sleep 100) ; Allow async processing

      ;; Start frame + test frame
      (is (= 2 (count (get-in @pipeline [:test/processor :processed-frames])))
          "Frame should be processed")))

  (testing "handles system frames with priority"
    (let [pipeline (create-test-pipeline
                     {:transport/in-ch (a/chan)
                      :transport/out-ch (a/chan)}
                     [{:processor/id :test/processor
                       :processor/config {:test/config "test"}}])
          system-frame (frame/system-start true)]

      (sut/start-pipeline! pipeline)

      (sut/send-frame! pipeline system-frame)

      (Thread/sleep 100)                ; Allow async processing

      (is (some #(= :frame.system/start (:frame/type %))
                (get-in @pipeline [:test/processor :processed-frames]))
          "System frame should be processed with priority"))))

(deftest pipeline-lifecycle-test
  (testing "pipeline start/stop"
    (let [pipeline (create-test-pipeline
                     {:transport/in-ch (a/chan)
                      :transport/out-ch (a/chan)}
                     [{:processor/id :test/processor
                       :processor/config {:test/config "test"}}])]

      (sut/start-pipeline! pipeline)
      (Thread/sleep 100)

      (is (some #(= :frame.system/start (:frame/type %))
                (get-in @pipeline [:test/processor :processed-frames]))
          "Start frame should be processed")

      (sut/stop-pipeline! pipeline)
      (Thread/sleep 100)

      (is (some #(= :frame.system/stop (:frame/type %))
                (get-in @pipeline [:test/processor :processed-frames]))
          "Stop frame should be processed"))))

(deftest processor-management-test
  (testing "processor channel closure"
    (let [pipeline (create-test-pipeline
                     {:transport/in-ch (a/chan)
                      :transport/out-ch (a/chan)}
                     [{:processor/id :test/processor
                       :processor/config {:test/config "test"}}])]

      (sut/close-processor! pipeline :test/processor)

      (is (not (a/poll! (get-in @pipeline [:test/processor :processor/in-ch])))
          "Processor channel should be closed"))))

(deftest pipeline-error-handling-test
  (testing "handles processor errors gracefully"
    (let [pipeline (create-test-pipeline
                     {:transport/in-ch (a/chan)
                      :transport/out-ch (a/chan)}
                     [{:processor/id :test/processor
                       :processor/config {:test/config "test"}}])]

      ;; Override process-frame method to simulate error
      (with-redefs [p/process-frame
                    (fn [_ _ _ _]
                      (throw (ex-info "Test error" {:type :test/error})))]

        (sut/start-pipeline! pipeline)
        (sut/send-frame! pipeline (frame/create-frame :frame.test/type "test"))

        (Thread/sleep 100)

        ;; Pipeline should continue running despite error
        (is (some? (get-in @pipeline [:pipeline/main-ch]))
            "Pipeline should remain operational after error")))))
