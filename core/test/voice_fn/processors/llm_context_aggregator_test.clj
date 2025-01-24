(ns voice-fn.processors.llm-context-aggregator-test
  (:require
   [clojure.core.async :as a]
   [clojure.test :refer [deftest is testing]]
   [midje.sweet :refer [fact facts]]
   [voice-fn.core]
   [voice-fn.frame :as frame]
   [voice-fn.processors.llm-context-aggregator :as sut]))

(facts "about concat-context"
  (fact "concatenates new message when role differs"
        (sut/concat-context-messages
          [{:role "system" :content "Hello"}]
          :user
          "World") => [{:role "system", :content "Hello"} {:role :user, :content "World"}])

  (fact "combines messages with same role"
    (sut/concat-context-messages
      [{:role "system" :content "Hello"}]
      :system
      "World") => [{:role :system, :content "Hello World"}])

  (fact "handles empty context"
    (sut/concat-context-messages
      []
      :user
      "Hello") => [{:role :user, :content "Hello"}])

  (fact "accepts both keyword and string roles"
    (sut/concat-context-messages
      [{:role "system" :content "Hello"}]
      :system
      "World") => [{:role :system, :content "Hello World"}]

    (sut/concat-context-messages
      [{:role :system :content "Hello"}]
      "system"
      "World") => [{:role "system", :content "Hello World"}])

  (fact
    "accepts array as entry with multiple entries"
    (sut/concat-context-messages
      [{:role :system :content "Hello"}]
      [{:role :user :content "Hi there"}
       {:role :assistant :content "How are you doing?"}]) => [{:role :system :content "Hello"}
                                                              {:role :user :content "Hi there"}
                                                              {:role :assistant :content "How are you doing?"}]))

(defn make-test-pipeline []
  (let [in-ch (a/chan 1)
        out-ch (a/chan 1)
        pipeline (atom {:pipeline/config {:llm/context [{:role "system" :content "Initial context"}]
                                          :transport/in-ch in-ch
                                          :transport/out-ch out-ch}
                        :pipeline/main-ch (a/chan 1)
                        :pipeline/system-ch (a/chan 1)})]
    pipeline))

(facts
  "about user speech aggregation"
  (let [config {:messages/role "user"
                :llm/context {:messages [{:role :assistant :content "You are a helpful assistant"}]}
                :aggregator/start-frame? frame/user-speech-start?
                :aggregator/end-frame? frame/user-speech-stop?
                :aggregator/accumulator-frame? frame/transcription?
                :aggregator/interim-results-frame? frame/transcription-interim?
                :aggregator/handles-interrupt? false ;; User speaking shouldn't be interrupted
                :aggregator/debug? false}
        state (partial merge config)
        start-frame-state (state {:aggregating? true
                                  :aggregation ""
                                  :seen-end-frame? false
                                  :seen-interim-results? false
                                  :seen-start-frame? true})
        first-aggregation-state (state {:aggregating? true
                                        :aggregation "Hello there"
                                        :seen-end-frame? false
                                        :seen-interim-results? false
                                        :seen-start-frame? true})]

    (sut/aggregator-transform config nil
      (frame/user-speech-start true)) => [start-frame-state]

    (sut/aggregator-transform start-frame-state nil (frame/transcription "Hello there")) => [first-aggregation-state]
    (let [[next-state {:keys [out]}] (sut/aggregator-transform first-aggregation-state nil (frame/user-speech-stop true))
          frame (first out)]
      next-state => (state {:aggregating? false
                            :aggregation ""
                            :seen-end-frame? false
                            :seen-interim-results? false
                            :seen-start-frame? false
                            :llm/context {:messages [{:content "You are a helpful assistant"
                                                      :role :assistant}
                                                     {:content "Hello there" :role :user}]}})
      (:frame/type frame) => :frame.llm/context)))

(facts "about assistant response aggregation"
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
               "Should aggregate complete assistant response"))))

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
