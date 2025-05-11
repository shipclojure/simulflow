(ns simulflow.transport-test
  (:require
   [midje.sweet :as midje :refer [fact facts]]
   [simulflow.frame :as frame]
   [simulflow.transport :as sut]
   [simulflow.utils.core :as u]))

(def test-config-change-frame (frame/system-config-change {:llm/context {:messages [{:role :system :content "You are a cool llm"}]}}))
(defn twilio-start-handle-event [event]
  (cond
    (= (:event event) "start")
    {:sys-out [test-config-change-frame]}
    :else {}))

(facts
  "about twilio transport in"
  (fact
    "Transport in calls twilio/handle-event if it is provided"
    (let [state {:twilio/handle-event twilio-start-handle-event}]
      (sut/twilio-transport-in-transform
        state
        :twilio-in
        (u/json-str {:event "start"})) => [state {:sys-out [test-config-change-frame]}]
      (fact
        "Merges frames in the same array if more are generated"
        (let [[new-state {:keys [sys-out]}] (sut/twilio-transport-in-transform
                                              state
                                              :twilio-in
                                              (u/json-str {:event "start"
                                                           :streamSid "hello"}))]
          new-state => state
          (first sys-out) => test-config-change-frame
          (frame/system-config-change? (last sys-out)) => true
          (:twilio/stream-sid (:frame/data (last sys-out))) => "hello")))))
