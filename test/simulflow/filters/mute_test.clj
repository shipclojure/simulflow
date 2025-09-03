(ns simulflow.filters.mute-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.filters.mute :as mute]
   [simulflow.frame :as frame]))

(deftest transform-tool-call-strategy-test
  (testing "tool-call strategy mutes on tool call request"
    (let [state {:mute/strategies #{:mute.strategy/tool-call}}
          tool-call-msg (frame/llm-tool-call-request {:role :assistant
                                                      :tool_calls [{:id "test"
                                                                    :type :function
                                                                    :function {:name "test-fn" :arguments "{}"}}]})
          [new-state output] (mute/transform state :in tool-call-msg)]
      (is (true? (::mute/muted? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/mute-input-start? (first (:sys-out output))))))

  (testing "tool-call strategy unmutes on tool call result when muted"
    (let [state {:mute/strategies #{:mute.strategy/tool-call} ::mute/muted? true}
          tool-result-msg (frame/llm-tool-call-result {:request {:role :assistant
                                                                 :tool_calls [{:id "test"
                                                                               :type :function
                                                                               :function {:name "test-fn" :arguments "{}"}}]}
                                                       :result {:role :tool
                                                                :content "Tool executed successfully"
                                                                :tool_call_id "test"}})
          [new-state output] (mute/transform state :in tool-result-msg)]
      (is (= false (::mute/muted? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/mute-input-stop? (first (:sys-out output))))))

  (testing "tool-call strategy ignores non-tool frames"
    (let [state {:mute/strategies #{:mute.strategy/tool-call}}
          regular-msg (frame/transcription "hello")
          [new-state output] (mute/transform state :in regular-msg)]
      (is (= state new-state))
      (is (empty? output))))

  (testing "no mute when tool-call strategy not enabled"
    (let [state {:mute/strategies #{:mute.strategy/bot-speech}}
          tool-call-msg (frame/llm-tool-call-request {:role :assistant
                                                      :tool_calls [{:id "test"
                                                                    :type :function
                                                                    :function {:name "test-fn" :arguments "{}"}}]})
          [new-state output] (mute/transform state :in tool-call-msg)]
      (is (= state new-state))
      (is (empty? output))))

  (testing "tool-call strategy does not mute when already muted"
    (let [state {:mute/strategies #{:mute.strategy/tool-call} ::mute/muted? true}
          tool-call-msg (frame/llm-tool-call-request {:role :assistant
                                                      :tool_calls [{:id "test"
                                                                    :type :function
                                                                    :function {:name "test-fn" :arguments "{}"}}]})
          [new-state output] (mute/transform state :in tool-call-msg)]
      (is (= state new-state))
      (is (empty? output))))

  (testing "tool-call strategy does not unmute when not muted"
    (let [state {:mute/strategies #{:mute.strategy/tool-call} ::mute/muted? false}
          tool-result-msg (frame/llm-tool-call-result {:request {:role :assistant
                                                                 :tool_calls [{:id "test"
                                                                               :type :function
                                                                               :function {:name "test-fn" :arguments "{}"}}]}
                                                       :result {:role :tool
                                                                :content "Tool executed successfully"
                                                                :tool_call_id "test"}})
          [new-state output] (mute/transform state :in tool-result-msg)]
      (is (= state new-state))
      (is (empty? output)))))

(deftest transform-bot-speech-strategy-test
  (testing "bot-speech strategy mutes on bot speech start"
    (let [state {:mute/strategies #{:mute.strategy/bot-speech}}
          bot-start-msg (frame/bot-speech-start true)
          [new-state output] (mute/transform state :in bot-start-msg)]
      (is (= true (::mute/muted? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/mute-input-start? (first (:sys-out output))))))

  (testing "bot-speech strategy unmutes on bot speech stop when muted"
    (let [state {:mute/strategies #{:mute.strategy/bot-speech} ::mute/muted? true}
          bot-stop-msg (frame/bot-speech-stop true)
          [new-state output] (mute/transform state :in bot-stop-msg)]
      (is (= false (::mute/muted? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/mute-input-stop? (first (:sys-out output))))))

  (testing "no mute when bot-speech strategy not enabled"
    (let [state {:mute/strategies #{:mute.strategy/tool-call}}
          bot-start-msg (frame/bot-speech-start true)
          [new-state output] (mute/transform state :in bot-start-msg)]
      (is (= state new-state))
      (is (empty? output))))

  (testing "bot-speech strategy does not mute when already muted"
    (let [state {:mute/strategies #{:mute.strategy/bot-speech} ::mute/muted? true}
          bot-start-msg (frame/bot-speech-start true)
          [new-state output] (mute/transform state :in bot-start-msg)]
      (is (= state new-state))
      (is (empty? output))))

  (testing "bot-speech strategy does not unmute when not muted"
    (let [state {:mute/strategies #{:mute.strategy/bot-speech} ::mute/muted? false}
          bot-stop-msg (frame/bot-speech-stop true)
          [new-state output] (mute/transform state :in bot-stop-msg)]
      (is (= state new-state))
      (is (empty? output)))))

(deftest transform-first-speech-strategy-test
  (testing "first-speech strategy mutes only on first bot speech"
    (let [state {:mute/strategies #{:mute.strategy/first-speech}}
          bot-start-msg (frame/bot-speech-start true)
          [new-state output] (mute/transform state :in bot-start-msg)]
      (is (= true (::mute/first-speech-started? new-state)))
      (is (= true (::mute/muted? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/mute-input-start? (first (:sys-out output))))))

  (testing "first-speech strategy does not mute on second bot speech start"
    (let [state {:mute/strategies #{:mute.strategy/first-speech}
                 ::mute/first-speech-started? true}
          bot-start-msg (frame/bot-speech-start true)
          [new-state output] (mute/transform state :in bot-start-msg)]
      (is (= state new-state))
      (is (empty? output))))

  (testing "first-speech strategy unmutes on first bot speech stop when muted"
    (let [state {:mute/strategies #{:mute.strategy/first-speech}
                 ::mute/first-speech-started? true
                 ::mute/muted? true}
          bot-stop-msg (frame/bot-speech-stop true)
          [new-state output] (mute/transform state :in bot-stop-msg)]
      (is (= true (::mute/first-speech-ended? new-state)))
      (is (= false (::mute/muted? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/mute-input-stop? (first (:sys-out output))))))

  (testing "first-speech strategy does not unmute on subsequent bot speech stops"
    (let [state {:mute/strategies #{:mute.strategy/first-speech}
                 ::mute/first-speech-started? true
                 ::mute/first-speech-ended? true
                 ::mute/muted? false}
          bot-stop-msg (frame/bot-speech-stop true)
          [new-state output] (mute/transform state :in bot-stop-msg)]
      (is (= state new-state))
      (is (empty? output)))))

(deftest transform-combined-strategies-test
  (testing "first-speech and bot-speech strategies both trigger on first speech"
    (let [state {:mute/strategies #{:mute.strategy/first-speech :mute.strategy/bot-speech}}
          bot-start-msg (frame/bot-speech-start true)
          [new-state output] (mute/transform state :in bot-start-msg)]
      (is (= true (::mute/first-speech-started? new-state)))
      (is (= true (::mute/muted? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/mute-input-start? (first (:sys-out output))))))

  (testing "only bot-speech strategy triggers on second speech when first-speech already ended"
    (let [state {:mute/strategies #{:mute.strategy/first-speech :mute.strategy/bot-speech}
                 ::mute/first-speech-started? true
                 ::mute/first-speech-ended? true}
          bot-start-msg (frame/bot-speech-start true)
          [new-state output] (mute/transform state :in bot-start-msg)]
      (is (= true (::mute/muted? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/mute-input-start? (first (:sys-out output))))))

  (testing "all strategies can work together"
    (let [state {:mute/strategies #{:mute.strategy/first-speech :mute.strategy/bot-speech :mute.strategy/tool-call}}
          tool-call-msg (frame/llm-tool-call-request {:role :assistant
                                                      :tool_calls [{:id "test"
                                                                    :type :function
                                                                    :function {:name "test-fn" :arguments "{}"}}]})
          [new-state output] (mute/transform state :in tool-call-msg)]
      (is (= true (::mute/muted? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/mute-input-start? (first (:sys-out output)))))))

(deftest transform-no-strategies-test
  (testing "no mute frames when no strategies enabled"
    (let [state {:mute/strategies #{}}
          test-frames [(frame/bot-speech-start true)
                       (frame/bot-speech-stop true)
                       (frame/llm-tool-call-request {:role :assistant
                                                     :tool_calls [{:id "test"
                                                                   :type :function
                                                                   :function {:name "test-fn" :arguments "{}"}}]})
                       (frame/llm-tool-call-result {:request {:role :assistant
                                                              :tool_calls [{:id "test"
                                                                            :type :function
                                                                            :function {:name "test-fn" :arguments "{}"}}]}
                                                    :result {:role :tool
                                                             :content "Tool executed successfully"
                                                             :tool_call_id "test"}})]]
      (doseq [test-frame test-frames]
        (let [original-state state
              [new-state output] (mute/transform state :in test-frame)]
          (is (= original-state new-state))
          (is (empty? output)))))))

(deftest transform-invalid-frames-test
  (testing "returns unchanged state for unhandled frames"
    (let [state {:mute/strategies #{:mute.strategy/bot-speech :mute.strategy/tool-call :mute.strategy/first-speech}}
          unhandled-frames [(frame/transcription "hello")
                            (frame/user-speech-start true)
                            (frame/user-speech-stop true)
                            (frame/llm-text-chunk "some text")]]
      (doseq [test-frame unhandled-frames]
        (let [original-state state
              [new-state output] (mute/transform state :in test-frame)]
          (is (= original-state new-state))
          (is (empty? output)))))))

(deftest init-test
  (testing "init parses valid config"
    (let [config {:mute/strategies #{:mute.strategy/bot-speech :mute.strategy/tool-call}}
          result (mute/init config)]
      (is (= #{:mute.strategy/bot-speech :mute.strategy/tool-call} (:mute/strategies result)))))

  (testing "init with single strategy"
    (let [config {:mute/strategies #{:mute.strategy/first-speech}}
          result (mute/init config)]
      (is (= #{:mute.strategy/first-speech} (:mute/strategies result)))))

  (testing "init with all strategies"
    (let [config {:mute/strategies #{:mute.strategy/first-speech
                                     :mute.strategy/bot-speech
                                     :mute.strategy/tool-call}}
          result (mute/init config)]
      (is (= 3 (count (:mute/strategies result))))
      (is (contains? (:mute/strategies result) :mute.strategy/first-speech))
      (is (contains? (:mute/strategies result) :mute.strategy/bot-speech))
      (is (contains? (:mute/strategies result) :mute.strategy/tool-call))))

  (testing "init fails with invalid config"
    (is (thrown? Exception (mute/init {})))
    (is (thrown? Exception (mute/init {:mute/strategies #{:invalid-strategy}})))))

(deftest processor-fn-test
  (testing "0-arity returns description"
    (let [desc (mute/processor-fn)]
      (is (contains? desc :ins))
      (is (contains? desc :outs))
      (is (contains? desc :params))
      (is (= "Channel for normal frames" (get-in desc [:ins :in])))
      (is (= "Channel for system frames" (get-in desc [:ins :sys-in])))
      (is (= "Channel for mute-start/stop frames" (get-in desc [:outs :sys-out])))))

  (testing "1-arity calls init"
    (let [config {:mute/strategies #{:mute.strategy/bot-speech}}
          result (mute/processor-fn config)]
      (is (= #{:mute.strategy/bot-speech} (:mute/strategies result)))))

  (testing "2-arity returns state unchanged"
    (let [state {:mute/strategies #{:mute.strategy/tool-call}}
          result (mute/processor-fn state :some-transition)]
      (is (= state result))))

  (testing "3-arity calls transform"
    (let [state {:mute/strategies #{:mute.strategy/bot-speech}}
          frame (frame/bot-speech-start true)
          [new-state output] (mute/processor-fn state :in frame)]
      (is (= true (::mute/muted? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/mute-input-start? (first (:sys-out output)))))))
