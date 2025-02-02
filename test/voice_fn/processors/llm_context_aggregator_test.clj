(ns voice-fn.processors.llm-context-aggregator-test
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
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
    (fact "handles tool result frames"
          (let [tool-result {:role :tool
                             :content [{:type :text
                                        :text "The weather in New York is 17 degrees celsius"}]
                             :tool_call_id "call_LCEOwyJ6wsqC5rzJRH0uMnR8"}
                tool-request {:role :assistant, :tool_calls [{:id "call_LCEOwyJ6wsqC5rzJRH0uMnR8", :type :function, :function {:name "get_weather", :arguments "{\"town\":\"New York\"}"}}]}
                context {:messages [{:role "system", :content "You are a voice agent operating via phone. Be concise. The input you receive comes from a speech-to-text (transcription) system that isn't always efficient and may send unclear text. Ask for clarification when you're unsure what the person said."}
                                    {:role "user", :content "What's the weather in New York?"}]
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
                new-context (update-in context [:messages] conj tool-request tool-result)
                [new-context-state {:keys [out]}] (sut/user-aggregator-transform s nil (frame/llm-tool-call-result {:result tool-result
                                                                                                                    :request tool-request}))
                context-frame (first out)]
            (:llm/context new-context-state) => new-context
            (:frame/data context-frame) => new-context
            (fact "Doesn't send context further if :send-llm? is false"
                  (let [[new-context-state out] (sut/user-aggregator-transform s nil (frame/llm-tool-call-result {:result tool-result
                                                                                                                  :request tool-request
                                                                                                                  :properties {:run-llm? false}}))]
                    (:llm/context new-context-state) => new-context
                    out => nil))))

    (fact
      "Handles system-config-change frames"
      (let [nc {:messages [{:role :system
                            :content "Your context was just updated"}]}]
        (sut/user-aggregator-transform
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
            scu (frame/scenario-context-update cu)
            [ns {:keys [out]}] (sut/user-aggregator-transform start :in scu)
            out-frame (first out)]
        ns => {:aggregating? true
               :aggregation ""
               :seen-end-frame? false
               :seen-interim-results? false
               :seen-start-frame? true
               :llm/context cu}

        (frame/llm-context? out-frame)
        (:frame/data out-frame) => cu))))

(def chunk->frame (comp frame/llm-tool-call-chunk first :tool_calls :delta first :choices))

(facts
  "about assistant response aggregation"
  (let [config {:llm/context {:messages [{:role "assistant" :content "You are a helpful assistant"}
                                         {:role "user" :content "Hello there"}]}}
        state (partial merge config)
        ;; State after start frame
        sstate (state {:function-arguments nil
                       :function-name nil
                       :tool-call-id nil
                       :content-aggregation nil})
        ;; State after text accumulation
        ststate (state {:content-aggregation "Hi! How can I help you?"
                        :function-arguments nil
                        :function-name nil
                        :tool-call-id nil})
        ;; State after complete sequence (final state)
        stestate (state {:content-aggregation nil
                         :function-arguments nil
                         :function-name nil
                         :tool-call-id nil
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
            next-state => (state {:content-aggregation nil
                                  :function-arguments nil
                                  :function-name nil
                                  :tool-call-id nil
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
        next-state => {:content-aggregation nil
                       :function-arguments nil
                       :function-name nil
                       :tool-call-id nil
                       :llm/context {:messages [{:content "You are a helpful assistant"
                                                 :role "assistant"}
                                                {:content "Hello there" :role "user"}
                                                {:role :assistant
                                                 :tool_calls [{:function {:arguments "{\"ticker\":\"MSFT\",\"fields\":[\"price\",\"volume\"],\"date\":\"2023-10-10\"}"
                                                                          :name "retrieve_latest_stock_data"}
                                                               :id "call_frPVnoe8ruDicw50T8sLHki7"
                                                               :type :function}]}]}}
        (= out-frame tool-write-frame) => true

        (:frame/type out-frame) => :frame.llm/context
        (:frame/data out-frame) => {:messages [{:content "You are a helpful assistant" :role "assistant"}
                                               {:content "Hello there" :role "user"}
                                               {:role :assistant
                                                :tool_calls [{:function {:arguments "{\"ticker\":\"MSFT\",\"fields\":[\"price\",\"volume\"],\"date\":\"2023-10-10\"}"
                                                                         :name "retrieve_latest_stock_data"}
                                                              :id "call_frPVnoe8ruDicw50T8sLHki7"
                                                              :type :function}]}]}))
    (fact "Handles tool calls with no arguments"
          (let [final-state (reduce (fn [current-state frame]
                                      (let [[next-state] (sut/assistant-aggregator-transform
                                                           current-state
                                                           nil
                                                           frame)]
                                        next-state))
                              sstate
                              (map chunk->frame mock/mock-tool-call-response-single-argument))
                ;; Final state after end frame
                [next-state {:keys [out tool-write]}] (sut/assistant-aggregator-transform
                                                        final-state
                                                        nil
                                                        (frame/llm-full-response-end true))
                out-frame (first out)
                tool-write-frame (first tool-write)]
            next-state => {:content-aggregation nil
                           :function-arguments nil
                           :function-name nil
                           :tool-call-id nil
                           :llm/context {:messages [{:content "You are a helpful assistant"
                                                     :role "assistant"}
                                                    {:content "Hello there" :role "user"}
                                                    {:role :assistant
                                                     :tool_calls [{:function {:arguments "{}"
                                                                              :name "end_call"}
                                                                   :id "call_J9MSffmnxdPj8r28tNzCO8qj"
                                                                   :type :function}]}]}}
            (= out-frame tool-write-frame) => true

            (:frame/type out-frame) => :frame.llm/context
            (:frame/data out-frame) => {:messages [{:content "You are a helpful assistant" :role "assistant"}
                                                   {:content "Hello there" :role "user"}
                                                   {:role :assistant
                                                    :tool_calls [{:function {:arguments "{}"
                                                                             :name "end_call"}
                                                                  :id "call_J9MSffmnxdPj8r28tNzCO8qj"
                                                                  :type :function}]}]}))
    (fact
      "Handles system-config-change frames"
      (let [nc {:messages [{:role :system
                            :content "Your context was just updated"}]}]
        (sut/assistant-aggregator-transform
          sstate :sys-in
          (frame/system-config-change
            {:llm/context nc})) => [(assoc sstate :llm/context nc)]))))

(facts
  "About the tool calling loop"
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

        {::flow/keys [in-ports out-ports]} (sut/assistant-aggregator-init {})
        tool-read (:tool-read in-ports)
        tool-write (:tool-write out-ports)
        async-context (frame/llm-context {:messages async-messages :tools tools})
        sync-context (frame/llm-context {:messages sync-messages :tools tools})]

    (fact
      "Handles sync calls correctly"
      (a/>!! tool-write sync-context)
      (let [res (a/<!! tool-read)]
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
      (a/>!! tool-write async-context)
      (let [res (a/<!! tool-read)]
        (frame/llm-tool-call-result? res) => true
        (:frame/data res) => {:properties {:on-update nil :run-llm? true}
                              :request {:role :assistant
                                        :tool_calls [{:function {:arguments "{}" :name "end_call"}
                                                      :id "call_J9MSffmnxdPj8r28tNzCO8qj"
                                                      :type :function}]}
                              :result {:content [{:text "Call with id test-call-id has ended" :type :text}]
                                       :role :tool
                                       :tool_call_id "call_J9MSffmnxdPj8r28tNzCO8qj"}}))
    (a/close! tool-read)
    (a/close! tool-write)))
