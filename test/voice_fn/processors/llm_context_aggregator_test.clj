(ns voice-fn.processors.llm-context-aggregator-test
  (:require
   [clojure.core.async :as a]
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
  "about context aggregation"
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
          (sut/context-aggregator-transform config nil
            (frame/user-speech-start true)) => [sstate]
          (sut/context-aggregator-transform sstate nil (frame/transcription "Hello there")) => [ststate]
          (let [[next-state {:keys [out]}] (sut/context-aggregator-transform ststate nil (frame/user-speech-stop true))
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
            (sut/context-aggregator-transform sstate nil (frame/user-speech-stop true)) =>  [sestate]

            (let [[next-state {:keys [out]}] (sut/context-aggregator-transform sestate nil (frame/transcription "Hello there"))
                  frame (first out)]
              next-state => (state stestate)
              (:frame/type frame) => :frame.llm/context
              (:frame/data frame) => {:messages [{:content "You are a helpful assistant" :role :assistant}
                                                 {:content "Hello there" :role "user"}]})))
    (fact "S I T E -> X"
          (sut/context-aggregator-transform sstate nil (frame/transcription-interim "Hello there")) => [sistate]
          (sut/context-aggregator-transform sistate nil (frame/transcription "Hello there"))  => [ststate])

    (fact "S I E I T -> X"
          (sut/context-aggregator-transform sistate nil (frame/user-speech-stop true)) => [siestate]
          (sut/context-aggregator-transform siestate nil (frame/transcription-interim "Hello, there")) => [siestate]
          (let [[next-state {:keys [out]}] (sut/context-aggregator-transform siestate nil (frame/transcription "Hello there"))
                frame (first out)]
            next-state => (state stestate)
            (:frame/type frame) => :frame.llm/context
            (:frame/data frame) => {:messages [{:content "You are a helpful assistant" :role :assistant}
                                               {:content "Hello there" :role "user"}]}))
    (fact "handles tool result frames"
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
            (:llm/context new-context-state) => new-context
            (:frame/data context-frame) => new-context
            (fact "Doesn't send context further if :send-llm? is false"
                  (let [[new-context-state out] (sut/context-aggregator-transform s nil (frame/llm-tool-call-result {:result tool-result
                                                                                                                     :request tool-request
                                                                                                                     :properties {:run-llm? false}}))]
                    (:llm/context new-context-state) => new-context
                    out => nil))))

    (fact
      "Handles system-config-change frames"
      (let [nc {:messages [{:role :system
                            :content "Your context was just updated"}]}]
        (sut/context-aggregator-transform
          sstate :sys-in
          (frame/system-config-change
            {:llm/context nc})) => [(assoc sstate :llm/context nc)]))
    (fact
      "Handles scenario-context-update frames"
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
        ns => {:aggregating? true
               :aggregation ""
               :seen-end-frame? false
               :seen-interim-results? false
               :seen-start-frame? true
               :llm/context cu}

        (frame/llm-context? out-frame)
        (:frame/data out-frame) => cu))
    (fact
      "Handles llm-context-messages-append frames"
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

        (fact "Updates context and routes based on properties"
              ;; Case 1: Normal append with run-llm
              (let [[new-state {:keys [out]}]
                    (sut/context-aggregator-transform
                      state nil
                      (frame/llm-context-messages-append
                        {:messages new-messages
                         :properties {:run-llm? true}}))]

                ;; Check state update
                (get-in new-state [:llm/context :messages]) => [{:role :system :content "Initial context"}
                                                                {:role :user :content "Hello"}
                                                                {:role :assistant :content "Hi there"}]

                ;; Check if message was sent to out channel
                (count out) => 1
                (frame/llm-context? (first out)) => true
                (get-in (first out) [:frame/data :messages]) => [{:role :system :content "Initial context"}
                                                                 {:role :user :content "Hello"}
                                                                 {:role :assistant :content "Hi there"}])

              ;; Case 2: Append with tool call
              (let [[new-state {:keys [out tool-write]}]
                    (sut/context-aggregator-transform
                      state nil
                      (frame/llm-context-messages-append
                        {:messages (conj new-messages tool-request)
                         :properties {:tool-call? true
                                      :run-llm? false}}))]

                ;; Check state update
                (get-in new-state [:llm/context :messages]) => [{:role :system :content "Initial context"}
                                                                {:role :user :content "Hello"}
                                                                {:role :assistant :content "Hi there"}
                                                                tool-request]

                ;; Check if message was sent to tool-write channel
                (count tool-write) => 1
                (frame/llm-context? (first tool-write)) => true
                (get-in (first tool-write) [:frame/data :messages]) => [{:role :system :content "Initial context"}
                                                                        {:role :user :content "Hello"}
                                                                        {:role :assistant :content "Hi there"}
                                                                        tool-request]

                ;; Verify no message sent to out channel when only tool-call? is true
                out => nil)

              ;; Case 3: Append with both tool call and run-llm
              (let [[new-state {:keys [out tool-write]}]
                    (sut/context-aggregator-transform
                      state nil
                      (frame/llm-context-messages-append
                        {:messages (conj new-messages tool-request)
                         :properties {:tool-call? true
                                      :run-llm? true}}))]

                ;; Check state update
                (get-in new-state [:llm/context :messages]) => [{:role :system :content "Initial context"}
                                                                {:role :user :content "Hello"}
                                                                {:role :assistant :content "Hi there"}
                                                                tool-request]

                ;; Check if message was sent to both channels
                (count tool-write) => 1
                (count out) => 1

                ;; Verify tool-write message
                (frame/llm-context? (first tool-write)) => true
                (get-in (first tool-write) [:frame/data :messages]) => [{:role :system :content "Initial context"}
                                                                        {:role :user :content "Hello"}
                                                                        {:role :assistant :content "Hi there"}
                                                                        tool-request]

                ;; Verify out message
                (frame/llm-context? (first out)) => true
                (get-in (first out) [:frame/data :messages]) => [{:role :system :content "Initial context"}
                                                                 {:role :user :content "Hello"}
                                                                 {:role :assistant :content "Hi there"}
                                                                 tool-request]))))
    (fact
      "Handles speak frames by adding them to assistant context"
      (let [initial-context {:messages [{:role :system :content "Initial context"}]
                             :tools []}
            state (assoc sstate :llm/context initial-context)
            [next-state out] (sut/context-aggregator-transform state nil (frame/speak-frame "Hello! How can I help you"))]
        (:llm/context next-state) => {:messages [{:content "Initial context" :role :system}
                                                 {:content "Hello! How can I help you" :role :assistant}]
                                      :tools []}
        out => nil))))

(comment

  (let [new-messages [{:role :user :content "Hello"}
                      {:role :assistant :content "Hi there"}]
        tool-request {:role :assistant, :tool_calls [{:id "call_LCEOwyJ6wsqC5rzJRH0uMnR8", :type :function, :function {:name "get_weather", :arguments "{\"town\":\"New York\"}"}}]}]

    (frame/llm-context-messages-append
      {:messages (conj new-messages tool-request)
       :properties {:tool-call? true
                    :run-llm? true}}))

  ,)

(def chunk->frame (comp frame/llm-tool-call-chunk first :tool_calls :delta first :choices))

(facts
  "about assistant response aggregation"
  (let [;; Start state
        sstate {:function-arguments nil
                :function-name nil
                :tool-call-id nil
                :content-aggregation nil}
        ;; State after text accumulation
        ststate (merge sstate {:content-aggregation "Hi! How can I help you?"})]

    (fact "S T E -> X"
          (sut/assistant-context-assembler-transform sstate nil
                                                     (frame/llm-full-response-start true)) => [sstate]
          (sut/assistant-context-assembler-transform sstate nil
                                                     (frame/llm-text-chunk "Hi! How can I help you?")) => [ststate]
          (let [[next-state {:keys [out]}] (sut/assistant-context-assembler-transform ststate nil
                                                                                      (frame/llm-full-response-end true))
                frame (first out)]
            next-state => sstate
            (:frame/type frame) => :frame.llm/context-messages-append
            (:frame/data frame) => {:messages [{:role :assistant
                                                :content [{:text  "Hi! How can I help you?" :type :text}]}]
                                    :properties {:run-llm? false
                                                 :tool-call? false}}))

    (fact "S T T T T T E -> X (streaming tokens pattern)"
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
            (get final-state :content-aggregation) => expected-response

            (:frame/type frame) => :frame.llm/context-messages-append
            (:frame/data frame) => {:messages [{:role :assistant
                                                :content [{:text expected-response :type :text}]}]
                                    :properties {:run-llm? false
                                                 :tool-call? false}}))

    (fact
      "Handles tool call streams"
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
        next-state => sstate
        (:frame/type out-frame) => :frame.llm/context-messages-append
        (:frame/data out-frame) => {:messages [{:role :assistant
                                                :tool_calls [{:function {:arguments "{\"ticker\":\"MSFT\",\"fields\":[\"price\",\"volume\"],\"date\":\"2023-10-10\"}"
                                                                         :name "retrieve_latest_stock_data"}
                                                              :id "call_frPVnoe8ruDicw50T8sLHki7"
                                                              :type :function}]}]
                                    :properties {:run-llm? false
                                                 :tool-call? true}}))
    (fact "Handles tool calls with no arguments"
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
            next-state => sstate
            (:frame/type out-frame) => :frame.llm/context-messages-append
            (:frame/data out-frame) => {:messages [{:role :assistant
                                                    :tool_calls [{:function {:arguments "{}"
                                                                             :name "end_call"}
                                                                  :id "call_J9MSffmnxdPj8r28tNzCO8qj"
                                                                  :type :function}]}]
                                        :properties {:run-llm? false
                                                     :tool-call? true}}))))

(facts
  "About the tool caller"
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

    (fact
      "Handles sync calls correctly"
      (let [res (sut/handle-tool-call sync-context)]
        (frame/llm-tool-call-result? res) => true
        (:frame/data res) => {:result {:content [{:text "The weather in New York is 17 degrees celsius" :type :text}]
                                       :role :tool
                                       :tool_call_id "call_LCEOwyJ6wsqC5rzJRH0uMnR8"}
                              :request {:role :assistant
                                        :tool_calls [{:function {:arguments "{\"town\":\"New York\"}"
                                                                 :name "get_weather"}
                                                      :id "call_LCEOwyJ6wsqC5rzJRH0uMnR8"
                                                      :type :function}]}
                              :properties {:run-llm? true
                                           :on-update nil}}))
    (fact
      "Handles async calls correctly"
      (let [res (sut/handle-tool-call async-context)]
        (frame/llm-tool-call-result? res) => true
        (:frame/data res) => {:properties {:on-update nil :run-llm? true}
                              :request {:role :assistant
                                        :tool_calls [{:function {:arguments "{}" :name "end_call"}
                                                      :id "call_J9MSffmnxdPj8r28tNzCO8qj"
                                                      :type :function}]}
                              :result {:content [{:text "Call with id test-call-id has ended" :type :text}]
                                       :role :tool
                                       :tool_call_id "call_J9MSffmnxdPj8r28tNzCO8qj"}}))))
