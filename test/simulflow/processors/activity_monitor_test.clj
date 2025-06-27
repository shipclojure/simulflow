(ns simulflow.processors.activity-monitor-test
  (:require [clojure.test :refer [deftest is testing]]
            [simulflow.frame :as frame]
            [simulflow.processors.activity-monitor :as activity-monitor]))

(def current-time #inst "2025-06-27T06:13:35.236-00:00")

;; When a frame is received
;; 1. If that frame is VAD for either bot or user
;; 1.1 If user speaking frame, set user speaking true and send frame to timer process to reset activity timer
;; 1.2 If user stopped speaking, set user speaking false and send frame to timer process to reset activity timer
;; 2.1 If bot speaking frame, set bot speaking true and send frame to timer process to reset activity timer
;; 2.2 If bot stopped speaking, set bot speaking false and send frame to timer process to reset activity timer
;; 3. If the tiemout for activity has passed and no VAD frame came AND nobody is speaking
;; 3.1 Increment the activity ping message count
;; 3.2 If the activity ping message count is bigger or equal to max pings send end conversation message
;; 4.1 If the timeout for activity has passed and no VAD frame came, but the user is speaking, don't send inactivity ping
;; 4.2 If the timeout for activity has passed and no VAD frame came, but the bot is speaking, don't send inactivity ping

(deftest activity-monitor-transform
  (testing "If user speaking frame, set user speaking true and send frame to timer process to reset activity timer"
    (let [speech-start (frame/user-speech-start true {:timestamp current-time})]
      (is (= (activity-monitor/transform
              {::activity-monitor/user-speaking? false}
              :in
              speech-start)
             [{::activity-monitor/user-speaking? true} {:timer-process-in [speech-start]}]))))
  (testing "If user stopped speaking, set user speaking false and send frame to timer process to reset activity timer"
    (let [speech-stop (frame/user-speech-stop true {:timestamp current-time})]
      (is (= (activity-monitor/transform
              {::activity-monitor/user-speaking? true}
              :in
              speech-stop)
             [{::activity-monitor/user-speaking? false} {:timer-process-in [speech-stop]}]))))

  (testing "If bot speaking frame, set bot speaking true and send frame to timer process to reset activity timer"
    (let [speech-start (frame/bot-speech-start true {:timestamp current-time})]
      (is (= (activity-monitor/transform
              {::activity-monitor/bot-speaking? false}
              :in
              speech-start)
             [{::activity-monitor/bot-speaking? true} {:timer-process-in [speech-start]}]))))
  (testing "If bot speaking frame, set bot speaking true and send frame to timer process to reset activity timer"
    (let [speech-stop (frame/bot-speech-stop true {:timestamp current-time})]
      (is (= (activity-monitor/transform
              {::activity-monitor/bot-speaking? true}
              :in
              speech-stop)
             [{::activity-monitor/bot-speaking? false} {:timer-process-in [speech-stop]}]))))

  (testing "Returns old state if any other frame came from :in chan"
    (is (= (activity-monitor/transform
            {::activity-monitor/bot-speaking? true}
            :in
            (frame/system-start true {:timestamp current-time}))
           [{::activity-monitor/bot-speaking? true}])))

  (testing "If the timeout for activity has passed and no VAD frame came, increment the activity ping message count and send a speak frame with a inactivity ping phrase"
    (is (= (activity-monitor/transform
            {::activity-monitor/ping-count 0
             ::activity-monitor/max-pings 3
             ::activity-monitor/ping-phrases #{"Are you still there?"}
             :now current-time}
            :timer-process-out
            {::activity-monitor/timeout? true})
           [{::activity-monitor/ping-count 1
             ::activity-monitor/max-pings 3
             ::activity-monitor/ping-phrases #{"Are you still there?"}
             :now current-time} {:out [(frame/speak-frame "Are you still there?" {:timestamp current-time})]}])))

  (testing "If the activity ping message count is bigger or equal to max pings send end conversation message and reset ping count"
    (is (= (activity-monitor/transform
            {::activity-monitor/ping-count 2
             ::activity-monitor/max-pings 3
             ::activity-monitor/ping-phrases #{"Are you still there?"}
             ::activity-monitor/end-phrase "Goodbye, dear sir!"
             :now current-time}
            :timer-process-out
            {::activity-monitor/timeout? true})
           [{::activity-monitor/ping-count 0
             ::activity-monitor/max-pings 3
             ::activity-monitor/ping-phrases #{"Are you still there?"}
             ::activity-monitor/end-phrase "Goodbye, dear sir!"
             :now current-time} {:out [(frame/speak-frame "Goodbye, dear sir!" {:timestamp current-time})]}])))
  (testing "If the timeout for activity has passed and no VAD frame came, but the user is speaking, don't send inactivity ping"
    (is (= (activity-monitor/transform
            {::activity-monitor/ping-count 0
             ::activity-monitor/max-pings 3
             ::activity-monitor/ping-phrases #{"Are you still there?"}
             ::activity-monitor/user-speaking? true
             :now current-time}
            :timer-process-out
            {::activity-monitor/timeout? true})
           [{::activity-monitor/ping-count 0
             ::activity-monitor/max-pings 3
             ::activity-monitor/ping-phrases #{"Are you still there?"}
             ::activity-monitor/user-speaking? true
             :now current-time}])))

  (testing "If the timeout for activity has passed and no VAD frame came, but the bot is speaking, don't send inactivity ping"
    (is (= (activity-monitor/transform
            {::activity-monitor/ping-count 0
             ::activity-monitor/max-pings 3
             ::activity-monitor/ping-phrases #{"Are you still there?"}
             ::activity-monitor/bot-speaking? true
             :now current-time}
            :timer-process-out
            {::activity-monitor/timeout? true})
           [{::activity-monitor/ping-count 0
             ::activity-monitor/max-pings 3
             ::activity-monitor/ping-phrases #{"Are you still there?"}
             ::activity-monitor/bot-speaking? true
             :now current-time}])))

  (testing "Idempotent state changes - user already speaking gets start event"
    (let [speech-start (frame/user-speech-start true {:timestamp current-time})]
      (is (= (activity-monitor/transform
              {::activity-monitor/user-speaking? true}
              :in
              speech-start)
             [{::activity-monitor/user-speaking? true} {:timer-process-in [speech-start]}]))))

  (testing "Idempotent state changes - user already not speaking gets stop event"
    (let [speech-stop (frame/user-speech-stop true {:timestamp current-time})]
      (is (= (activity-monitor/transform
              {::activity-monitor/user-speaking? false}
              :in
              speech-stop)
             [{::activity-monitor/user-speaking? false} {:timer-process-in [speech-stop]}]))))

  (testing "Idempotent state changes - bot already speaking gets start event"
    (let [speech-start (frame/bot-speech-start true {:timestamp current-time})]
      (is (= (activity-monitor/transform
              {::activity-monitor/bot-speaking? true}
              :in
              speech-start)
             [{::activity-monitor/bot-speaking? true} {:timer-process-in [speech-start]}]))))

  (testing "Idempotent state changes - bot already not speaking gets stop event"
    (let [speech-stop (frame/bot-speech-stop true {:timestamp current-time})]
      (is (= (activity-monitor/transform
              {::activity-monitor/bot-speaking? false}
              :in
              speech-stop)
             [{::activity-monitor/bot-speaking? false} {:timer-process-in [speech-stop]}]))))

  (testing "Unknown input channel returns state unchanged"
    (is (= (activity-monitor/transform
            {::activity-monitor/ping-count 0}
            :some-unknown-channel
            {:some "message"})
           [{::activity-monitor/ping-count 0}])))

  (testing "Timer channel with non-timeout message returns state unchanged"
    (is (= (activity-monitor/transform
            {::activity-monitor/ping-count 0}
            :timer-process-out
            {:not-a-timeout "message"})
           [{::activity-monitor/ping-count 0}])))

  (testing "Timeout when both user and bot are speaking - no action taken"
    (is (= (activity-monitor/transform
            {::activity-monitor/ping-count 0
             ::activity-monitor/max-pings 3
             ::activity-monitor/user-speaking? true
             ::activity-monitor/bot-speaking? true}
            :timer-process-out
            {::activity-monitor/timeout? true})
           [{::activity-monitor/ping-count 0
             ::activity-monitor/max-pings 3
             ::activity-monitor/user-speaking? true
             ::activity-monitor/bot-speaking? true}])))

  (testing "Ping count at exact boundary - should trigger end conversation"
    (is (= (activity-monitor/transform
            {::activity-monitor/ping-count 1
             ::activity-monitor/max-pings 2
             ::activity-monitor/end-phrase "Goodbye!"
             :now current-time}
            :timer-process-out
            {::activity-monitor/timeout? true})
           [{::activity-monitor/ping-count 0
             ::activity-monitor/max-pings 2
             ::activity-monitor/end-phrase "Goodbye!"
             :now current-time} {:out [(frame/speak-frame "Goodbye!" {:timestamp current-time})]}])))

  (testing "Multiple ping phrases - should select one of them"
    (let [ping-phrases #{"Hello?" "Are you there?" "Still listening?"}
          state {::activity-monitor/ping-count 0
                 ::activity-monitor/max-pings 3
                 ::activity-monitor/ping-phrases ping-phrases
                 :now current-time}
          [new-state {:keys [out]}] (activity-monitor/transform
                                     state
                                     :timer-process-out
                                     {::activity-monitor/timeout? true})]
      (is (= (::activity-monitor/ping-count new-state) 1))
      (is (= (count out) 1))
      (is (contains? ping-phrases (:frame/data (first out))))))

  (testing "Empty ping phrases - uses default ping phrases"
    (is (= (activity-monitor/transform
            {::activity-monitor/ping-count 0
             ::activity-monitor/max-pings 3
             ::activity-monitor/ping-phrases #{}
             :now current-time}
            :timer-process-out
            {::activity-monitor/timeout? true})
           [{::activity-monitor/ping-count 1
             ::activity-monitor/max-pings 3
             ::activity-monitor/ping-phrases #{}
             :now current-time} {:out [(frame/speak-frame "Are you still there?" {:timestamp current-time})]}])))

  (testing "Missing state keys - handled gracefully with defaults"
    (is (= (activity-monitor/transform
            {::activity-monitor/max-pings 3
             :now current-time}
            :timer-process-out
            {::activity-monitor/timeout? true})
           [{::activity-monitor/max-pings 3
             :now current-time
             ::activity-monitor/ping-count 1} {:out [(frame/speak-frame "Are you still there?" {:timestamp current-time})]}])))

  (testing "Missing end phrase - uses default end phrase when max pings reached"
    (is (= (activity-monitor/transform
            {::activity-monitor/ping-count 2
             ::activity-monitor/max-pings 3
             :now current-time}
            :timer-process-out
            {::activity-monitor/timeout? true})
           [{::activity-monitor/ping-count 0
             ::activity-monitor/max-pings 3
             :now current-time} {:out [(frame/speak-frame "Goodbye!" {:timestamp current-time})]}]))))
