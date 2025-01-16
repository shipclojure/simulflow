(ns voice-fn.mock-data
  (:require
   [midje.sweet :as midje]))

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
