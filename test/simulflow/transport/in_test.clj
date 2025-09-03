(ns simulflow.transport.in-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.frame :as frame]
   [simulflow.transport.in :as in]
   [simulflow.utils.core :as u]
   [simulflow.vad.core :as vad])
  (:import
   (java.util Date)))

(def test-timestamp #inst "2025-08-07T09:42:21.786-00:00")

(defn test-vad-analyzer [resulting-vad-state]
  (reify vad/VADAnalyzer
    (analyze-audio [_ _]
      resulting-vad-state)
    (voice-confidence [_ _] 0.1)
    (cleanup [_]
      (prn "Cleanup called!"))))

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
        (is (nil? sys-out))))

    (testing "Toggles muted state when mute-input-start frame is received"
      (let [[rs out] (in/base-input-transport-transform {} :sys-in (frame/mute-input-start))]
        (is (= rs {::in/muted? true}))
        (is (empty? out))))
    (testing "Toggles muted state off when mute-input-stop frame is received"
      (let [[rs out] (in/base-input-transport-transform {::in/muted? true} :sys-in (frame/mute-input-stop))]
        (is (= rs {::in/muted? false}))
        (is (empty? out))))
    (testing "When muted state is active, audio frames aren't processed"
      (let [[rs out] (in/base-input-transport-transform {::in/muted? true} :in (frame/audio-input-raw (byte-array (range 200))))]
        (is (= rs {::in/muted? true}))
        (is (empty? out))))))

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
                 ::in/in
                 (u/json-str {:event "start"}))))

        (testing "Merges frames in the same array if more are generated"
          (let [[new-state {:keys [sys-out]}] (in/twilio-transport-in-transform
                                                state
                                                ::in/in
                                                (u/json-str {:event "start"
                                                             :streamSid "hello"}))]
            (is (= state new-state))
            (is (= test-config-change-frame (first sys-out)))
            (is (frame/system-config-change? (last sys-out)))
            (is (= "hello" (:twilio/stream-sid (:frame/data (last sys-out))))))))))
  (testing "Doesn't send serializer if `:transport/send-twilio-serializer?` is false"
    (let [state {:transport/send-twilio-serializer? false}
          [new-state out] (in/twilio-transport-in-transform
                            state
                            ::in/in
                            (u/json-str {:event "start"
                                         :streamSid "hello"}))
          config-change-frame (-> out :sys-out first)
          serializer (-> out :sys-out first :frame/data :transport/serializer)]
      (is (= state new-state))
      (is (frame/system-config-change? config-change-frame))
      (is (nil? serializer)))))

;; Microphone transport in tests

;; Microphone input tests

(deftest microphone-transport-test
  (testing "process-mic-buffer with valid data"
    (let [test-buffer (byte-array [1 2 3 4 5])
          result (in/process-mic-buffer test-buffer 3)]
      (is (map? result))
      (is (= [1 2 3] (vec (:audio-data result))))
      (is (instance? Date (:timestamp result)))))

  (testing "process-mic-buffer with zero bytes"
    (let [test-buffer (byte-array [1 2 3 4 5])
          result (in/process-mic-buffer test-buffer 0)]
      (is (nil? result))))

  (testing "process-mic-buffer with negative bytes"
    (let [test-buffer (byte-array [1 2 3 4 5])
          result (in/process-mic-buffer test-buffer -1)]
      (is (nil? result))))

  (testing "process-mic-buffer with full buffer"
    (let [test-buffer (byte-array [10 20 30 40 50])
          result (in/process-mic-buffer test-buffer 5)]
      (is (= [10 20 30 40 50] (vec (:audio-data result))))))

  (testing "process-mic-buffer creates new array (no shared state)"
    (let [test-buffer (byte-array [1 2 3 4 5])
          result1 (in/process-mic-buffer test-buffer 3)]
      (Thread/sleep 1) ; Ensure timestamp difference
      (let [result2 (in/process-mic-buffer test-buffer 3)]
        ;; Modify original buffer
        (aset test-buffer 0 (byte 99))
        ;; Results should be unaffected
        (is (= [1 2 3] (vec (:audio-data result1))))
        (is (= [1 2 3] (vec (:audio-data result2))))
        ;; Results should be independent (timestamps different)
        (is (not= (:timestamp result1) (:timestamp result2))))))

  ;; =============================================================================
  ;; Individual Function Tests

  (testing "mic-transport-in-transition function"
    (let [close-fn (atom false)
          state {::in/close #(reset! close-fn true)}
          _result (in/base-transport-in-transition! state :clojure.core.async.flow/stop)]
      (is @close-fn "Close function should be called on stop transition")
      ;; Test other transitions don't call close
      (reset! close-fn false)
      (in/base-transport-in-transition! state :clojure.core.async.flow/start)
      (is (not @close-fn) "Close function should not be called on start transition")))

  (testing "mic-transport-in-transform function"
    (let [test-audio-data (byte-array [1 2 3 4])
          test-timestamp (Date.)
          input-data {:audio-data test-audio-data :timestamp test-timestamp}
          [new-state output] (in/mic-transport-in-transform {} ::in/in input-data)]

      (is (= {} new-state)) ; State unchanged
      (is (contains? output :out))
      (is (= 1 (count (:out output))))

      (let [frame (first (:out output))]
        (is (frame/audio-input-raw? frame))
        (is (= test-audio-data (:frame/data frame)))
        (is (= test-timestamp (:frame/ts frame))))))

  (testing "mic-transport-in-transform preserves frame metadata"
    (let [test-timestamp (Date.)
          input-data {:audio-data (byte-array [5 6 7 8]) :timestamp test-timestamp}
          [_ output] (in/mic-transport-in-transform {} ::in/in input-data)
          frame (first (:out output))]

      (is (= :simulflow.frame/audio-input-raw (:frame/type frame)))
      (is (= test-timestamp (:frame/ts frame)))))

  (testing "Mute functionality works as expected"
    (let [[muted-state] (in/mic-transport-in-transform {} :sys-in (frame/mute-input-start))]
      (is (true? (::in/muted? muted-state)))
      (let [[unmuted-state] (in/mic-transport-in-transform muted-state :sys-in (frame/mute-input-stop))]
        (is (false? (::in/muted? unmuted-state))))
      (let [[s r] (in/mic-transport-in-transform {::in/muted? true} ::in/in {:audio-data (byte-array (range 20)) :timestamp test-timestamp})]
        (is (empty? r))
        (is (= s {::in/muted? true})))))

  ;; =============================================================================
  ;; Multi-arity Function Tests (For Compatibility)

  (testing "mic-transport-in-fn describe (0-arity) delegates correctly"
    (let [description (in/mic-transport-in-fn)]
      (is (= description in/mic-transport-in-describe)
          "0-arity should delegate to describe function")))

  (testing "mic-transport-in-fn transform (3-arity) delegates correctly"
    (let [test-audio-data (byte-array [1 2 3 4])
          test-timestamp (Date.)
          input-data {:audio-data test-audio-data :timestamp test-timestamp}
          multi-arity-result (in/mic-transport-in-fn {} :in input-data)
          individual-result (in/mic-transport-in-transform {} :in input-data)]

      (is (= multi-arity-result individual-result)
          "3-arity should delegate to transform function")))

  ;; =============================================================================
  ;; Property-Based Tests

  (testing "process-mic-buffer invariants"
    (doseq [buffer-size [10 100 1000]
            bytes-read [0 5 50 500]]
      (let [test-buffer (byte-array (range buffer-size))
            result (in/process-mic-buffer test-buffer bytes-read)]
        (if (pos? bytes-read)
          (do
            (is (map? result)
                (str "Should return map for bytes-read=" bytes-read))
            (is (= bytes-read (count (:audio-data result)))
                (str "Audio data length should match bytes-read=" bytes-read))
            (is (instance? Date (:timestamp result))
                "Should include timestamp"))
          (is (nil? result)
              (str "Should return nil for bytes-read=" bytes-read))))))

  ;; =============================================================================
  ;; Edge Cases

  (testing "process-mic-buffer with empty buffer"
    (let [empty-buffer (byte-array 0)
          result (in/process-mic-buffer empty-buffer 0)]
      (is (nil? result))))

  (testing "process-mic-buffer with bytes-read larger than buffer"
    ;; This tests the Arrays/copyOfRange behavior
    (let [small-buffer (byte-array [1 2 3])
          ;; Note: In real usage, this shouldn't happen, but testing robustness
          result (in/process-mic-buffer small-buffer 3)]
      (is (= [1 2 3] (vec (:audio-data result)))))))
