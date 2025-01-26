(ns voice-fn.processors.openai-test
  (:require
   [clojure.core.async :as a]
   [midje.sweet :refer [fact facts]]
   [voice-fn.frame :as frame]
   [voice-fn.mock-data :as mock]
   [voice-fn.processors.openai :as sut]))

(facts
  "About the openai llm"

  (fact
    "tool-call-request aggregation is correct"
    (with-redefs [sut/stream-openai-chat-completion (fn [_params]
                                                      (let [stream-ch (a/chan 1024)]
                                                        (a/onto-chan! stream-ch mock/mock-tool-call-response)
                                                        stream-ch))]
      (let [out (a/chan 1024)
            context-frame (frame/llm-context {:messages [{:role :user :content "hello"}]})
            state {:llm/model "gpt-4o-mini"
                   :openai/api-key "sk-xxxxxxxxxxxxxxxxxxxxxxxxxx"}]
        (sut/flow-do-completion! state out context-frame)

        (let [llm-request-start-frame (a/<!! out)
              tool-call-request (a/<!! out)
              response-end (a/<!! out)]
          (frame/llm-full-response-start? llm-request-start-frame) => true
          (frame/llm-tools-call-request? tool-call-request) => true
          (:frame/data tool-call-request)  => {:arguments {:date "2023-10-10" :fields ["price" "volume"] :ticker "MSFT"}
                                               :function-name "retrieve_latest_stock_data"
                                               :tool-call-id "call_frPVnoe8ruDicw50T8sLHki7"}
          (frame/llm-full-response-end? response-end) => true)))))
