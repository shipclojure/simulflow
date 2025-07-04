(ns simulflow.processors.openai-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.frame :as frame]
   [simulflow.processors.openai :as openai]))

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

(deftest openai-transform-test
  (testing "handles llm-read input port"
    (let [msg (frame/llm-text-chunk "Hello")
          [state output] (openai/transform {} :llm-read msg)]
      (is (= state {}))
      (is (= output {:out [msg]}))))

  (testing "handles llm-context message on input port"
    (let [context-frame (frame/llm-context {:messages [{:role :system :content "You are a helpful assistant"}] :tools []})
          [state output] (openai/transform {} :in context-frame)]
      (is (= state {}))
      (is (contains? output :llm-write))
      (is (= (:llm-write output) [context-frame]))
      (is (contains? output :out))
      (is (= (count (:out output)) 1))
      (is (frame/llm-full-response-start? (first (:out output))))))

  (testing "handles unknown input port gracefully"
    (let [[state output] (openai/transform {} :unknown-port "message")]
      (is (= state {}))
      (is (= output {}))))

  (testing "handles non-llm-context message on input port"
    (let [other-frame (frame/system-start true)
          [state output] (openai/transform {} :in other-frame)]
      (is (= state {}))
      (is (= output {})))))

(deftest openai-transition-test
  (testing "closes channels on stop transition"
    ;; This test would need mock channels to verify closure
    ;; For now, just test that the function exists and doesn't throw
    (let [mock-state {:clojure.core.async.flow/in-ports {} :clojure.core.async.flow/out-ports {}}]
      (is (nil? (openai/transition mock-state :clojure.core.async.flow/stop)))))

  (testing "ignores non-stop transitions"
    (let [mock-state {:clojure.core.async.flow/in-ports {} :clojure.core.async.flow/out-ports {}}]
      (is (nil? (openai/transition mock-state :clojure.core.async.flow/start))))))

(deftest openai-multi-arity-fn-test
  (testing "0-arity returns describe"
    (let [result (openai/openai-llm-fn)]
      (is (contains? result :ins))
      (is (contains? result :outs))
      (is (contains? result :params))))

  (testing "2-arity delegates to transition"
    (let [mock-state {:clojure.core.async.flow/in-ports {} :clojure.core.async.flow/out-ports {}}]
      (is (nil? (openai/openai-llm-fn mock-state :clojure.core.async.flow/stop)))))

  (testing "3-arity delegates to transform"
    (let [msg (frame/llm-text-chunk "test")
          [state output] (openai/openai-llm-fn {} :llm-read msg)]
      (is (= state {}))
      (is (= output {:out [msg]})))))
