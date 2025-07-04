(ns simulflow.processors.openai-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.frame :as frame]
   [simulflow.processors.openai :as openai]
   [simulflow.schema :as schema]
   [simulflow.utils.core :as u]))

(deftest openai-describe-test
  (testing "describe function returns correct structure"
    (let [result (openai/describe)]
      (is (contains? result :ins))
      (is (contains? result :outs))
      (is (contains? result :params))
      (is (contains? result :workload))
      (is (= (:workload result) :io))
      (is (contains? (:ins result) :in))
      (is (contains? (:outs result) :out)))))

(def test-api-key "test-api-key-miiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiin")

(deftest openai-transform-test
  (testing "handles llm-context message on input port"
    (let [state (schema/parse-with-defaults openai/OpenAILLMConfigSchema {:openai/api-key test-api-key})
          ai-messages [{:role "system" :content "You are a helpful assistant"} {:role "user" :content "Hello, do you hear me?"}]
          ai-context {:messages ai-messages :tools []}
          context-frame (frame/llm-context ai-context)
          [new-state output] (openai/transform state :in context-frame)]
      (is (= new-state state))
      (testing "Sends appropriate llm completion request based on the new user context"
        (is (contains? output ::openai/llm-write))
        (is (= (count (::openai/llm-write output)) 1))
        (let [command (first (::openai/llm-write output))
              messages (:messages (u/parse-if-json (get-in command [:command/data :body])))]
          (is (= command #:command{:kind :command/sse-request,
                                   :data {:url "https://api.openai.com/v1/chat/completions",
                                          :method :post,
                                          :body (u/json-str {:messages ai-messages :stream true, :model (:llm/model state)}),
                                          :headers {"Authorization" (str "Bearer " test-api-key)
                                                    "Content-Type" "application/json"}}}))
          (is (= messages (:messages ai-context)))))
      (testing "Marks the start of the llm completion request to the pipeline"
        (is (contains? output :out))
        (is (= (count (:out output)) 1))
        (is (frame/llm-full-response-start? (first (:out output)))))))

  (testing "handles unknown input port gracefully"
    (let [[state output] (openai/transform {} :unknown-port "message")]
      (is (= state {}))
      (is (= output {}))))

  (testing "handles non-llm-context message on input port"
    (let [other-frame (frame/system-start true)
          [state output] (openai/transform {} :in other-frame)]
      (is (= state {}))
      (is (= output {}))))

  (testing "streaming LLM response handling from ::llm-read port"
    (testing "handles :done message - should emit llm-full-response-end"
      (let [[state output] (openai/transform {} ::openai/llm-read :done)]
        (is (= state {}))
        (is (contains? output :out))
        (is (= (count (:out output)) 1))
        (is (frame/llm-full-response-end? (first (:out output))))))

    (testing "handles text content chunks - should emit llm-text-chunk frames"
      (let [text-chunk {:choices [{:delta {:content "Hello"}}]}
            [state output] (openai/transform {} ::openai/llm-read text-chunk)]
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
            [state output] (openai/transform {} ::openai/llm-read tool-chunk)]
        (is (= state {}))
        (is (contains? output :out))
        (is (= (count (:out output)) 1))
        (is (frame/llm-tool-call-chunk? (first (:out output))))
        (is (= (:frame/data (first (:out output))) tool-call-data))))

    (testing "handles empty delta - should return nil output"
      (let [empty-chunk {:choices [{:delta {}}]}
            [_state result] (openai/transform {} ::openai/llm-read empty-chunk)]
        (is (nil? result))))

    (testing "handles chunk with no content or tool_calls - should return nil output"
      (let [no-content-chunk {:choices [{:delta {:role "assistant"}}]}
            [state result] (openai/transform {} ::openai/llm-read no-content-chunk)]
        (is (= state {}))
        (is (nil? result))))

    (testing "handles multiple tool calls - should emit first tool call"
      (let [tool-call-1 {:index 0 :type "function" :function {:name "weather" :arguments "{}"}}
            tool-call-2 {:index 1 :type "function" :function {:name "time" :arguments "{}"}}
            multi-tool-chunk {:choices [{:delta {:tool_calls [tool-call-1 tool-call-2]}}]}
            [state output] (openai/transform {} ::openai/llm-read multi-tool-chunk)]
        (is (= state {}))
        (is (contains? output :out))
        (is (= (count (:out output)) 1))
        (is (frame/llm-tool-call-chunk? (first (:out output))))
        (is (= (:frame/data (first (:out output))) tool-call-1))))

    (testing "handles chunk with both content and tool_calls - should prioritize tool_calls"
      (let [tool-call-data {:index 0 :type "function" :function {:name "test" :arguments "{}"}}
            mixed-chunk {:choices [{:delta {:content "Hello" :tool_calls [tool-call-data]}}]}
            [state output] (openai/transform {} ::openai/llm-read mixed-chunk)]
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
            results (map #(openai/transform {} ::openai/llm-read %) chunks)
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
            results (map #(openai/transform {} ::openai/llm-read %) chunks)
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
              [state result] (openai/transform {} ::openai/llm-read malformed-chunk)]
          (is (= state {}))
          (is (nil? result))))

      (testing "chunk with empty choices"
        (let [malformed-chunk {:choices []}
              [state result] (openai/transform {} ::openai/llm-read malformed-chunk)]
          (is (= state {}))
          (is (nil? result))))

      (testing "chunk with choice but no delta"
        (let [malformed-chunk {:choices [{}]}
              [state result] (openai/transform {} ::openai/llm-read malformed-chunk)]
          (is (= state {}))
          (is (nil? result))))

      (testing "chunk with nil content"
        (let [nil-content-chunk {:choices [{:delta {:content nil}}]}
              [state result] (openai/transform {} ::openai/llm-read nil-content-chunk)]
          (is (= state {}))
          (is (nil? result))))

      (testing "chunk with empty string content - should emit empty text chunk"
        (let [empty-content-chunk {:choices [{:delta {:content ""}}]}
              [state output] (openai/transform {} ::openai/llm-read empty-content-chunk)]
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
              [state output] (openai/transform {} ::openai/llm-read tool-call-chunk)]
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
              [state result] (openai/transform {} ::openai/llm-read finish-chunk)]
          (is (= state {}))
          (is (nil? result))))

      (testing "streaming tool call arguments simulation"
        (let [argument-chunks [{:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "{\""}}]}}]}
                               {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "ticker"}}]}}]}
                               {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "\":\""}}]}}]}
                               {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "MSFT"}}]}}]}
                               {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "\"}"}}]}}]}]
              results (map #(openai/transform {} ::openai/llm-read %) argument-chunks)
              valid-results (remove nil? results)]
          (is (= (count valid-results) 5))
          (doseq [[state output] valid-results]
            (is (= state {}))
            (is (= (count (:out output)) 1))
            (is (frame/llm-tool-call-chunk? (first (:out output))))))))))

(deftest openai-transition-test
  (testing "closes channels on stop transition"
    ;; This test would need mock channels to verify closure
    ;; For now, just test that the function exists and doesn't throw
    (let [mock-state {:clojure.core.async.flow/in-ports {} :clojure.core.async.flow/out-ports {}}]
      (is (= (openai/transition mock-state :clojure.core.async.flow/stop) mock-state))))

  (testing "ignores non-stop transitions"
    (let [mock-state {:clojure.core.async.flow/in-ports {} :clojure.core.async.flow/out-ports {}}]
      (is (= (openai/transition mock-state :clojure.core.async.flow/start) mock-state)))))

(deftest openai-multi-arity-fn-test
  (testing "0-arity returns describe"
    (let [result (openai/openai-llm-fn)]
      (is (contains? result :ins))
      (is (contains? result :outs))
      (is (contains? result :params))))

  (testing "2-arity delegates to transition"
    (let [mock-state {:clojure.core.async.flow/in-ports {} :clojure.core.async.flow/out-ports {}}]
      (is (= mock-state (openai/openai-llm-fn mock-state :clojure.core.async.flow/stop)))))

  (testing "3-arity delegates to transform"
    (let [context-frame (frame/llm-context {:messages [{:role :system :content "You are helpful"}] :tools []})
          state {:llm/model "gpt-4o-mini" :openai/api-key "test-key"}
          [_ output] (openai/openai-llm-fn state :in context-frame)]
      (is (contains? output ::openai/llm-write))
      (is (contains? output :out))
      (is (= (:command/kind (first (::openai/llm-write output))) :command/sse-request)))))
