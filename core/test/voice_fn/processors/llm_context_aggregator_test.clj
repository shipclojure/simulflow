(ns voice-fn.processors.llm-context-aggregator-test
  (:require
   [clojure.core.async :as a]
   [clojure.test :refer [deftest is testing]]
   [voice-fn.core]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.processors.llm-context-aggregator :as sut]))

(deftest concat-context-test
  (testing "concatenates new message when role differs"
    (is (= [{:role "system" :content "Hello"}
            {:role "user" :content "World"}]
           (sut/concat-context
             [{:role "system" :content "Hello"}]
             :user
             "World"))))

  (testing "combines messages with same role"
    (is (= [{:role "system" :content "Hello World"}]
           (sut/concat-context
             [{:role "system" :content "Hello"}]
             :system
             "World"))))

  (testing "handles empty context"
    (is (= [{:role "user" :content "Hello"}]
           (sut/concat-context
             []
             :user
             "Hello"))))

  (testing "accepts both keyword and string roles"
    (is (= [{:role "system" :content "Hello World"}]
           (sut/concat-context
             [{:role "system" :content "Hello"}]
             :system
             "World")))

    (is (= [{:role "system" :content "Hello World"}]
           (sut/concat-context
             [{:role :system :content "Hello"}]
             "system"
             "World")))))

(defn make-test-pipeline []
  (let [in-ch (a/chan 1)
        out-ch (a/chan 1)
        pipeline (atom {:pipeline/config {:llm/context [{:role "system" :content "Initial context"}]
                                          :transport/in-ch in-ch
                                          :transport/out-ch out-ch}
                        :pipeline/main-ch (a/chan 1)
                        :pipeline/system-ch (a/chan 1)})]
    pipeline))

(deftest process-aggregator-frame-test
  (testing "user speech aggregation"
    (let [pipeline (make-test-pipeline)
          config sut/user-context-aggregator-options]

      (testing "basic speech aggregation"
        (sut/process-aggregator-frame :context.aggregator/user pipeline config
          (frame/user-speech-start true))
        (is (get-in @pipeline [:context.aggregator/user :aggregation-state :aggregating?])
            "Should start aggregating after speech start")

        (sut/process-aggregator-frame :context.aggregator/user pipeline config
          (frame/transcription "Hello"))

        (sut/process-aggregator-frame :context.aggregator/user pipeline config
          (frame/user-speech-stop true))

        (is (= [{:role "system" :content "Initial context"}
                {:role "user" :content "Hello"}]
               (get-in @pipeline [:pipeline/config :llm/context]))
            "Should aggregate complete transcription")))

    (testing "handles interim results correctly"
      (let [pipeline (make-test-pipeline)
            config sut/user-context-aggregator-options]

        (sut/process-aggregator-frame :context.aggregator/user pipeline config
          (frame/user-speech-start true))

        (sut/process-aggregator-frame :context.aggregator/user pipeline config
          (frame/transcription-interim "Hel"))

        (sut/process-aggregator-frame :context.aggregator/user pipeline config
          (frame/transcription "Hello"))

        (sut/process-aggregator-frame
          :context.aggregator/user pipeline config
          (frame/user-speech-stop true))

        (is (= [{:role "system" :content "Initial context"}
                {:role "user" :content "Hello"}]
               (get-in @pipeline [:pipeline/config :llm/context]))
            "Should handle interim results and use final transcription"))))

  (testing "assistant response aggregation"
    (let [pipeline (make-test-pipeline)
          config sut/assistant-context-aggregator-options]

      (testing "basic response aggregation"
        (sut/process-aggregator-frame :context.aggregator/assistant pipeline config
          (frame/llm-full-response-start true))

        (sut/process-aggregator-frame :context.aggregator/assistant pipeline config
          (frame/llm-text-chunk "Hello"))

        (sut/process-aggregator-frame :context.aggregator/assistant pipeline config
          (frame/llm-text-chunk " World"))

        (sut/process-aggregator-frame :context.aggregator/assistant pipeline config
          (frame/llm-full-response-end true))

        (is (= [{:role "system" :content "Initial context"}
                {:role "assistant" :content "Hello World"}]
               (get-in @pipeline [:pipeline/config :llm/context]))
            "Should aggregate complete assistant response")))))

(deftest edge-cases-test
  (testing "Empty messages aren't added to the context"
    (let [pipeline (make-test-pipeline)
          config sut/user-context-aggregator-options]

      (sut/process-aggregator-frame :context.aggregator/user pipeline config
        (frame/user-speech-start true))

      (sut/process-aggregator-frame :context.aggregator/user pipeline config
        (frame/transcription ""))

      (sut/process-aggregator-frame :context.aggregator/user pipeline config
        (frame/user-speech-stop true))

      (is (= [{:role "system" :content "Initial context"}]
             (get-in @pipeline [:pipeline/config :llm/context])))))

  (testing "handles multiple start frames"
    (let [pipeline (make-test-pipeline)
          config sut/user-context-aggregator-options]

      (sut/process-aggregator-frame :context.aggregator/user pipeline config
        (frame/user-speech-start true))

      (sut/process-aggregator-frame :context.aggregator/user pipeline config
        (frame/user-speech-start true))

      (sut/process-aggregator-frame :context.aggregator/user pipeline config
        (frame/transcription "Hello"))

      (sut/process-aggregator-frame :context.aggregator/user pipeline config
        (frame/user-speech-stop true))

      (is (= [{:role "system" :content "Initial context"}
              {:role "user" :content "Hello"}]
             (get-in @pipeline [:pipeline/config :llm/context]))
          "Should handle multiple start frames gracefully")))

  (testing "handles out of order frames"
    (let [pipeline (make-test-pipeline)
          config sut/user-context-aggregator-options]

      (sut/process-aggregator-frame :context.aggregator/user pipeline config
        (frame/transcription "Hello"))

      (sut/process-aggregator-frame :context.aggregator/user pipeline config
        (frame/user-speech-start true))

      (sut/process-aggregator-frame :context.aggregator/user pipeline config
        (frame/user-speech-stop true))

      (is (= [{:role "system" :content "Initial context"}]
             (get-in @pipeline [:pipeline/config

                                :llm/context]))
          "Should not aggregate when frames are out of order"))))
