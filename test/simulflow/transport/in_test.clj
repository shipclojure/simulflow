(ns simulflow.transport.in-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.frame :as frame]
   [simulflow.transport.in :as in]
   [simulflow.utils.core :as u]
   [simulflow.vad.core :as vad]))

(def test-timestamp #inst "2025-08-07T09:42:21.786-00:00")

(defn test-vad-analyzer [resulting-vad-state]
  (reify vad/VADAnalyzer
    (analyze-audio [_ _]
      resulting-vad-state)
    (voice-confidence [_ _] 0.1)))

(deftest base-input-transport-test
  (let [input-frame (frame/audio-input-raw (byte-array (range 200)) {:timestamp test-timestamp})]
    (testing "Returns state for anything that is not audio-input-raw or bot-interrupt frames"
      (let [state {:pipeline/supports-interrupt? true}]
        (is (= [state] (in/base-input-transport-transform state :in (frame/llm-full-response-end true))))))
    (testing "Pushes audio-input-raw frame further if no vad analyzer is present"
      (let [state {:pipeline/supports-interrupt? true}
            [rs {:keys [out]}] (in/base-input-transport-transform state :in input-frame)]
        (is (= rs state))
        (is (= input-frame (first out)))))

    (testing "Vad checking"
      (testing "Keeps the new vad state when it is a transition state and no control frames emitted"
        (let [state {:pipeline/supports-interrupt? false
                     :vad/state :vad.state/quiet
                     :vad/analyser (test-vad-analyzer :vad.state/starting)}
              [rs {:keys [out sys-out]}] (in/base-input-transport-transform state :in input-frame)]
          (is (= rs (assoc state :vad/state :vad.state/starting)))
          (is (= (first out) input-frame))
          (is (nil? sys-out)))
        (let [state {:pipeline/supports-interrupt? false
                     :vad/state :vad.state/speaking
                     :vad/analyser (test-vad-analyzer :vad.state/stopping)}
              [rs {:keys [out sys-out]}] (in/base-input-transport-transform state :in input-frame)]
          (is (= rs (assoc state :vad/state :vad.state/stopping)))
          (is (= (first out) input-frame))
          (is (nil? sys-out))))

      (testing "Doesn't emit new VAD or interruption frames when vad state doesn't change"
        (let [state {:pipeline/supports-interrupt? false
                     :vad/state :vad.state/speaking
                     :vad/analyser (test-vad-analyzer :vad.state/speaking)}
              [rs {:keys [out sys-out]}] (in/base-input-transport-transform state :in input-frame)]
          (is (= rs state))
          (is (= (first out) input-frame))
          (is (nil? sys-out))))
      (testing "Pipeline doesn't support interruption"
        (testing "Emits user-speech-start and vad-user-speech-start frames when vad changes to user start speaking"
          (let [state {:pipeline/supports-interrupt? false
                       :vad/state :vad.state/starting
                       :vad/analyser (test-vad-analyzer :vad.state/speaking)}
                [rs {:keys [out sys-out]}] (in/base-input-transport-transform state :in input-frame)
                [vad-user-speech-frame user-speech-frame] sys-out]
            (is (= rs (assoc state :vad/state :vad.state/speaking)))
            (is (= (first out) input-frame))
            (is (= (count sys-out) 2))
            (is (frame/vad-user-speech-start? vad-user-speech-frame))
            (is (frame/user-speech-start? user-speech-frame))))
        (testing "Emits user-speech-stop and vad-user-speech-stop frames when vad changes to quiet"
          (let [state {:pipeline/supports-interrupt? false
                       :vad/state :vad.state/stopping
                       :vad/analyser (test-vad-analyzer :vad.state/quiet)}
                [rs {:keys [out sys-out]}] (in/base-input-transport-transform state :in input-frame)
                [vad-user-speech-frame user-speech-frame] sys-out]
            (is (= rs (assoc state :vad/state :vad.state/quiet)))
            (is (= (first out) input-frame))
            (is (= (count sys-out) 2))
            (is (frame/vad-user-speech-stop? vad-user-speech-frame))
            (is (frame/user-speech-stop? user-speech-frame)))))
      (testing "Pipeline support interruption"
        (testing "Emits user-speech-start, vad-user-speech-start and control-interrupt-start frames when vad changes to user start speaking"
          (let [state {:pipeline/supports-interrupt? true
                       :vad/state :vad.state/starting
                       :vad/analyser (test-vad-analyzer :vad.state/speaking)}
                [rs {:keys [out sys-out]}] (in/base-input-transport-transform state :in input-frame)
                [vad-user-speech-frame user-speech-frame control-frame] sys-out]
            (is (= rs (assoc state :vad/state :vad.state/speaking)))
            (is (= (first out) input-frame))
            (is (= (count sys-out) 3))
            (is (frame/vad-user-speech-start? vad-user-speech-frame))
            (is (frame/user-speech-start? user-speech-frame))
            (is (frame/control-interrupt-start? control-frame))))
        (testing "Emits user-speech-stop and vad-user-speech-stop and control-interrupt-stop frames when vad changes to quiet"
          (let [state {:pipeline/supports-interrupt? true
                       :vad/state :vad.state/stopping
                       :vad/analyser (test-vad-analyzer :vad.state/quiet)}
                [rs {:keys [out sys-out]}] (in/base-input-transport-transform state :in input-frame)
                [vad-user-speech-frame user-speech-frame control-frame] sys-out]
            (is (= rs (assoc state :vad/state :vad.state/quiet)))
            (is (= (first out) input-frame))
            (is (= (count sys-out) 3))
            (is (frame/vad-user-speech-stop? vad-user-speech-frame))
            (is (frame/user-speech-stop? user-speech-frame))
            (is (frame/control-interrupt-stop? control-frame))))))

    (testing "Returns control-interrupt-start when a bot-interrupt is received if pipeline supports interrupt"
      (let [state {:pipeline/supports-interrupt? true}
            [rs {:keys [out sys-out]}] (in/base-input-transport-transform state :in (frame/bot-interrupt true))]
        (is (= rs state))
        (is (nil? out))
        (is (frame/control-interrupt-start? (first sys-out)))))
    (testing "Does nothing when a bot-interrupt is received if pipeline doesn't support interrupt"
      (let [state {:pipeline/supports-interrupt? false}
            [rs {:keys [out sys-out]}] (in/base-input-transport-transform state :in (frame/bot-interrupt true))]
        (is (= rs state))
        (is (nil? out))
        (is (nil? sys-out))))))

;; =============================================================================
;; Twilio Transport Tests

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
               (in/twilio-transport-in-transform
                 state
                 ::in/twilio-in
                 (u/json-str {:event "start"}))))

        (testing "Merges frames in the same array if more are generated"
          (let [[new-state {:keys [sys-out]}] (in/twilio-transport-in-transform
                                                state
                                                ::in/twilio-in
                                                (u/json-str {:event "start"
                                                             :streamSid "hello"}))]
            (is (= state new-state))
            (is (= test-config-change-frame (first sys-out)))
            (is (frame/system-config-change? (last sys-out)))
            (is (= "hello" (:twilio/stream-sid (:frame/data (last sys-out)))))))))))
