(ns voice-fn.processors.llm-context-aggregator-test
  (:require
   [midje.sweet :refer [fact facts]]
   [voice-fn.frame :as frame]
   [voice-fn.mock-data :as mock]
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
  (let [config {:llm/context {:messages [{:role :assistant :content "You are a helpful assistant"}]}}
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
          (sut/user-aggregator-transform config nil
            (frame/user-speech-start true)) => [sstate]
          (sut/user-aggregator-transform sstate nil (frame/transcription "Hello there")) => [ststate]
          (let [[next-state {:keys [out]}] (sut/user-aggregator-transform ststate nil (frame/user-speech-stop true))
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
            (sut/user-aggregator-transform sstate nil (frame/user-speech-stop true)) =>  [sestate]

            (let [[next-state {:keys [out]}] (sut/user-aggregator-transform sestate nil (frame/transcription "Hello there"))
                  frame (first out)]
              next-state => (state stestate)
              (:frame/type frame) => :frame.llm/context
              (:frame/data frame) => {:messages [{:content "You are a helpful assistant" :role :assistant}
                                                 {:content "Hello there" :role "user"}]})))
    (fact "S I T E -> X"
          (sut/user-aggregator-transform sstate nil (frame/transcription-interim "Hello there")) => [sistate]
          (sut/user-aggregator-transform sistate nil (frame/transcription "Hello there"))  => [ststate])

    (fact "S I E I T -> X"
          (sut/user-aggregator-transform sistate nil (frame/user-speech-stop true)) => [siestate]
          (sut/user-aggregator-transform siestate nil (frame/transcription-interim "Hello, there")) => [siestate]
          (let [[next-state {:keys [out]}] (sut/user-aggregator-transform siestate nil (frame/transcription "Hello there"))
                frame (first out)]
            next-state => (state stestate)
            (:frame/type frame) => :frame.llm/context
            (:frame/data frame) => {:messages [{:content "You are a helpful assistant" :role :assistant}
                                               {:content "Hello there" :role "user"}]}))
    (fact "updates current context if a context frame is received"
          (let [new-context {:messages [{:content "You are a helpful assistant" :role :assistant}
                                        {:content "Hello there" :role "user"}
                                        {:content "How can I help" :role :assistant}]}]
            (sut/user-aggregator-transform ststate nil (frame/llm-context new-context)) => [(assoc ststate :llm/context new-context) nil]))))

(def chunk->frame (comp frame/llm-tool-call-chunk first :tool_calls :delta first :choices))

(facts
  "about assistant response aggregation"
  (let [config {:llm/context {:messages [{:role "assistant" :content "You are a helpful assistant"}
                                         {:role "user" :content "Hello there"}]}}
        state (partial merge config)
        ;; State after start frame
        sstate (state {:aggregating? true
                       :function-arguments nil
                       :function-name nil
                       :tool-call-id nil
                       :content-aggregation nil
                       :seen-end-frame? false
                       :seen-interim-results? false
                       :seen-start-frame? true})
        ;; State after text accumulation
        ststate (state {:aggregating? true
                        :content-aggregation "Hi! How can I help you?"
                        :function-arguments nil
                        :function-name nil
                        :tool-call-id nil
                        :seen-end-frame? false
                        :seen-interim-results? false
                        :seen-start-frame? true})
        ;; State after complete sequence (final state)
        stestate (state {:aggregating? false
                         :content-aggregation nil
                         :function-arguments nil
                         :function-name nil
                         :tool-call-id nil
                         :seen-end-frame? false
                         :seen-interim-results? false
                         :seen-start-frame? false
                         :llm/context {:messages [{:role "assistant" :content "You are a helpful assistant"}
                                                  {:role "user" :content "Hello there"}
                                                  {:role :assistant
                                                   :content [{:text  "Hi! How can I help you?" :type :text}]}]}})]

    (fact "S T E -> X"
          (sut/assistant-aggregator-transform config nil
                                              (frame/llm-full-response-start true)) => [sstate]
          (sut/assistant-aggregator-transform sstate nil
                                              (frame/llm-text-chunk "Hi! How can I help you?")) => [ststate]
          (let [[next-state {:keys [out]}] (sut/assistant-aggregator-transform ststate nil
                                                                               (frame/llm-full-response-end true))
                frame (first out)]
            next-state => stestate
            (:frame/type frame) => :frame.llm/context
            (:frame/data frame) => {:messages [{:role "assistant" :content "You are a helpful assistant"}
                                               {:role "user" :content "Hello there"}
                                               {:role :assistant
                                                :content [{:text  "Hi! How can I help you?" :type :text}]}]}))

    (fact "S T T T T T E -> X (streaming tokens pattern)"
          (let [token-chunks ["Hi" "!" " How" " can" " I" " help" " you" "?"]
                expected-response "Hi! How can I help you?"

                ;; Accumulate all token chunks
                final-state (reduce (fn [current-state frame]
                                      (let [[next-state] (sut/assistant-aggregator-transform
                                                           current-state
                                                           nil
                                                           frame)]
                                        next-state))
                                    sstate
                                    (map frame/llm-text-chunk token-chunks))

                ;; Final state after end frame
                [next-state {:keys [out]}] (sut/assistant-aggregator-transform
                                             final-state
                                             nil
                                             (frame/llm-full-response-end true))
                frame (first out)]

            ;; Verify intermediate state has accumulated all tokens
            (get final-state :content-aggregation) => expected-response

            ;; Verify final state and output
            next-state => (state {:aggregating? false
                                  :content-aggregation nil
                                  :function-arguments nil
                                  :function-name nil
                                  :tool-call-id nil
                                  :seen-end-frame? false
                                  :seen-interim-results? false
                                  :seen-start-frame? false
                                  :llm/context {:messages [{:role "assistant"
                                                            :content "You are a helpful assistant"}
                                                           {:role "user"
                                                            :content "Hello there"}
                                                           {:role :assistant
                                                            :content [{:text expected-response :type :text}]}]}})

            (:frame/type frame) => :frame.llm/context
            (:frame/data frame) => {:messages [{:role "assistant"
                                                :content "You are a helpful assistant"}
                                               {:role "user"
                                                :content "Hello there"}
                                               {:role :assistant
                                                :content [{:text expected-response :type :text}]}]}))

    (fact "updates current context if a frame is received"
          (let [new-context {:messages [{:content "You are a helpful assistant" :role :assistant}
                                        {:content "Hello there" :role "user"}
                                        {:content "How can I help" :role :assistant}]}]
            (sut/assistant-aggregator-transform ststate nil (frame/llm-context new-context)) => [(assoc ststate :llm/context new-context)]))
    (fact
      "Handles tool call streams"
      (let [final-state (reduce (fn [current-state frame]
                                  (let [[next-state] (sut/assistant-aggregator-transform
                                                       current-state
                                                       nil
                                                       frame)]
                                    next-state))
                                sstate
                                (map chunk->frame mock/mock-tool-call-response))
            ;; Final state after end frame
            [next-state {:keys [out tool-write]}] (sut/assistant-aggregator-transform
                                                    final-state
                                                    nil
                                                    (frame/llm-full-response-end true))
            out-frame (first out)
            tool-write-frame (first tool-write)]
        next-state => {:aggregating? false
                       :content-aggregation nil
                       :function-arguments nil
                       :function-name nil
                       :seen-end-frame? false
                       :seen-interim-results? false
                       :seen-start-frame? false
                       :tool-call-id nil
                       :llm/context {:messages [{:content "You are a helpful assistant"
                                                 :role "assistant"}
                                                {:content "Hello there" :role "user"}
                                                {:role :assistant
                                                 :tool_calls [{:function {:arguments {:date "2023-10-10"
                                                                                      :fields ["price"
                                                                                               "volume"]
                                                                                      :ticker "MSFT"}
                                                                          :name "retrieve_latest_stock_data"}
                                                               :id "call_frPVnoe8ruDicw50T8sLHki7"
                                                               :type :function}]}]}}
        (= out-frame tool-write-frame) => true

        (:frame/type out-frame) => :frame.llm/context
        (:frame/data out-frame) => {:messages [{:content "You are a helpful assistant" :role "assistant"}
                                               {:content "Hello there" :role "user"}
                                               {:role :assistant
                                                :tool_calls [{:function {:arguments {:date "2023-10-10"
                                                                                     :fields ["price" "volume"]
                                                                                     :ticker "MSFT"}
                                                                         :name "retrieve_latest_stock_data"}
                                                              :id "call_frPVnoe8ruDicw50T8sLHki7"
                                                              :type :function}]}]}))))
