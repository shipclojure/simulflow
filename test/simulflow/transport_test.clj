(ns simulflow.transport-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.frame :as frame]
   [simulflow.transport :as sut]
   [simulflow.utils.core :as u]))

(def test-config-change-frame (frame/system-config-change {:llm/context {:messages [{:role :system :content "You are a cool llm"}]}}))

(defn twilio-start-handle-event [event]
  (cond
    (= (:event event) "start")
    {:sys-out [test-config-change-frame]}
    :else {}))

(deftest twilio-transport-in-test
  (testing "twilio transport in"
    (testing "Transport in calls twilio/handle-event if it is provided"
      (let [state {:twilio/handle-event twilio-start-handle-event}]
        (is (= [state {:sys-out [test-config-change-frame]}]
               (sut/twilio-transport-in-transform
                state
                :twilio-in
                (u/json-str {:event "start"}))))

        (testing "Merges frames in the same array if more are generated"
          (let [[new-state {:keys [sys-out]}] (sut/twilio-transport-in-transform
                                               state
                                               :twilio-in
                                               (u/json-str {:event "start"
                                                            :streamSid "hello"}))]
            (is (= state new-state))
            (is (= test-config-change-frame (first sys-out)))
            (is (frame/system-config-change? (last sys-out)))
            (is (= "hello" (:twilio/stream-sid (:frame/data (last sys-out)))))))))))
