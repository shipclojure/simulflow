(ns voice-fn.schema-test
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [midje.sweet :refer [fact facts]]
   [voice-fn.schema :as sut]))

;; LLM Messages

(facts "about schema for openai format llm messages"
  (let [system-msg {:role "system"
                    :content [{:type "text"
                               :text "Develop a stock trader bot that simulates trading strategies for various stocks based on market data and user-defined parameters.\n\nThe bot should be capable of analyzing stock data, identifying trends, and recommending actions (e.g., buy, sell, hold) based on pre-determined algorithms or user selections. It should also be able to adjust strategies based on feedback or changing market conditions.\n\n# Steps\n\n1. **Data Collection**: Gather real-time or historical stock market data, including price, volume, and other relevant indicators.\n2. **Trend Analysis**: Analyze the collected data to identify patterns or trends that can inform trading decisions.\n3. **Strategy Execution**: Apply trading strategies to generate trade recommendations. These strategies could include moving averages, momentum indicators, or other technical analysis tools.\n4. **Decision Making**: Use the analysis to make decisions on whether to buy, sell, or hold a particular stock.\n5. **Feedback & Adjustment**: Monitor the outcomes of executed strategies and adjust future actions based on performance and user feedback.\n\n# Output Format\n\nOutput should be formatted as a JSON object with the following structure:\n\n```json\n{\n  \"stock_symbol\": \"[Stock Symbol]\",\n  \"action_recommended\": \"[Buy/Sell/Hold]\",\n  \"entry_point\": \"[Suggested Entry Price]\",\n  \"exit_point\": \"[Suggested Exit Price]\",\n  \"analysis_summary\": \"[Brief summary of the analysis leading to the decision]\"\n}\n```\n\n# Examples\n\n**Example Input:**\n- Stock Symbol: AAPL\n- Market Data: Current price, volume, past trends\n\n**Example Output:**\n\n```json\n{\n  \"stock_symbol\": \"AAPL\",\n  \"action_recommended\": \"Buy\",\n  \"entry_point\": \"142.50\",\n  \"exit_point\": \"150.00\",\n  \"analysis_summary\": \"The stock is experiencing an upward trend with increased volume suggesting a bullish momentum\"\n}\n```\n\n**Example Input:**\n- Stock Symbol: GOOGL\n- Market Data: Current price, volume, past trends\n\n**Example Output:**\n\n```json\n{\n  \"stock_symbol\": \"GOOGL\",\n  \"action_recommended\": \"Hold\",\n  \"entry_point\": \"N/A\",\n  \"exit_point\": \"N/A\",\n  \"analysis_summary\": \"The stock is showing mixed signals with stable volume. It is recommended to hold and watch for clear trend development\"\n}\n```\n\n# Notes\n\n- Ensure the bot is capable of handling multiple stocks simultaneously.\n- Consider integrating additional market indicators or news analysis to improve prediction accuracy.\n- User-defined parameters could include risk tolerance, investment period, and preferred market sectors."}]}
        user-msg {:role "user"
                  :content [{:type "text"
                             :text "What is the current stock for MSFT?"}]}
        user-msg-deprecated {:role "user"
                             :content "What is the current stock for MSFT?"}

        tool-call-msg {:role "assistant"
                       :tool_calls [{:id "call_B9cCUlIAQghTph1wezvBODSe"
                                     :type "function"
                                     :function {:name "retrieve_latest_stock_data"
                                                :arguments "{\"ticker\":\"MSFT\",\"fields\":[\"price\",\"volume\"],\"date\":\"2023-10-15\"}"}}]}
        tool-response-msg  {:role "tool"
                            :content [{:type "text"
                                       :text "{\n  \"success\": true,\n  \"data\": {\n    \"ticker\": \"MSFT\",\n    \"date\": \"2023-10-15\",\n    \"price\": 320.45,\n    \"volume\": 21150000\n  },\n  \"message\": \"Latest stock data retrieved successfully.\"\n}"}]
                            :tool_call_id "call_B9cCUlIAQghTph1wezvBODSe"}
        assistant-msg {:role "assistant"
                       :content [{:type "text"
                                  :text "As of October 15, 2023, the latest stock data for Microsoft Corporation (MSFT) is as follows:\n\n- **Price**: $320.45\n- **Volume**: 21,150,000 shares"}]}
        messages [system-msg
                  user-msg
                  tool-call-msg
                  tool-response-msg
                  assistant-msg]]
    (fact "about user messages"
          (m/validate sut/LLMUserMessage user-msg) => true
          (m/validate sut/LLMUserMessage user-msg-deprecated) => true
          (me/humanize (m/explain sut/LLMUserMessage {:role :user
                                                      :content [{:type :hello
                                                                 :content "hello world"}]}))
          => {:content ["should be a string"]}
          (me/humanize (m/explain sut/LLMUserMessage {:role :user
                                                      :content [{:type "text"
                                                                 :content 123}]}))
          =>  {:content ["should be a string"]})

    (fact "full set of messages is valid"
          (m/validate sut/LLMContextMessages messages) => true)
    (fact "invalid message is called out"
          (me/humanize (m/explain sut/LLMContextMessages (conj messages :invalid)))
          => [nil nil nil nil nil ["invalid type" "invalid type" "invalid type" "invalid type" "invalid type"]])))

;; Tool Definition

(facts "about llm functions parameters"
  (fact "LLMFunctionCallParameterSchema checks if required parameters are defined"

        (let [valid-parameters {:type :object
                                :description "Function to close a Twilio call"
                                :required [:call_sid]
                                :properties {:call_sid {:type :string
                                                        :description "The unique identifier of the call to be closed"}}}
              missing-required (assoc valid-parameters :required [:missing])
              not-missing (assoc-in missing-required [:properties :missing] {:type :number
                                                                             :description "This is a missing property"})]
          (m/validate sut/LLMFunctionCallParameters valid-parameters) => true
          (me/humanize (m/explain sut/LLMFunctionCallParameters missing-required)) => ["Required parameters are not defined"]
          (m/validate sut/LLMFunctionCallParameters not-missing) => true))
  (fact "llms accept array type parameters"
        (m/validate sut/LLMFunctionCallParameters {:type :object
                                                   :required [:ticker :fields :date]
                                                   :properties {:ticker {:type :string
                                                                         :description "Stock ticker symbol for which to retrieve data"}
                                                                :fields {:type :array
                                                                         :description "Fields to retrieve for the stock data"
                                                                         :items {:type :string
                                                                                 :description "Field name to retrieve (e.g 'price' 'volume')"}}
                                                                :date {:type :string
                                                                       :description "Date for which to retrieve stock data in the format 'YYYY-MM-DD'"}}})
        => true))

(fact "LLMFunctionToolDefinition"
      (let [valid-function
            {:type :function
             :function {:name "close_twilio_call"
                        :description "Function to close a twilio call"
                        :parameters {:type :object
                                     :required [:call_sid :reason]
                                     :properties {:call_sid {:type :string
                                                             :description "The unique identifier of the call to be closed"}
                                                  :reason {:type :string
                                                           :description "The reason for closing the call"}}
                                     :additionalProperties false}
                        :strict true}}
            invalid-required (update-in valid-function [:function :parameters :properties] dissoc :reason)
            stocks-function {:type :function
                             :function
                             {:name "retrieve_latest_stock_data"
                              :description "Retrive latest stock data for the current day"
                              :parameters {:type :object
                                           :required [:ticker :fields :date]
                                           :properties {:ticker {:type :string
                                                                 :description "Stock ticker symbol for which to retrieve data"}
                                                        :fields {:type :array
                                                                 :description "Fields to retrieve for the stock data"
                                                                 :items {:type :string
                                                                         :description "Field name to retrieve (e.g 'price' 'volume')"}}
                                                        :date {:type :string
                                                               :description "Date for which to retrieve stock data in the format 'YYYY-MM-DD'"}}
                                           :additionalProperties false}
                              :strict true}}]
        (m/validate sut/LLMFunctionToolDefinition valid-function) => true

        (me/humanize (m/explain sut/LLMFunctionToolDefinition invalid-required)) => {:function {:parameters ["Required parameters are not defined"]}}
        (m/validate sut/LLMFunctionToolDefinition stocks-function) => true))
