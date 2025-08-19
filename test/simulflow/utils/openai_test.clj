(ns simulflow.utils.openai-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.frame :as frame]
   [simulflow.utils.core :as u]
   [simulflow.utils.openai :as uai]))

(def test-api-key "test-api-key-miiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiin")

(deftest transform-llm-processor-test
  (testing "handles llm-context message on input port"
    (let [state {:llm/model "gpt-4o-mini" :openai/api-key test-api-key :api/completions-url "https://api.openai.com/v1/chat/completions"}
          ai-messages [{:role "system" :content "You are a helpful assistant"} {:role "user" :content "Hello, do you hear me?"}]
          ai-context {:messages ai-messages :tools []}
          context-frame (frame/llm-context ai-context)
          [new-state output] (uai/transform-llm-processor state :in context-frame :openai/api-key :api/completions-url)]
      (is (= new-state state))
      (testing "Sends appropriate llm completion request based on the new user context"
        (is (contains? output ::uai/llm-write))
        (is (= (count (::uai/llm-write output)) 1))
        (let [command (first (::uai/llm-write output))
              messages (:messages (u/parse-if-json (get-in command [:command/data :body])))]
          (is (= command #:command{:kind :command/sse-request
                                   :data {:url "https://api.openai.com/v1/chat/completions"
                                          :method :post
                                          :body (u/json-str {:messages ai-messages :stream true, :model (:llm/model state)})
                                          :headers {"Authorization" (str "Bearer " test-api-key)
                                                    "Content-Type" "application/json"}}}))
          (is (= messages (:messages ai-context)))))
      (testing "Marks the start of the llm completion request to the pipeline"
        (is (contains? output :out))
        (is (= (count (:out output)) 1))
        (is (frame/llm-full-response-start? (first (:out output)))))))

  (testing "handles unknown input port gracefully"
    (let [[state output] (uai/transform-llm-processor {} :unknown-port "message" :openai/api-key :api/completions-url)]
      (is (= state {}))
      (is (= output {}))))

  (testing "handles non-llm-context message on input port"
    (let [other-frame (frame/system-start true)
          [state output] (uai/transform-llm-processor {} :in other-frame :openai/api-key :api/completions-url)]
      (is (= state {}))
      (is (= output {}))))

  (testing "streaming LLM response handling from ::llm-read port"
    (testing "handles :done message - should emit llm-full-response-end"
      (let [[state output] (uai/transform-llm-processor {} ::uai/llm-read :done :openai/api-key :api/completions-url)]
        (is (= state {}))
        (is (contains? output :out))
        (is (= (count (:out output)) 1))
        (is (frame/llm-full-response-end? (first (:out output))))))

    (testing "handles text content chunks - should emit llm-text-chunk frames"
      (let [text-chunk {:choices [{:delta {:content "Hello"}}]}
            [state output] (uai/transform-llm-processor {} ::uai/llm-read text-chunk :openai/api-key :api/completions-url)]
        (is (= state {}))
        (is (contains? output :out))
        (is (= (count (:out output)) 1))
        (is (frame/llm-text-chunk? (first (:out output))))
        (is (= (:frame/data (first (:out output))) "Hello"))))

    (testing "handles tool call chunks - should emit llm-tool-call-chunk frames"
      (let [tool-call-data {:index 0
                            :type "function"
                            :function {:name "get_weather" :arguments "{\"city\": \"New York\"}"}}
            tool-chunk {:choices [{:delta {:tool_calls [tool-call-data]}}]}
            [state output] (uai/transform-llm-processor {} ::uai/llm-read tool-chunk :openai/api-key :api/completions-url)]
        (is (= state {}))
        (is (contains? output :out))
        (is (= (count (:out output)) 1))
        (is (frame/llm-tool-call-chunk? (first (:out output))))
        (is (= (:frame/data (first (:out output))) tool-call-data))))

    (testing "handles empty delta - should return nil output"
      (let [empty-chunk {:choices [{:delta {}}]}
            [_state result] (uai/transform-llm-processor {} ::uai/llm-read empty-chunk :openai/api-key :api/completions-url)]
        (is (nil? result))))

    (testing "handles chunk with no content or tool_calls - should return nil output"
      (let [no-content-chunk {:choices [{:delta {:role "assistant"}}]}
            [state result] (uai/transform-llm-processor {} ::uai/llm-read no-content-chunk :openai/api-key :api/completions-url)]
        (is (= state {}))
        (is (nil? result))))

    (testing "handles multiple tool calls - should emit first tool call"
      (let [tool-call-1 {:index 0 :type "function" :function {:name "weather" :arguments "{}"}}
            tool-call-2 {:index 1 :type "function" :function {:name "time" :arguments "{}"}}
            multi-tool-chunk {:choices [{:delta {:tool_calls [tool-call-1 tool-call-2]}}]}
            [state output] (uai/transform-llm-processor {} ::uai/llm-read multi-tool-chunk :openai/api-key :api/completions-url)]
        (is (= state {}))
        (is (contains? output :out))
        (is (= (count (:out output)) 1))
        (is (frame/llm-tool-call-chunk? (first (:out output))))
        (is (= (:frame/data (first (:out output))) tool-call-1))))

    (testing "handles chunk with both content and tool_calls - should prioritize tool_calls"
      (let [tool-call-data {:index 0 :type "function" :function {:name "test" :arguments "{}"}}
            mixed-chunk {:choices [{:delta {:content "Hello" :tool_calls [tool-call-data]}}]}
            [state output] (uai/transform-llm-processor {} ::uai/llm-read mixed-chunk :openai/api-key :api/completions-url)]
        (is (= state {}))
        (is (contains? output :out))
        (is (= (count (:out output)) 1))
        (is (frame/llm-tool-call-chunk? (first (:out output))))
        (is (= (:frame/data (first (:out output))) tool-call-data))))

    (testing "handles streaming text completion sequence"
      (let [chunks [{:choices [{:delta {:content "Hello"}}]}
                    {:choices [{:delta {:content " "}}]}
                    {:choices [{:delta {:content "world"}}]}
                    {:choices [{:delta {:content "!"}}]}
                    :done]
            results (map #(uai/transform-llm-processor {} ::uai/llm-read % :openai/api-key :api/completions-url) chunks)
            valid-results (remove nil? results)]
        (is (= (count valid-results) 5)) ; 4 content chunks + 1 done

        ; Check content chunks
        (doseq [i (range 4)]
          (let [[state output] (nth valid-results i)]
            (is (= state {}))
            (is (frame/llm-text-chunk? (first (:out output))))))

        ; Check done chunk
        (let [[state output] (last valid-results)]
          (is (= state {}))
          (is (frame/llm-full-response-end? (first (:out output)))))))

    (testing "handles streaming tool call sequence"
      (let [chunks [{:choices [{:delta {:tool_calls [{:index 0 :type "function" :function {:name "get_weather" :arguments ""}}]}}]}
                    {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "{\""}}]}}]}
                    {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "city"}}]}}]}
                    {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "\":\""}}]}}]}
                    {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "NYC"}}]}}]}
                    {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "\"}"}}]}}]}
                    :done]
            results (map #(uai/transform-llm-processor {} ::uai/llm-read % :openai/api-key :api/completions-url) chunks)
            valid-results (remove nil? results)]
        (is (= (count valid-results) 7)) ; 6 tool call chunks + 1 done

        ; Check tool call chunks
        (doseq [i (range 6)]
          (let [[state output] (nth valid-results i)]
            (is (= state {}))
            (is (frame/llm-tool-call-chunk? (first (:out output))))))

        ; Check done chunk
        (let [[state output] (last valid-results)]
          (is (= state {}))
          (is (frame/llm-full-response-end? (first (:out output)))))))

    (testing "handles malformed chunks gracefully"
      (testing "chunk with no choices"
        (let [malformed-chunk {}
              [state result] (uai/transform-llm-processor {} ::uai/llm-read malformed-chunk :openai/api-key :api/completions-url)]
          (is (= state {}))
          (is (nil? result))))

      (testing "chunk with empty choices"
        (let [malformed-chunk {:choices []}
              [state result] (uai/transform-llm-processor {} ::uai/llm-read malformed-chunk :openai/api-key :api/completions-url)]
          (is (= state {}))
          (is (nil? result))))

      (testing "chunk with choice but no delta"
        (let [malformed-chunk {:choices [{}]}
              [state result] (uai/transform-llm-processor {} ::uai/llm-read malformed-chunk :openai/api-key :api/completions-url)]
          (is (= state {}))
          (is (nil? result))))

      (testing "chunk with nil content"
        (let [nil-content-chunk {:choices [{:delta {:content nil}}]}
              [state result] (uai/transform-llm-processor {} ::uai/llm-read nil-content-chunk :openai/api-key :api/completions-url)]
          (is (= state {}))
          (is (nil? result))))

      (testing "chunk with empty string content - should emit empty text chunk"
        (let [empty-content-chunk {:choices [{:delta {:content ""}}]}
              [state output] (uai/transform-llm-processor {} ::uai/llm-read empty-content-chunk :openai/api-key :api/completions-url)]
          (is (= state {}))
          (is (contains? output :out))
          (is (= (count (:out output)) 1))
          (is (frame/llm-text-chunk? (first (:out output))))
          (is (= (:frame/data (first (:out output))) "")))))

    (testing "handles tool call responses from mock-like data"
      (testing "basic tool call chunk"
        (let [tool-call-chunk {:choices [{:delta {:tool_calls [{:index 0
                                                                :type "function"
                                                                :function {:arguments "" :name "retrieve_latest_stock_data"}
                                                                :id "call_frPVnoe8ruDicw50T8sLHki7"}]}}]}
              [state output] (uai/transform-llm-processor {} ::uai/llm-read tool-call-chunk :openai/api-key :api/completions-url)]
          (is (= state {}))
          (is (contains? output :out))
          (is (= (count (:out output)) 1))
          (is (frame/llm-tool-call-chunk? (first (:out output))))
          (let [tool-call-data (:frame/data (first (:out output)))]
            (is (= (:index tool-call-data) 0))
            (is (= (:type tool-call-data) "function"))
            (is (= (get-in tool-call-data [:function :name]) "retrieve_latest_stock_data"))
            (is (= (:id tool-call-data) "call_frPVnoe8ruDicw50T8sLHki7")))))

      (testing "finish reason chunk with empty delta"
        (let [finish-chunk {:choices [{:finish_reason "tool_calls" :delta {}}]}
              [state result] (uai/transform-llm-processor {} ::uai/llm-read finish-chunk :openai/api-key :api/completions-url)]
          (is (= state {}))
          (is (nil? result))))

      (testing "streaming tool call arguments simulation"
        (let [argument-chunks [{:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "{\""}}]}}]}
                               {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "ticker"}}]}}]}
                               {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "\":\""}}]}}]}
                               {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "MSFT"}}]}}]}
                               {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "\"}"}}]}}]}]
              results (map #(uai/transform-llm-processor {} ::uai/llm-read % :openai/api-key :api/completions-url) argument-chunks)
              valid-results (remove nil? results)]
          (is (= (count valid-results) 5))
          (doseq [[state output] valid-results]
            (is (= state {}))
            (is (= (count (:out output)) 1))
            (is (frame/llm-tool-call-chunk? (first (:out output))))))))))

(deftest transition-llm-processor-test
  (testing "closes channels on stop transition"
    ;; This test would need mock channels to verify closure
    ;; For now, just test that the function exists and doesn't throw
    (let [mock-state {:clojure.core.async.flow/in-ports {} :clojure.core.async.flow/out-ports {}}]
      (is (= (uai/transition-llm-processor mock-state :clojure.core.async.flow/stop) mock-state))))

  (testing "ignores non-stop transitions"
    (let [mock-state {:clojure.core.async.flow/in-ports {} :clojure.core.async.flow/out-ports {}}]
      (is (= (uai/transition-llm-processor mock-state :clojure.core.async.flow/start) mock-state)))))

(deftest transform-handle-llm-response-test
  (testing "handles :done message"
    (let [[state output] (uai/transform-handle-llm-response {} :done)]
      (is (= state {}))
      (is (contains? output :out))
      (is (frame/llm-full-response-end? (first (:out output))))))
  
  (testing "handles text content"
    (let [chunk {:choices [{:delta {:content "Hello"}}]}
          [state output] (uai/transform-handle-llm-response {} chunk)]
      (is (= state {}))
      (is (contains? output :out))
      (is (frame/llm-text-chunk? (first (:out output))))
      (is (= (:frame/data (first (:out output))) "Hello"))))
  
  (testing "handles tool calls"
    (let [tool-call {:index 0 :type "function" :function {:name "test"}}
          chunk {:choices [{:delta {:tool_calls [tool-call]}}]}
          [state output] (uai/transform-handle-llm-response {} chunk)]
      (is (= state {}))
      (is (contains? output :out))
      (is (frame/llm-tool-call-chunk? (first (:out output))))
      (is (= (:frame/data (first (:out output))) tool-call))))
  
  (testing "handles empty delta"
    (let [chunk {:choices [{:delta {}}]}
          [state output] (uai/transform-handle-llm-response {} chunk)]
      (is (= state {}))
      (is (nil? output)))))