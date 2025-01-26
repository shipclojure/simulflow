(ns voice-fn.processors.llm-context-aggregator-test
  (:require
   [midje.sweet :refer [fact facts]]
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
        sstate (state {:aggregating? true
                       :aggregation ""
                       :seen-end-frame? false
                       :seen-interim-results? false
                       :seen-start-frame? true})
        ststate (state {:aggregating? true
                        :aggregation "Hello there"
                        :seen-end-frame? false
                        :seen-interim-results? false
                        :seen-start-frame? true})
        stestate (state {:aggregating? false
                         :aggregation ""
                         :seen-end-frame? false
                         :seen-interim-results? false
                         :seen-start-frame? false
                         :llm/context {:messages [{:content "You are a helpful assistant"
                                                   :role :assistant}
                                                  {:content "Hello there" :role "user"}]}})
        sistate (state {:aggregating? true
                        :aggregation ""
                        :seen-end-frame? false
                        :seen-interim-results? true
                        :seen-start-frame? true})

        siestate (state {:aggregating? true
                         :aggregation ""
                         :seen-end-frame? true
                         :seen-interim-results? true
                         :seen-start-frame? false})]

    (fact "S T E -> X"
          (sut/aggregator-transform config nil
                                    (frame/user-speech-start true)) => [sstate]
          (sut/aggregator-transform sstate nil (frame/transcription "Hello there")) => [ststate]
          (let [[next-state {:keys [out]}] (sut/aggregator-transform ststate nil (frame/user-speech-stop true))
                frame (first out)]
            next-state => stestate
            (:frame/type frame) => :frame.llm/context
            (:frame/data frame) => {:messages [{:content "You are a helpful assistant" :role :assistant}
                                               {:content "Hello there" :role "user"}]}))

    (fact "S E T -> X"
          (let [sestate (state {:aggregating? true
                                :aggregation ""
                                :seen-end-frame? true
                                :seen-interim-results? false
                                :seen-start-frame? false})]
            (sut/aggregator-transform sstate nil (frame/user-speech-stop true)) =>  [sestate]

            (let [[next-state {:keys [out]}] (sut/aggregator-transform sestate nil (frame/transcription "Hello there"))
                  frame (first out)]
              next-state => (state stestate)
              (:frame/type frame) => :frame.llm/context
              (:frame/data frame) => {:messages [{:content "You are a helpful assistant" :role :assistant}
                                                 {:content "Hello there" :role "user"}]})))
    (fact "S I T E -> X"
          (sut/aggregator-transform sstate nil (frame/transcription-interim "Hello there")) => [sistate]
          (sut/aggregator-transform sistate nil (frame/transcription "Hello there"))  => [ststate])

    (fact "S I E I T -> X"
          (sut/aggregator-transform sistate nil (frame/user-speech-stop true)) => [siestate]
          (sut/aggregator-transform siestate nil (frame/transcription-interim "Hello, there")) => [siestate]
          (let [[next-state {:keys [out]}] (sut/aggregator-transform siestate nil (frame/transcription "Hello there"))
                frame (first out)]
            next-state => (state stestate)
            (:frame/type frame) => :frame.llm/context
            (:frame/data frame) => {:messages [{:content "You are a helpful assistant" :role :assistant}
                                               {:content "Hello there" :role "user"}]}))
    (fact "updates current context if a frame is received"
          (let [new-context {:messages [{:content "You are a helpful assistant" :role :assistant}
                                        {:content "Hello there" :role "user"}
                                        {:content "How can I help" :role :assistant}]}]
            (sut/aggregator-transform ststate nil (frame/llm-context new-context)) => [(assoc ststate :llm/context new-context)]))))

(facts
  "about assistant response aggregation"
  (let [config {:messages/role "assistant"
                :llm/context {:messages [{:role "assistant" :content "You are a helpful assistant"}
                                         {:role "user" :content "Hello there"}]}
                :aggregator/start-frame? frame/llm-full-response-start?
                :aggregator/end-frame? frame/llm-full-response-end?
                :aggregator/accumulator-frame? frame/llm-text-chunk?}
        state (partial merge config)
        ;; State after start frame
        sstate (state {:aggregating? true
                       :aggregation ""
                       :seen-end-frame? false
                       :seen-interim-results? false
                       :seen-start-frame? true})
        ;; State after text accumulation
        ststate (state {:aggregating? true
                        :aggregation "Hi! How can I help you?"
                        :seen-end-frame? false
                        :seen-interim-results? false
                        :seen-start-frame? true})
        ;; State after complete sequence (final state)
        stestate (state {:aggregating? false
                         :aggregation ""
                         :seen-end-frame? false
                         :seen-interim-results? false
                         :seen-start-frame? false
                         :llm/context {:messages [{:role "assistant" :content "You are a helpful assistant"}
                                                  {:role "user" :content "Hello there"}
                                                  {:role "assistant" :content "Hi! How can I help you?"}]}})]

    (fact "S T E -> X"
          (sut/aggregator-transform config nil
                                    (frame/llm-full-response-start true)) => [sstate]
          (sut/aggregator-transform sstate nil
                                    (frame/llm-text-chunk "Hi! How can I help you?")) => [ststate]
          (let [[next-state {:keys [out]}] (sut/aggregator-transform ststate nil
                                                                     (frame/llm-full-response-end true))
                frame (first out)]
            next-state => stestate
            (:frame/type frame) => :frame.llm/context
            (:frame/data frame) => {:messages [{:role "assistant" :content "You are a helpful assistant"}
                                               {:role "user" :content "Hello there"}
                                               {:role "assistant" :content "Hi! How can I help you?"}]}))

    (fact "S E T -> X"
          (let [sestate (state {:aggregating? true
                                :aggregation ""
                                :seen-end-frame? true
                                :seen-interim-results? false
                                :seen-start-frame? false})]
            (sut/aggregator-transform sstate nil
                                      (frame/llm-full-response-end true)) =>  [sestate]

            (let [[next-state {:keys [out]}] (sut/aggregator-transform sestate nil
                                                                       (frame/llm-text-chunk "Hi! How can I help you?"))
                  frame (first out)]
              next-state => stestate
              (:frame/type frame) => :frame.llm/context
              (:frame/data frame) => {:messages [{:role "assistant" :content "You are a helpful assistant"}
                                                 {:role "user" :content "Hello there"}
                                                 {:role "assistant" :content "Hi! How can I help you?"}]})))

    (fact "S T T T T T E -> X (streaming tokens pattern)"
          (let [token-chunks ["Hi" "!" " How" " can" " I" " help" " you" "?"]
                expected-response "Hi! How can I help you?"

                ;; Accumulate all token chunks
                final-state (reduce (fn [current-state frame]
                                      (let [[next-state] (sut/aggregator-transform
                                                           current-state
                                                           nil
                                                           frame)]
                                        next-state))
                                    sstate
                                    (map frame/llm-text-chunk token-chunks))

                ;; Final state after end frame
                [next-state {:keys [out]}] (sut/aggregator-transform
                                             final-state
                                             nil
                                             (frame/llm-full-response-end true))
                frame (first out)]

            ;; Verify intermediate state has accumulated all tokens
            (get final-state :aggregation) => expected-response

            ;; Verify final state and output
            next-state => (state {:aggregating? false
                                  :aggregation ""
                                  :seen-end-frame? false
                                  :seen-interim-results? false
                                  :seen-start-frame? false
                                  :llm/context {:messages [{:role "assistant"
                                                            :content "You are a helpful assistant"}
                                                           {:role "user"
                                                            :content "Hello there"}
                                                           {:role "assistant"
                                                            :content expected-response}]}})

            (:frame/type frame) => :frame.llm/context
            (:frame/data frame) => {:messages [{:role "assistant"
                                                :content "You are a helpful assistant"}
                                               {:role "user"
                                                :content "Hello there"}
                                               {:role "assistant"
                                                :content expected-response}]}))

    (fact "updates current context if a frame is received"
          (let [new-context {:messages [{:content "You are a helpful assistant" :role :assistant}
                                        {:content "Hello there" :role "user"}
                                        {:content "How can I help" :role :assistant}]}]
            (sut/aggregator-transform ststate nil (frame/llm-context new-context)) => [(assoc ststate :llm/context new-context)]))))
