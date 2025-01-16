(ns voice-fn.mock-data)

(def stock-trade-retrieve-function
  {:type :function
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
    :strict true}})

(def stock-trade-system-msg
  {:role "system"
   :content [{:type "text"
              :text "Develop a stock trader bot that simulates trading strategies for various stocks based on market data and user-defined parameters.\n\nThe bot should be capable of analyzing stock data, identifying trends, and recommending actions (e.g., buy, sell, hold) based on pre-determined algorithms or user selections. It should also be able to adjust strategies based on feedback or changing market conditions.\n\n# Steps\n\n1. **Data Collection**: Gather real-time or historical stock market data, including price, volume, and other relevant indicators.\n2. **Trend Analysis**: Analyze the collected data to identify patterns or trends that can inform trading decisions.\n3. **Strategy Execution**: Apply trading strategies to generate trade recommendations. These strategies could include moving averages, momentum indicators, or other technical analysis tools.\n4. **Decision Making**: Use the analysis to make decisions on whether to buy, sell, or hold a particular stock.\n5. **Feedback & Adjustment**: Monitor the outcomes of executed strategies and adjust future actions based on performance and user feedback.\n\n# Output Format\n\nOutput should be formatted as a JSON object with the following structure:\n\n```json\n{\n  \"stock_symbol\": \"[Stock Symbol]\",\n  \"action_recommended\": \"[Buy/Sell/Hold]\",\n  \"entry_point\": \"[Suggested Entry Price]\",\n  \"exit_point\": \"[Suggested Exit Price]\",\n  \"analysis_summary\": \"[Brief summary of the analysis leading to the decision]\"\n}\n```\n\n# Examples\n\n**Example Input:**\n- Stock Symbol: AAPL\n- Market Data: Current price, volume, past trends\n\n**Example Output:**\n\n```json\n{\n  \"stock_symbol\": \"AAPL\",\n  \"action_recommended\": \"Buy\",\n  \"entry_point\": \"142.50\",\n  \"exit_point\": \"150.00\",\n  \"analysis_summary\": \"The stock is experiencing an upward trend with increased volume suggesting a bullish momentum\"\n}\n```\n\n**Example Input:**\n- Stock Symbol: GOOGL\n- Market Data: Current price, volume, past trends\n\n**Example Output:**\n\n```json\n{\n  \"stock_symbol\": \"GOOGL\",\n  \"action_recommended\": \"Hold\",\n  \"entry_point\": \"N/A\",\n  \"exit_point\": \"N/A\",\n  \"analysis_summary\": \"The stock is showing mixed signals with stable volume. It is recommended to hold and watch for clear trend development\"\n}\n```\n\n# Notes\n\n- Ensure the bot is capable of handling multiple stocks simultaneously.\n- Consider integrating additional market indicators or news analysis to improve prediction accuracy.\n- User-defined parameters could include risk tolerance, investment period, and preferred market sectors."}]})

(def stock-trade-user-msg
  {:role "user"
   :content [{:type "text"
              :text "What is the current stock for MSFT?"}]})

(def stock-trade-user-msg-deprecated
  {:role "user"
   :content "What is the current stock for MSFT?"})

(def stock-trade-tool-call-request
  {:role "assistant"
   :tool_calls [{:id "call_B9cCUlIAQghTph1wezvBODSe"
                 :type "function"
                 :function {:name "retrieve_latest_stock_data"
                            :arguments "{\"ticker\":\"MSFT\",\"fields\":[\"price\",\"volume\"],\"date\":\"2023-10-15\"}"}}]})

(def stock-trade-tool-call-response
  {:role "tool"
   :content [{:type "text"
              :text "{\n  \"success\": true,\n  \"data\": {\n    \"ticker\": \"MSFT\",\n    \"date\": \"2023-10-15\",\n    \"price\": 320.45,\n    \"volume\": 21150000\n  },\n  \"message\": \"Latest stock data retrieved successfully.\"\n}"}]
   :tool_call_id "call_B9cCUlIAQghTph1wezvBODSe"})

(def stock-trade-assistant-message
  {:role "assistant"
   :content [{:type "text"
              :text "As of October 15, 2023, the latest stock data for Microsoft Corporation (MSFT) is as follows:\n\n- **Price**: $320.45\n- **Volume**: 21,150,000 shares"}]})

(def stock-trade-conversation [stock-trade-system-msg
                               stock-trade-user-msg
                               stock-trade-tool-call-request
                               stock-trade-tool-call-response
                               stock-trade-assistant-message])

(def mock-tool-call-response
  [{:service_tier "default"
    :created 1736923837
    :choices [{:finish_reason nil
               :index 0
               :logprobs nil
               :delta {:role "assistant"
                       :content nil
                       :refusal nil
                       :tool_calls [{:index 0, :type "function", :function {:arguments "", :name "retrieve_latest_stock_data"}, :id "call_frPVnoe8ruDicw50T8sLHki7"}]}}]
    :system_fingerprint "fp_72ed7ab54c"
    :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT"
    :object "chat.completion.chunk"
    :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default"
    :created 1736923837
    :choices [{:finish_reason nil, :index 0
               :logprobs nil
               :delta {:tool_calls [{:index 0, :function {:arguments "{\""}}]}}]
    :system_fingerprint "fp_72ed7ab54c"
    :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT"
    :object "chat.completion.chunk"
    :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "ticker"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "\":\""}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "MS"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "FT"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "\",\""}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "fields"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "\":[\""}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "price"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "\",\""}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "volume"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "\"],"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "\""}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "date"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "\":\""}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "202"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "3"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "-"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "10"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "-"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "10"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default", :created 1736923837, :choices [{:finish_reason nil, :index 0, :logprobs nil, :delta {:tool_calls [{:index 0, :function {:arguments "\"}"}}]}}], :system_fingerprint "fp_72ed7ab54c", :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT", :object "chat.completion.chunk", :model "gpt-4o-mini-2024-07-18"}
   {:service_tier "default"
    :created 1736923837
    :choices [{:finish_reason "tool_calls", :index 0, :logprobs nil, :delta {}}]
    :system_fingerprint "fp_72ed7ab54c"
    :id "chatcmpl-AprWX3D40t4SQZrO5LEdvYYHHXuMT"
    :object "chat.completion.chunk"
    :model "gpt-4o-mini-2024-07-18"}
   :done])
