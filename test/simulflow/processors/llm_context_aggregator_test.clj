(ns simulflow.processors.llm-context-aggregator-test
  (:require
   [clojure.core.async :as a]
   [clojure.test :refer [deftest is testing]]
   [simulflow.frame :as frame]
   [simulflow.mock-data :as mock]
   [simulflow.processors.llm-context-aggregator :as sut]))

(deftest concat-context-test
  (testing "concat-context"
    (testing "concatenates new message when role differs"
      (is (= [{:role "system", :content "Hello"} {:role :user, :content "World"}]
             (sut/concat-context-messages
               [{:role "system" :content "Hello"}]
               :user
               "World"))))

    (testing "combines messages with same role"
      (is (= [{:role :system, :content "Hello World"}]
             (sut/concat-context-messages
               [{:role "system" :content "Hello"}]
               :system
               "World"))))

    (testing "handles empty context"
      (is (= [{:role :user, :content "Hello"}]
             (sut/concat-context-messages
               []
               :user
               "Hello"))))

    (testing "accepts both keyword and string roles"
      (is (= [{:role :system, :content "Hello World"}]
             (sut/concat-context-messages
               [{:role "system" :content "Hello"}]
               :system
               "World")))

      (is (= [{:role "system", :content "Hello World"}]
             (sut/concat-context-messages
               [{:role :system :content "Hello"}]
               "system"
               "World"))))

    (testing "accepts array as entry with multiple entries"
      (is (= [{:role :system :content "Hello"}
              {:role :user :content "Hi there"}
              {:role :assistant :content "How are you doing?"}]
             (sut/concat-context-messages
               [{:role :system :content "Hello"}]
               [{:role :user :content "Hi there"}
                {:role :assistant :content "How are you doing?"}]))))))

(deftest context-aggregation-test
  (testing "context aggregation"
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

      (testing "S T E -> X"
        (is (= [sstate]
               (sut/context-aggregator-transform config nil
                                                 (frame/user-speech-start true))))
        (is (= [ststate]
               (sut/context-aggregator-transform sstate nil (frame/transcription "Hello there"))))
        (let [[next-state {:keys [out]}] (sut/context-aggregator-transform ststate nil (frame/user-speech-stop true))
              frame (first out)]
          (is (= stestate next-state))
          (is (= :simulflow.frame/llm-context (:frame/type frame)))
          (is (= {:messages [{:content "You are a helpful assistant" :role :assistant}
                             {:content "Hello there" :role "user"}]}
                 (:frame/data frame)))))

      (testing "S E T -> X"
        (let [sestate (state {:aggregating? true
                              :aggregation ""
                              :seen-end-frame? true
                              :seen-interim-results? false
                              :seen-start-frame? false})]
          (is (= [sestate]
                 (sut/context-aggregator-transform sstate nil (frame/user-speech-stop true))))

          (let [[next-state {:keys [out]}] (sut/context-aggregator-transform sestate nil (frame/transcription "Hello there"))
                frame (first out)]
            (is (= (state stestate) next-state))
            (is (= :simulflow.frame/llm-context (:frame/type frame)))
            (is (= {:messages [{:content "You are a helpful assistant" :role :assistant}
                               {:content "Hello there" :role "user"}]}
                   (:frame/data frame))))))

      (testing "S I T E -> X"
        (is (= [sistate]
               (sut/context-aggregator-transform sstate nil (frame/transcription-interim "Hello there"))))
        (is (= [ststate]
               (sut/context-aggregator-transform sistate nil (frame/transcription "Hello there")))))

      (testing "S I E I T -> X"
        (is (= [siestate]
               (sut/context-aggregator-transform sistate nil (frame/user-speech-stop true))))
        (is (= [siestate]
               (sut/context-aggregator-transform siestate nil (frame/transcription-interim "Hello, there"))))
        (let [[next-state {:keys [out]}] (sut/context-aggregator-transform siestate nil (frame/transcription "Hello there"))
              frame (first out)]
          (is (= (state stestate) next-state))
          (is (= :simulflow.frame/llm-context (:frame/type frame)))
          (is (= {:messages [{:content "You are a helpful assistant" :role :assistant}
                             {:content "Hello there" :role "user"}]}
                 (:frame/data frame)))))

      (testing "handles tool result frames"
        (let [tool-result {:role :tool
                           :content [{:type :text
                                      :text "The weather in New York is 17 degrees celsius"}]
                           :tool_call_id "call_LCEOwyJ6wsqC5rzJRH0uMnR8"}
              tool-request {:role :assistant, :tool_calls [{:id "call_LCEOwyJ6wsqC5rzJRH0uMnR8", :type :function, :function {:name "get_weather", :arguments "{\"town\":\"New York\"}"}}]}
              context {:messages [{:role "system", :content "You are a voice agent operating via phone. Be concise. The input you receive comes from a speech-to-text (transcription) system that isn't always efficient and may send unclear text. Ask for clarification when you're unsure what the person said."}
                                  {:role "user", :content "What's the weather in New York?"}
                                  tool-request]
                       :tools [{:type :function
                                :function {:name "get_weather"
                                           :description "Get the current weather of a location"
                                           :handler (fn [{:keys [town]}] (str "The weather in " town " is 17 degrees celsius"))
                                           :parameters {:type :object
                                                        :required [:town]
                                                        :properties {:town {:type :string
                                                                            :description "Town for which to retrieve the current weather"}}
                                                        :additionalProperties false}
                                           :strict true}}]}
              s (assoc sstate :llm/context context)
              new-context (update-in context [:messages] conj tool-result)
              [new-context-state {:keys [out]}] (sut/context-aggregator-transform s nil (frame/llm-tool-call-result {:result tool-result
                                                                                                                     :request tool-request}))
              context-frame (first out)]
          (is (= new-context (:llm/context new-context-state)))
          (is (= new-context (:frame/data context-frame)))

          (testing "Doesn't send context further if :send-llm? is false"
            (let [[new-context-state out] (sut/context-aggregator-transform s nil (frame/llm-tool-call-result {:result tool-result
                                                                                                               :request tool-request
                                                                                                               :properties {:run-llm? false}}))]
              (is (= new-context (:llm/context new-context-state)))
              (is (nil? out))))))

      (testing "Handles system-config-change frames"
        (let [nc {:messages [{:role :system
                              :content "Your context was just updated"}]}]
          (is (= [(assoc sstate :llm/context nc)]
                 (sut/context-aggregator-transform
                   sstate :sys-in
                   (frame/system-config-change
                     {:llm/context nc}))))))

      (testing "Handles scenario-context-update frames"
        (let [start {:aggregating? true
                     :llm/context {:messages []
                                   :tools []}
                     :aggregation ""
                     :seen-end-frame? false
                     :seen-interim-results? false
                     :seen-start-frame? true}
              handler (fn [{:keys [size]}] size)
              cu {:messages [{:role :system
                              :content "You are a restaurant reservation assistant for La Maison, an upscale French restaurant. You must ALWAYS use one of the available functions to progress the conversation. This is a phone conversations and your responses will be converted to audio. Avoid outputting special characters and emojis. Be casual and friendly."}
                             {:role :system
                              :content "Warmly greet the customer and ask how many people are in their party."}]
                  :tools [{:type :function
                           :function
                           {:name "record_party_size"
                            :handler handler
                            :description "Record the number of people in the party"
                            :parameters
                            {:type :object
                             :properties
                             {:size {:type :integer
                                     :description "Number of people that will dine at the restaurant"
                                     :minimum 1
                                     :maximum 12}}
                             :required [:size]}
                            :transition-to :get-time}}]}
              scu (frame/scenario-context-update (assoc cu :properties {:run-llm? true}))
              [ns {:keys [out]}] (sut/context-aggregator-transform start :in scu)
              out-frame (first out)]
          (is (= {:aggregating? true
                  :aggregation ""
                  :seen-end-frame? false
                  :seen-interim-results? false
                  :seen-start-frame? true
                  :llm/context cu}
                 ns))
          (is (frame/llm-context? out-frame))
          (is (= cu (:frame/data out-frame)))))

      (testing "Handles llm-context-messages-append frames"
        (let [initial-context {:messages [{:role :system :content "Initial context"}]
                               :tools [{:type :function
                                        :function {:name "get_weather"
                                                   :description "Get the current weather of a location"
                                                   :handler (fn [{:keys [town]}] (str "The weather in " town " is 17 degrees celsius"))
                                                   :parameters {:type :object
                                                                :required [:town]
                                                                :properties {:town {:type :string
                                                                                    :description "Town for which to retrieve the current weather"}}
                                                                :additionalProperties false}
                                                   :strict true}}]}
              state (assoc sstate :llm/context initial-context)
              new-messages [{:role :user :content "Hello"}
                            {:role :assistant :content "Hi there"}]
              tool-request {:role :assistant, :tool_calls [{:id "call_LCEOwyJ6wsqC5rzJRH0uMnR8", :type :function, :function {:name "get_weather", :arguments "{\"town\":\"New York\"}"}}]}]

          (testing "Updates context and routes based on properties"
            ;; Case 1: Normal append with run-llm
            (let [[new-state {:keys [out]}]
                  (sut/context-aggregator-transform
                    state nil
                    (frame/llm-context-messages-append
                      {:messages new-messages
                       :properties {:run-llm? true}}))]

              ;; Check state update
              (is (= [{:role :system :content "Initial context"}
                      {:role :user :content "Hello"}
                      {:role :assistant :content "Hi there"}]
                     (get-in new-state [:llm/context :messages])))

              ;; Check if message was sent to out channel
              (is (= 1 (count out)))
              (is (frame/llm-context? (first out)))
              (is (= [{:role :system :content "Initial context"}
                      {:role :user :content "Hello"}
                      {:role :assistant :content "Hi there"}]
                     (get-in (first out) [:frame/data :messages]))))

            ;; Case 2: Append with tool call
            (let [[new-state {:keys [out tool-write]}]
                  (sut/context-aggregator-transform
                    state nil
                    (frame/llm-context-messages-append
                      {:messages (conj new-messages tool-request)
                       :properties {:tool-call? true
                                    :run-llm? false}}))]

              ;; Check state update
              (is (= [{:role :system :content "Initial context"}
                      {:role :user :content "Hello"}
                      {:role :assistant :content "Hi there"}
                      tool-request]
                     (get-in new-state [:llm/context :messages])))

              ;; Check if message was sent to tool-write channel
              (is (= 1 (count tool-write)))
              (is (frame/llm-context? (first tool-write)))
              (is (= [{:role :system :content "Initial context"}
                      {:role :user :content "Hello"}
                      {:role :assistant :content "Hi there"}
                      tool-request]
                     (get-in (first tool-write) [:frame/data :messages])))

              ;; Verify no message sent to out channel when only tool-call? is true
              (is (nil? out)))

            ;; Case 3: Append with both tool call and run-llm
            (let [[new-state {:keys [out tool-write]}]
                  (sut/context-aggregator-transform
                    state nil
                    (frame/llm-context-messages-append
                      {:messages (conj new-messages tool-request)
                       :properties {:tool-call? true
                                    :run-llm? true}}))]

              ;; Check state update
              (is (= [{:role :system :content "Initial context"}
                      {:role :user :content "Hello"}
                      {:role :assistant :content "Hi there"}
                      tool-request]
                     (get-in new-state [:llm/context :messages])))

              ;; Check if message was sent to both channels
              (is (= 1 (count tool-write)))
              (is (= 1 (count out)))

              ;; Verify tool-write message
              (is (frame/llm-context? (first tool-write)))
              (is (= [{:role :system :content "Initial context"}
                      {:role :user :content "Hello"}
                      {:role :assistant :content "Hi there"}
                      tool-request]
                     (get-in (first tool-write) [:frame/data :messages])))

              ;; Verify out message
              (is (frame/llm-context? (first out)))
              (is (= [{:role :system :content "Initial context"}
                      {:role :user :content "Hello"}
                      {:role :assistant :content "Hi there"}
                      tool-request]
                     (get-in (first out) [:frame/data :messages])))))))

      (testing "Handles speak frames by adding them to assistant context"
        (let [initial-context {:messages [{:role :system :content "Initial context"}]
                               :tools []}
              state (assoc sstate :llm/context initial-context)
              [next-state out] (sut/context-aggregator-transform state nil (frame/speak-frame "Hello! How can I help you"))]
          (is (= {:messages [{:content "Initial context" :role :system}
                             {:content "Hello! How can I help you" :role :assistant}]
                  :tools []}
                 (:llm/context next-state)))
          (is (nil? out)))))))

(defn chunk->frame [chunk-data]
  (let [tool-call-data (-> chunk-data :choices first :delta :tool_calls first)
        created-timestamp (:created chunk-data)]
    (frame/llm-tool-call-chunk tool-call-data
                               {:timestamp (when created-timestamp
                                             (* created-timestamp 1000))})))

(deftest assistant-response-aggregation-test
  (testing "assistant response aggregation"
    (let [;; Start state
          sstate {:function-arguments nil
                  :function-name nil
                  :tool-call-id nil
                  :content-aggregation nil}
          ;; State after text accumulation
          ststate (merge sstate {:content-aggregation "Hi! How can I help you?"})]

      (testing "S T E -> X"
        (is (= [sstate]
               (sut/assistant-context-assembler-transform sstate nil
                                                          (frame/llm-full-response-start true))))
        (is (= [ststate]
               (sut/assistant-context-assembler-transform sstate nil
                                                          (frame/llm-text-chunk "Hi! How can I help you?"))))
        (let [[next-state {:keys [out]}] (sut/assistant-context-assembler-transform ststate nil
                                                                                    (frame/llm-full-response-end true))
              frame (first out)]
          (is (= sstate next-state))
          (is (= :simulflow.frame/llm-context-messages-append (:frame/type frame)))
          (is (= {:messages [{:role :assistant
                              :content [{:text "Hi! How can I help you?" :type :text}]}]
                  :properties {:run-llm? false
                               :tool-call? false}}
                 (:frame/data frame)))))

      (testing "S T T T T T E -> X (streaming tokens pattern)"
        (let [token-chunks ["Hi" "!" " How" " can" " I" " help" " you" "?"]
              expected-response "Hi! How can I help you?"

              ;; Accumulate all token chunks
              final-state (reduce (fn [current-state frame]
                                    (let [[next-state] (sut/assistant-context-assembler-transform
                                                         current-state
                                                         nil
                                                         frame)]
                                      next-state))
                                  sstate
                                  (map frame/llm-text-chunk token-chunks))

              ;; Final state after end frame
              [_ {:keys [out]}] (sut/assistant-context-assembler-transform
                                  final-state
                                  nil
                                  (frame/llm-full-response-end true))
              frame (first out)]

          ;; Verify intermediate state has accumulated all tokens
          (is (= expected-response (get final-state :content-aggregation)))

          (is (= :simulflow.frame/llm-context-messages-append (:frame/type frame)))
          (is (= {:messages [{:role :assistant
                              :content [{:text expected-response :type :text}]}]
                  :properties {:run-llm? false
                               :tool-call? false}}
                 (:frame/data frame)))))

      (testing "Handles tool call streams"
        (let [final-state (reduce (fn [current-state frame]
                                    (let [[next-state] (sut/assistant-context-assembler-transform
                                                         current-state
                                                         nil
                                                         frame)]
                                      next-state))
                                  sstate
                                  (map chunk->frame mock/mock-tool-call-response))
              ;; Final state after end frame
              [next-state {:keys [out]}] (sut/assistant-context-assembler-transform
                                           final-state
                                           nil
                                           (frame/llm-full-response-end true))
              out-frame (first out)]
          (is (= sstate next-state))
          (is (= :simulflow.frame/llm-context-messages-append (:frame/type out-frame)))
          (is (= {:messages [{:role :assistant
                              :tool_calls [{:function {:arguments "{\"ticker\":\"MSFT\",\"fields\":[\"price\",\"volume\"],\"date\":\"2023-10-10\"}"
                                                       :name "retrieve_latest_stock_data"}
                                            :id "call_frPVnoe8ruDicw50T8sLHki7"
                                            :type :function}]}]
                  :properties {:run-llm? false
                               :tool-call? true}}
                 (:frame/data out-frame)))))

      (testing "Handles tool calls with no arguments"
        (let [final-state (reduce (fn [current-state frame]
                                    (let [[next-state] (sut/assistant-context-assembler-transform
                                                         current-state
                                                         nil
                                                         frame)]
                                      next-state))
                                  sstate
                                  (map chunk->frame mock/mock-tool-call-response-single-argument))
              ;; Final state after end frame
              [next-state {:keys [out]}] (sut/assistant-context-assembler-transform
                                           final-state
                                           nil
                                           (frame/llm-full-response-end true))
              out-frame (first out)]
          (is (= sstate next-state))
          (is (= :simulflow.frame/llm-context-messages-append (:frame/type out-frame)))
          (is (= {:messages [{:role :assistant
                              :tool_calls [{:function {:arguments "{}"
                                                       :name "end_call"}
                                            :id "call_J9MSffmnxdPj8r28tNzCO8qj"
                                            :type :function}]}]
                  :properties {:run-llm? false
                               :tool-call? true}}
                 (:frame/data out-frame))))))))

(deftest tool-caller-test
  (testing "About the tool caller"
    (let [call-id "test-call-id"
          tools [{:type :function
                  :function
                  {:name "get_weather"
                   :description "Get the current weather of a location"
                   :handler (fn [{:keys [town]}] (str "The weather in " town " is 17 degrees celsius"))
                   :parameters {:type :object
                                :required [:town]
                                :properties {:town {:type :string
                                                    :description "Town for which to retrieve the current weather"}}
                                :additionalProperties false}
                   :strict true}}
                 {:type "function"
                  :function
                  {:name "end_call"
                   :handler (fn [_]
                              (a/thread
                                (a/<!! (a/timeout 300))
                                (str "Call with id " call-id " has ended")))
                   :description "End the current call"
                   :parameters {:type "object"
                                :required []
                                :properties {}
                                :additionalProperties false}}}]
          sync-messages [{:content "You are a helpful assistant"
                          :role "assistant"}
                         {:content "Hello there" :role "user"}
                         {:role :assistant
                          :tool_calls [{:id "call_LCEOwyJ6wsqC5rzJRH0uMnR8"
                                        :type :function
                                        :function {:name "get_weather", :arguments "{\"town\":\"New York\"}"}}]}]
          async-messages [{:content "You are a helpful assistant"
                           :role "assistant"}
                          {:content "Hello there" :role "user"}
                          {:role :assistant
                           :tool_calls [{:function {:arguments "{}"
                                                    :name "end_call"}
                                         :id "call_J9MSffmnxdPj8r28tNzCO8qj"
                                         :type :function}]}]

          async-context (frame/llm-context {:messages async-messages :tools tools})
          sync-context (frame/llm-context {:messages sync-messages :tools tools})]

      (testing "Handles sync calls correctly"
        (let [res (sut/handle-tool-call sync-context)]
          (is (frame/llm-tool-call-result? res))
          (let [data (:frame/data res)]
            (is (= {:content [{:text "The weather in New York is 17 degrees celsius" :type :text}]
                    :role :tool
                    :tool_call_id "call_LCEOwyJ6wsqC5rzJRH0uMnR8"}
                   (:result data)))
            (is (= {:role :assistant
                    :tool_calls [{:function {:arguments "{\"town\":\"New York\"}"
                                             :name "get_weather"}
                                  :id "call_LCEOwyJ6wsqC5rzJRH0uMnR8"
                                  :type :function}]}
                   (:request data)))
            (is (true? (get-in data [:properties :run-llm?])))
            (is (fn? (get-in data [:properties :on-update]))))))

      (testing "Handles async calls correctly"
        (let [res (sut/handle-tool-call async-context)]
          (is (frame/llm-tool-call-result? res))
          (let [data (:frame/data res)]
            (is (= {:content [{:text "Call with id test-call-id has ended" :type :text}]
                    :role :tool
                    :tool_call_id "call_J9MSffmnxdPj8r28tNzCO8qj"}
                   (:result data)))
            (is (= {:role :assistant
                    :tool_calls [{:function {:arguments "{}" :name "end_call"}
                                  :id "call_J9MSffmnxdPj8r28tNzCO8qj"
                                  :type :function}]}
                   (:request data)))
            (is (true? (get-in data [:properties :run-llm?])))
            (is (fn? (get-in data [:properties :on-update])))))))))

(deftest llm-sentence-assembler-test
  (testing "llm-sentence-assembler-transform"
    (testing "normal sentence assembly without interruption"
      (testing "assembles complete sentences from text chunks"
        ;; Use real assemble-sentence function, no mocking
        (let [initial-state {::sut/accumulator "Hello"}
              text-chunk (frame/llm-text-chunk " world.")
              [new-state {:keys [sys-out]}] (sut/llm-sentence-assembler-transform initial-state :in text-chunk)]

          ;; Should complete sentence and reset accumulator
          (is (= "" (::sut/accumulator new-state)))
          (is (= 1 (count sys-out)))
          (is (frame/speak-frame? (first sys-out)))
          (is (= "Hello world." (:frame/data (first sys-out))))))

      (testing "accumulates partial sentences"
        ;; Test with text that doesn't end a sentence
        (let [initial-state {::sut/accumulator "Hello"}
              text-chunk (frame/llm-text-chunk " there")
              [new-state output] (sut/llm-sentence-assembler-transform initial-state :in text-chunk)]

          ;; Should accumulate without producing output
          (is (= "Hello there" (::sut/accumulator new-state)))
          (is (nil? output))))

      (testing "handles empty accumulator"
        (let [initial-state {::sut/accumulator ""}
              text-chunk (frame/llm-text-chunk "Hello")
              [new-state output] (sut/llm-sentence-assembler-transform initial-state :in text-chunk)]

          (is (= "Hello" (::sut/accumulator new-state)))
          (is (nil? output))))

      (testing "handles nil accumulator"
        (let [initial-state {::sut/accumulator nil}
              text-chunk (frame/llm-text-chunk "Hello")
              [new-state output] (sut/llm-sentence-assembler-transform initial-state :in text-chunk)]

          (is (= "Hello" (::sut/accumulator new-state)))
          (is (nil? output))))

      (testing "completes sentences with exclamation marks"
        (let [initial-state {::sut/accumulator "Hello world"}
              text-chunk (frame/llm-text-chunk "!")
              [new-state {:keys [sys-out]}] (sut/llm-sentence-assembler-transform initial-state :in text-chunk)]

          (is (= "" (::sut/accumulator new-state)))
          (is (= 1 (count sys-out)))
          (is (frame/speak-frame? (first sys-out)))
          (is (= "Hello world!" (:frame/data (first sys-out))))))

      (testing "completes sentences with question marks"
        (let [initial-state {::sut/accumulator "How are you"}
              text-chunk (frame/llm-text-chunk "?")
              [new-state {:keys [sys-out]}] (sut/llm-sentence-assembler-transform initial-state :in text-chunk)]

          (is (= "" (::sut/accumulator new-state)))
          (is (= 1 (count sys-out)))
          (is (frame/speak-frame? (first sys-out)))
          (is (= "How are you?" (:frame/data (first sys-out)))))))

    (testing "interruption handling"
      (testing "control-interrupt-start clears accumulator and sets interrupted state"
        (let [initial-state {::sut/accumulator "Hello there, how can"
                             :some-other-key "preserved"}
              interrupt-frame (frame/control-interrupt-start true)
              [new-state output] (sut/llm-sentence-assembler-transform initial-state :sys-in interrupt-frame)]

          ;; Should clear accumulator and set interrupted state
          (is (= "" (::sut/accumulator new-state)))
          (is (= true (:pipeline/interrupted? new-state)))
          (is (= "preserved" (:some-other-key new-state))) ; Other state preserved
          (is (= {} output))))

      (testing "control-interrupt-start with empty accumulator"
        (let [initial-state {::sut/accumulator ""
                             :some-other-key "preserved"}
              interrupt-frame (frame/control-interrupt-start true)
              [new-state output] (sut/llm-sentence-assembler-transform initial-state :sys-in interrupt-frame)]

          (is (= "" (::sut/accumulator new-state)))
          (is (= true (:pipeline/interrupted? new-state)))
          (is (= "preserved" (:some-other-key new-state)))
          (is (= {} output))))

      (testing "control-interrupt-stop clears interrupted state"
        (let [initial-state {:pipeline/interrupted? true
                             ::sut/accumulator "some data"
                             :some-other-key "preserved"}
              stop-frame (frame/control-interrupt-stop true)
              [new-state output] (sut/llm-sentence-assembler-transform initial-state :sys-in stop-frame)]

          ;; Should clear interrupted state but preserve other state
          (is (= false (:pipeline/interrupted? new-state)))
          (is (= "some data" (::sut/accumulator new-state))) ; Accumulator preserved
          (is (= "preserved" (:some-other-key new-state)))
          (is (= {} output))))

      (testing "processes llm-text-chunks normally when not interrupted"
        ;; Use real function - test with partial sentence
        (let [initial-state {::sut/accumulator "Hello"
                             :pipeline/interrupted? false}
              text-chunk (frame/llm-text-chunk " world")
              [new-state _output] (sut/llm-sentence-assembler-transform initial-state :in text-chunk)]

          (is (= "Hello world" (::sut/accumulator new-state)))
          (is (= false (:pipeline/interrupted? new-state)))))

      (testing "drops llm-text-chunks when interrupted"
        (let [initial-state {::sut/accumulator "Hello"
                             :pipeline/interrupted? true}
              text-chunk (frame/llm-text-chunk " world")
              [new-state output] (sut/llm-sentence-assembler-transform initial-state :in text-chunk)]

          ;; State should remain unchanged when interrupted
          (is (= "Hello" (::sut/accumulator new-state)))
          (is (= true (:pipeline/interrupted? new-state)))
          (is (= {} output))))

      (testing "processes llm-text-chunks when interrupted? is nil (falsy)"
        ;; Test with real function - use partial sentence
        (let [initial-state {::sut/accumulator "Hello"
                             :pipeline/interrupted? nil}
              text-chunk (frame/llm-text-chunk " world")
              [new-state output] (sut/llm-sentence-assembler-transform initial-state :in text-chunk)]

          ;; Should process normally since nil is falsy
          (is (= "Hello world" (::sut/accumulator new-state)))
          (is (nil? output)))))

    (testing "interruption state transitions"
      (testing "interrupt -> resume -> process cycle"
        ;; Use real assemble-sentence function throughout
        (let [initial-state {::sut/accumulator "Hello there"}
              ;; 1. Interrupt starts - should clear accumulator and set interrupted
              [interrupted-state _] (sut/llm-sentence-assembler-transform
                                      initial-state :sys-in (frame/control-interrupt-start true))]

          (is (= "" (::sut/accumulator interrupted-state)))
          (is (= true (:pipeline/interrupted? interrupted-state)))

          ;; 2. During interruption, text chunks are dropped
          (let [text-chunk (frame/llm-text-chunk "ignored text")
                [still-interrupted _] (sut/llm-sentence-assembler-transform
                                        interrupted-state :in text-chunk)]
            (is (= "" (::sut/accumulator still-interrupted)))
            (is (= true (:pipeline/interrupted? still-interrupted)))

            ;; 3. Interrupt stops - should clear interrupted state
            (let [[resumed-state _] (sut/llm-sentence-assembler-transform
                                      still-interrupted :sys-in (frame/control-interrupt-stop true))]
              (is (= "" (::sut/accumulator resumed-state)))
              (is (= false (:pipeline/interrupted? resumed-state)))

              ;; 4. After resuming, text chunks are processed again
              (let [new-text-chunk (frame/llm-text-chunk "New sentence.")
                    [final-state {:keys [sys-out]}] (sut/llm-sentence-assembler-transform
                                                      resumed-state :in new-text-chunk)]
                (is (= "" (::sut/accumulator final-state)))
                (is (= false (:pipeline/interrupted? final-state)))
                (is (= 1 (count sys-out)))
                (is (frame/speak-frame? (first sys-out)))
                (is (= "New sentence." (:frame/data (first sys-out)))))))))

      (testing "multiple interrupt commands are idempotent"
        (let [initial-state {::sut/accumulator "some text"}

              ;; First interrupt
              [state1 _] (sut/llm-sentence-assembler-transform
                           initial-state :sys-in (frame/control-interrupt-start true))

              ;; Second interrupt (should not change state further)
              [state2 _] (sut/llm-sentence-assembler-transform
                           state1 :sys-in (frame/control-interrupt-start true))]

          (is (= "" (::sut/accumulator state1)))
          (is (= true (:pipeline/interrupted? state1)))

          (is (= "" (::sut/accumulator state2)))
          (is (= true (:pipeline/interrupted? state2)))))

      (testing "stop interrupt when not interrupted is safe"
        (let [initial-state {:pipeline/interrupted? false
                             ::sut/accumulator "preserved text"
                             :some-data "also preserved"}
              [new-state output] (sut/llm-sentence-assembler-transform
                                   initial-state :sys-in (frame/control-interrupt-stop true))]

          (is (= false (:pipeline/interrupted? new-state)))
          (is (= "preserved text" (::sut/accumulator new-state)))
          (is (= "also preserved" (:some-data new-state)))
          (is (= {} output)))))

    (testing "edge cases and error conditions"
      (testing "unknown input port is ignored"
        (let [initial-state {::sut/accumulator "preserved"}
              [new-state output] (sut/llm-sentence-assembler-transform
                                   initial-state :unknown-port "unknown-message")]

          (is (= "preserved" (::sut/accumulator new-state)))
          (is (= {} output))))

      (testing "non-text-chunk frames are ignored"
        (let [initial-state {::sut/accumulator "preserved"}
              other-frame (frame/user-speech-start true)
              [new-state output] (sut/llm-sentence-assembler-transform
                                   initial-state :in other-frame)]

          (is (= "preserved" (::sut/accumulator new-state)))
          (is (= {} output))))

      (testing "nil text chunk data is handled gracefully"
        ;; Use real function - nil will be converted to string by assemble-sentence
        (let [initial-state {::sut/accumulator "Hello"}
              nil-chunk-frame {:frame/type :simulflow.frame/llm-text-chunk
                               :frame/data nil}
              [new-state output] (sut/llm-sentence-assembler-transform
                                   initial-state :in nil-chunk-frame)]

          ;; Real assemble-sentence will handle nil by converting to string
          (is (= "Hello" (::sut/accumulator new-state))) ; nil becomes empty string
          (is (= {} output))))

      (testing "empty text chunk is processed normally"
        ;; Use real function - empty string doesn't end sentence
        (let [initial-state {::sut/accumulator "Hello"}
              empty-chunk (frame/llm-text-chunk "")
              [new-state output] (sut/llm-sentence-assembler-transform
                                   initial-state :in empty-chunk)]

          (is (= "Hello" (::sut/accumulator new-state)))
          (is (nil? output))))

      (testing "state keys are preserved during operations"
        ;; Use real function with partial sentence
        (let [initial-state {::sut/accumulator "Hello"
                             :pipeline/interrupted? false
                             :custom-key "custom-value"
                             :another-key 42}
              text-chunk (frame/llm-text-chunk " world")
              [new-state _] (sut/llm-sentence-assembler-transform
                              initial-state :in text-chunk)]

          ;; Custom keys should be preserved
          (is (= "custom-value" (:custom-key new-state)))
          (is (= 42 (:another-key new-state)))
          (is (= false (:pipeline/interrupted? new-state)))
          (is (= "Hello world" (::sut/accumulator new-state))))))

    (testing "real sentence assembly behavior"
      ;; Test with actual u/assemble-sentence function (no mocking)
      (testing "handles complex sentences correctly"
        (let [initial-state {::sut/accumulator ""}

              ;; Build up a sentence word by word
              [state1 sys-out1] (sut/llm-sentence-assembler-transform
                                  initial-state :in (frame/llm-text-chunk "The U.S.A. is"))
              [state2 sys-out2] (sut/llm-sentence-assembler-transform
                                  state1 :in (frame/llm-text-chunk " a great"))
              [state3 {:keys [sys-out]}] (sut/llm-sentence-assembler-transform
                                           state2 :in (frame/llm-text-chunk " country!"))]

          ;; First two chunks should accumulate (no sentence end detected)
          (is (nil? sys-out1))
          (is (nil? sys-out2))

              ;; Third chunk with exclamation should complete sentence
          (is (= 1 (count sys-out)))
          (is (frame/speak-frame? (first sys-out)))
          (is (= "The U.S.A. is a great country!" (:frame/data (first sys-out))))
          (is (= "" (::sut/accumulator state3)))))

      (testing "handles multiple sentence types"
        ;; Test period
        (let [[_state {:keys [sys-out]}] (sut/llm-sentence-assembler-transform
                                           {::sut/accumulator "Hello"} :in (frame/llm-text-chunk " world."))]
          (is (= 1 (count sys-out)))
          (is (= "Hello world." (:frame/data (first sys-out)))))

        ;; Test question mark
        (let [[_state {:keys [sys-out]}] (sut/llm-sentence-assembler-transform
                                           {::sut/accumulator "How are"} :in (frame/llm-text-chunk " you?"))]
          (is (= 1 (count sys-out)))
          (is (= "How are you?" (:frame/data (first sys-out)))))

        ;; Test exclamation
        (let [[_state {:keys [sys-out]}] (sut/llm-sentence-assembler-transform
                                           {::sut/accumulator "Great"} :in (frame/llm-text-chunk " job!"))]
          (is (= 1 (count sys-out)))
          (is (= "Great job!" (:frame/data (first sys-out)))))))))
