(ns simulflow.vad.silero-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.vad.silero :as silero]))

(deftest test-update-vad-state-quiet-to-starting
  (testing "Transition from quiet to starting on speech detection"
    (let [initial-state {:vad/state :vad.state/quiet
                         :vad/starting-count 0
                         :vad/stopping-count 0}
          result (silero/update-vad-state initial-state true 3 5)]
      (is (= :vad.state/starting (:vad/state result)))
      (is (= 1 (:vad/starting-count result)))
      (is (= 0 (:vad/stopping-count result)))))

  (testing "Stay in quiet when no speech detected"
    (let [initial-state {:vad/state :vad.state/quiet
                         :vad/starting-count 0
                         :vad/stopping-count 0}
          result (silero/update-vad-state initial-state false 3 5)]
      (is (= :vad.state/quiet (:vad/state result)))
      (is (= 0 (:vad/starting-count result)))
      (is (= 0 (:vad/stopping-count result))))))

(deftest test-update-vad-state-starting-transitions
  (testing "Increment count while in starting state with continued speech"
    (let [initial-state {:vad/state :vad.state/starting
                         :vad/starting-count 2
                         :vad/stopping-count 0}
          result (silero/update-vad-state initial-state true 3 5)]
      (is (= :vad.state/starting (:vad/state result)))
      (is (= 3 (:vad/starting-count result)))
      (is (= 0 (:vad/stopping-count result)))))

  (testing "Transition from starting to speaking when threshold reached"
    (let [initial-state {:vad/state :vad.state/starting
                         :vad/starting-count 3
                         :vad/stopping-count 0}
          result (silero/update-vad-state initial-state true 3 5)]
      (is (= :vad.state/speaking (:vad/state result)))
      (is (= 0 (:vad/starting-count result)))
      (is (= 0 (:vad/stopping-count result)))))

  (testing "Return to quiet from starting when speech stops"
    (let [initial-state {:vad/state :vad.state/starting
                         :vad/starting-count 2
                         :vad/stopping-count 0}
          result (silero/update-vad-state initial-state false 3 5)]
      (is (= :vad.state/quiet (:vad/state result)))
      (is (= 0 (:vad/starting-count result)))
      (is (= 0 (:vad/stopping-count result))))))

(deftest test-update-vad-state-speaking-transitions
  (testing "Stay in speaking state with continued speech"
    (let [initial-state {:vad/state :vad.state/speaking
                         :vad/starting-count 0
                         :vad/stopping-count 0}
          result (silero/update-vad-state initial-state true 3 5)]
      (is (= :vad.state/speaking (:vad/state result)))
      (is (= 0 (:vad/starting-count result)))
      (is (= 0 (:vad/stopping-count result)))))

  (testing "Transition from speaking to stopping when speech stops"
    (let [initial-state {:vad/state :vad.state/speaking
                         :vad/starting-count 0
                         :vad/stopping-count 0}
          result (silero/update-vad-state initial-state false 3 5)]
      (is (= :vad.state/stopping (:vad/state result)))
      (is (= 0 (:vad/starting-count result)))
      (is (= 1 (:vad/stopping-count result))))))

(deftest test-update-vad-state-stopping-transitions
  (testing "Increment count while in stopping state with continued silence"
    (let [initial-state {:vad/state :vad.state/stopping
                         :vad/starting-count 0
                         :vad/stopping-count 3}
          result (silero/update-vad-state initial-state false 3 5)]
      (is (= :vad.state/stopping (:vad/state result)))
      (is (= 0 (:vad/starting-count result)))
      (is (= 4 (:vad/stopping-count result)))))

  (testing "Transition from stopping to quiet when threshold reached"
    (let [initial-state {:vad/state :vad.state/stopping
                         :vad/starting-count 0
                         :vad/stopping-count 5}
          result (silero/update-vad-state initial-state false 3 5)]
      (is (= :vad.state/quiet (:vad/state result)))
      (is (= 0 (:vad/starting-count result)))
      (is (= 0 (:vad/stopping-count result)))))

  (testing "Return to speaking from stopping when speech resumes"
    (let [initial-state {:vad/state :vad.state/stopping
                         :vad/starting-count 0
                         :vad/stopping-count 3}
          result (silero/update-vad-state initial-state true 3 5)]
      (is (= :vad.state/speaking (:vad/state result)))
      (is (= 0 (:vad/starting-count result)))
      (is (= 0 (:vad/stopping-count result))))))

(deftest test-update-vad-state-edge-cases
  (testing "Threshold boundary cases for starting"
    (let [;; Just below threshold
          state-below {:vad/state :vad.state/starting
                       :vad/starting-count 2
                       :vad/stopping-count 0}
          result-below (silero/update-vad-state state-below true 3 5)
          ;; At threshold
          state-at {:vad/state :vad.state/starting
                    :vad/starting-count 3
                    :vad/stopping-count 0}
          result-at (silero/update-vad-state state-at true 3 5)]

      ;; Below threshold should stay in starting
      (is (= :vad.state/starting (:vad/state result-below)))
      (is (= 3 (:vad/starting-count result-below)))

      ;; At threshold should transition to speaking
      (is (= :vad.state/speaking (:vad/state result-at)))
      (is (= 0 (:vad/starting-count result-at)))))

  (testing "Threshold boundary cases for stopping"
    (let [;; Just below threshold
          state-below {:vad/state :vad.state/stopping
                       :vad/starting-count 0
                       :vad/stopping-count 4}
          result-below (silero/update-vad-state state-below false 3 5)
          ;; At threshold
          state-at {:vad/state :vad.state/stopping
                    :vad/starting-count 0
                    :vad/stopping-count 5}
          result-at (silero/update-vad-state state-at false 3 5)]

      ;; Below threshold should stay in stopping
      (is (= :vad.state/stopping (:vad/state result-below)))
      (is (= 5 (:vad/stopping-count result-below)))

      ;; At threshold should transition to quiet
      (is (= :vad.state/quiet (:vad/state result-at)))
      (is (= 0 (:vad/stopping-count result-at))))))

(deftest test-update-vad-state-different-parameters
  (testing "Different start/stop frame thresholds"
    (let [;; Test with start-frames = 2 (at threshold)
          state-at-2 {:vad/state :vad.state/starting
                      :vad/starting-count 2
                      :vad/stopping-count 0}
          result-2 (silero/update-vad-state state-at-2 true 2 10)

          ;; Test with start-frames = 5 (below threshold)
          state-below-5 {:vad/state :vad.state/starting
                         :vad/starting-count 1
                         :vad/stopping-count 0}
          result-5 (silero/update-vad-state state-below-5 true 5 10)]

      ;; With threshold 2 and count 2, should transition to speaking
      (is (= :vad.state/speaking (:vad/state result-2)))

      ;; With threshold 5 and count 1, should stay in starting
      (is (= :vad.state/starting (:vad/state result-5)))
      (is (= 2 (:vad/starting-count result-5))))))

(deftest test-update-vad-state-complete-sequence
  (testing "Complete state transition sequence"
    (let [start-frames 3
          stop-frames 5
          ;; Simulate a complete sequence: quiet -> speaking -> quiet
          sequence [{:speaking? false :expected-state :vad.state/quiet}
                    {:speaking? true :expected-state :vad.state/starting} ; Start detection
                    {:speaking? true :expected-state :vad.state/starting} ; Count: 2
                    {:speaking? true :expected-state :vad.state/starting} ; Count: 3
                    {:speaking? true :expected-state :vad.state/speaking} ; Transition to speaking
                    {:speaking? true :expected-state :vad.state/speaking} ; Continue speaking
                    {:speaking? false :expected-state :vad.state/stopping} ; Start stopping
                    {:speaking? false :expected-state :vad.state/stopping} ; Count: 2
                    {:speaking? false :expected-state :vad.state/stopping} ; Count: 3
                    {:speaking? false :expected-state :vad.state/stopping} ; Count: 4
                    {:speaking? false :expected-state :vad.state/stopping} ; Count: 5
                    {:speaking? false :expected-state :vad.state/quiet}] ; Transition to quiet

          initial-state {:vad/state :vad.state/quiet
                         :vad/starting-count 0
                         :vad/stopping-count 0}]

      ;; Run through the complete sequence
      (reduce (fn [current-state {:keys [speaking? expected-state]}]
                (let [new-state (silero/update-vad-state current-state speaking? start-frames stop-frames)]
                  (is (= expected-state (:vad/state new-state))
                      (str "Expected " expected-state " but got " (:vad/state new-state)
                           " with speaking?=" speaking?))
                  new-state))
              initial-state
              sequence))))

(deftest test-update-vad-state-interruption-scenarios
  (testing "Speech interrupted during starting phase"
    (let [state-starting {:vad/state :vad.state/starting
                          :vad/starting-count 2
                          :vad/stopping-count 0}
          result (silero/update-vad-state state-starting false 3 5)]
      (is (= :vad.state/quiet (:vad/state result)))
      (is (= 0 (:vad/starting-count result)))))

  (testing "Speech resumes during stopping phase"
    (let [state-stopping {:vad/state :vad.state/stopping
                          :vad/starting-count 0
                          :vad/stopping-count 3}
          result (silero/update-vad-state state-stopping true 3 5)]
      (is (= :vad.state/speaking (:vad/state result)))
      (is (= 0 (:vad/stopping-count result)))))

  (testing "Rapid on-off transitions"
    (let [initial-state {:vad/state :vad.state/quiet
                         :vad/starting-count 0
                         :vad/stopping-count 0}
          ;; Speech on -> off -> on -> off
          state-1 (silero/update-vad-state initial-state true 3 5) ; -> starting
          state-2 (silero/update-vad-state state-1 false 3 5) ; -> quiet
          state-3 (silero/update-vad-state state-2 true 3 5) ; -> starting
          state-4 (silero/update-vad-state state-3 false 3 5)] ; -> quiet

      (is (= :vad.state/starting (:vad/state state-1)))
      (is (= :vad.state/quiet (:vad/state state-2)))
      (is (= :vad.state/starting (:vad/state state-3)))
      (is (= :vad.state/quiet (:vad/state state-4))))))

(deftest test-update-vad-state-hysteresis-behavior
  (testing "Hysteresis prevents rapid state changes"
    (let [start-frames 3
          stop-frames 5]

      ;; Test that brief speech doesn't immediately trigger speaking
      (is (= :vad.state/starting
             (:vad/state (silero/update-vad-state
                           {:vad/state :vad.state/quiet :vad/starting-count 0 :vad/stopping-count 0}
                           true start-frames stop-frames))))

      ;; Test that brief silence doesn't immediately trigger quiet
      (is (= :vad.state/stopping
             (:vad/state (silero/update-vad-state
                           {:vad/state :vad.state/speaking :vad/starting-count 0 :vad/stopping-count 0}
                           false start-frames stop-frames))))))

  (testing "Asymmetric thresholds work correctly"
    (let [start-frames 2 ; Quick to start
          stop-frames 8] ; Slow to stop

      ;; Should transition to speaking quickly (2 frames)
      (is (= :vad.state/speaking
             (:vad/state (silero/update-vad-state
                           {:vad/state :vad.state/starting :vad/starting-count 2 :vad/stopping-count 0}
                           true start-frames stop-frames))))

      ;; Should take longer to transition to quiet (8 frames)
      (is (= :vad.state/stopping
             (:vad/state (silero/update-vad-state
                           {:vad/state :vad.state/stopping :vad/starting-count 0 :vad/stopping-count 7}
                           false start-frames stop-frames))))

      (is (= :vad.state/quiet
             (:vad/state (silero/update-vad-state
                           {:vad/state :vad.state/stopping :vad/starting-count 0 :vad/stopping-count 8}
                           false start-frames stop-frames)))))))
