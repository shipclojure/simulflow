(ns simulflow.transport.out-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [simulflow.frame :as frame]
   [simulflow.transport.out :as sut]
   [simulflow.transport.protocols :as tp :refer [FrameSerializer]]
   [simulflow.utils.core :as u]))

(deftest process-realtime-out-audio-frame-test
  (testing "process-realtime-out-audio-frame with first audio frame"
    (let [state {::sut/speaking? false
                 ::sut/last-send-time 0
                 :audio.out/sending-interval 20}
          frame (frame/audio-output-raw {:audio (byte-array [1 2 3]) :sample-rate 16000})
          current-time 2000
          [new-state output] (sut/process-realtime-out-audio-frame state frame current-time)]

      (is (true? (::sut/speaking? new-state)))
      (is (= current-time (::sut/last-send-time new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/bot-speech-start? (first (:sys-out output))))
      (is (= 1 (count (:audio-write output))))
      (is (= 16000 (:sample-rate (first (:audio-write output)))))))

  (testing "process-realtime-out-audio-frame with different sample rate"
    (let [state {::sut/speaking? false
                 ::sut/last-send-time 0
                 :audio.out/sending-interval 20}
          frame (frame/audio-output-raw {:audio (byte-array [1 2 3]) :sample-rate 24000})
          current-time 2000
          [new-state output] (sut/process-realtime-out-audio-frame state frame current-time)
          command (first (:audio-write output))]
      (is (= (update command :data vec) {:command :write-audio
                                         :data [1, 2, 3]
                                         :delay-until 2000
                                         :sample-rate 24000}))

      (is (true? (::sut/speaking? new-state)))
      (is (= 1 (count (:audio-write output))))
      (is (= 24000 (:sample-rate (first (:audio-write output))))))))

(deftest realtime-out-transform-test
  (testing "transform with audio frame"
    (let [state {::sut/speaking? false :audio.out/sending-interval 25}
          frame (frame/audio-output-raw {:audio (byte-array [1 2 3]) :sample-rate 16000})
          [new-state output] (sut/base-realtime-out-transform state :in frame)]

      (is (true? (::sut/speaking? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/bot-speech-start? (first (:sys-out output))))
      (is (= 1 (count (:audio-write output))))))

  (testing "transform with timer tick (stop speaking)"
    (let [state {::sut/speaking? true
                 ::sut/last-send-time 1000
                 :activity-detection/silence-threshold-ms 200}
          timer-frame {:timer/tick true :timer/timestamp 1300}
          [new-state output] (sut/base-realtime-out-transform state :timer-out timer-frame)]

      (is (false? (::sut/speaking? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/bot-speech-stop? (first (:sys-out output)))))))

(deftest test-realtime-speakers-out-describe
  (testing "describe function returns correct structure"
    (let [description sut/realtime-speakers-out-describe]
      (is (contains? description :ins))
      (is (contains? description :outs))
      (is (contains? description :params))

      ;; Verify input channels
      (is (contains? (:ins description) :in))
      (is (contains? (:ins description) :sys-in))

      ;; Verify output channels
      (is (contains? (:outs description) :out))

      ;; Verify required parameters
      (is (contains? (:params description) :audio.out/duration-ms)))))

(deftest test-realtime-out-timer-handling
  (testing "timer tick when not speaking (no effect)"
    (let [state {::sut/speaking? false
                 ::sut/last-send-time 1000
                 :activity-detection/silence-threshold-ms 200}
          timer-frame {:timer/tick true :timer/timestamp 1500}
          [new-state output] (sut/base-realtime-out-transform state :timer-out timer-frame)]

      (is (false? (::sut/speaking? new-state)))
      (is (empty? (:out output)))))

  (testing "timer tick when speaking but silence threshold not exceeded"
    (let [state {::sut/speaking? true
                 ::sut/last-send-time 1000
                 :activity-detection/silence-threshold-ms 200}
          timer-frame {:timer/tick true :timer/timestamp 1100} ; Only 100ms silence
          [new-state output] (sut/base-realtime-out-transform state :timer-out timer-frame)]

      (is (true? (::sut/speaking? new-state)))
      (is (empty? (:out output)))))

  (testing "timer tick when speaking and silence threshold exceeded"
    (let [state {::sut/speaking? true
                 ::sut/last-send-time 1000
                 :activity-detection/silence-threshold-ms 200}
          timer-frame {:timer/tick true :timer/timestamp 1300} ; 300ms silence > 200ms threshold
          [new-state output] (sut/base-realtime-out-transform state :timer-out timer-frame)]

      (is (false? (::sut/speaking? new-state)))
      (is (= 1 (count (:sys-out output))))
      (is (frame/bot-speech-stop? (first (:sys-out output)))))))

(deftest test-realtime-out-system-config-handling
  (testing "system config change with new serializer"
    (let [new-serializer (reify FrameSerializer
                           (serialize-frame [_ frame] (assoc frame :serialized? true)))
          config-frame (frame/system-config-change {:transport/serializer new-serializer})
          initial-state {:transport/serializer nil}
          [new-state output] (sut/base-realtime-out-transform initial-state :in config-frame)]

      (is (= new-serializer (:transport/serializer new-state)))
      (is (empty? output))))

  (testing "system config change without serializer"
    (let [config-frame (frame/system-config-change {:other/setting "value"})
          initial-state {:transport/serializer nil}
          [new-state output] (sut/base-realtime-out-transform initial-state :in config-frame)]

      (is (= initial-state new-state))
      (is (empty? output)))))

(deftest test-realtime-speakers-out-serializer-integration
  (testing "transform with serializer"
    (let [mock-serializer (reify tp/FrameSerializer
                            (serialize-frame [_ frame]
                              ;; Serializer modifies the frame data
                              (assoc frame :frame/data [99 99 99])))
          state {::sut/speaking? false
                 ::sut/last-send-time 0
                 :audio.out/sending-interval 20
                 :transport/serializer mock-serializer}
          frame (frame/audio-output-raw {:audio (byte-array [1 2 3]) :sample-rate 16000})]
      (with-redefs [u/mono-time (constantly 1000)]
        (let [[_ output] (sut/base-realtime-out-transform state :in frame)
              audio-write (first (:audio-write output))]
          (is (= :write-audio (:command audio-write)))
          (is (= [99 99 99] (:frame/data (:data audio-write)))))))) ; Should use serialized data

  (testing "transform without serializer"
    (let [state {::sut/speaking? false
                 ::sut/last-send-time 0
                 :audio.out/sending-interval 20
                 :transport/serializer nil}
          frame (frame/audio-output-raw {:audio (byte-array [1 2 3]) :sample-rate 16000})]
      (with-redefs [u/mono-time (constantly 1000)]
        (let [[_ output] (sut/base-realtime-out-transform state :in frame)
              audio-write (first (:audio-write output))]
          (is (= :write-audio (:command audio-write)))
          (is (= [1 2 3] (vec (:data audio-write)))))))))

(deftest test-realtime-speakers-out-edge-cases
  (testing "unknown input port"
    (let [state {}
          frame {:some :data}
          [new-state output] (sut/base-realtime-out-transform state :unknown-port frame)]

      (is (= state new-state))
      (is (empty? output))))

  (testing "non-audio frame on input port"
    (let [state {}
          non-audio-frame {:frame/type :other}
          [new-state output] (sut/base-realtime-out-transform state :in non-audio-frame)]

      (is (= state new-state))
      (is (empty? output))))

  (testing "non-timer frame on timer-out port"
    (let [state {}
          non-timer-frame {:other :data}
          [new-state output] (sut/base-realtime-out-transform state :timer-out non-timer-frame)]

      (is (= state new-state))
      (is (empty? output)))))

(deftest test-realtime-speakers-out-multi-arity-functions
  (testing "0-arity (describe) delegates correctly"
    (let [multi-arity-result (sut/realtime-speakers-out-fn)
          direct-result sut/realtime-speakers-out-describe]
      (is (= multi-arity-result direct-result))))

  (testing "2-arity (transition) delegates correctly"
    (let [state {}
          transition :clojure.core.async.flow/start
          multi-arity-result (sut/realtime-speakers-out-fn state transition)
          direct-result (sut/realtime-out-transition state transition)]
      (is (= multi-arity-result direct-result))))

  (testing "3-arity (transform) delegates correctly"
    (let [state {}
          input-port :in
          frame {:test :frame}
          multi-arity-result (sut/realtime-speakers-out-fn state input-port frame)
          direct-result (sut/base-realtime-out-transform state input-port frame)]
      (is (= multi-arity-result direct-result)))))

(deftest test-realtime-out-state-transitions
  (testing "speaking state progression"
    (let [initial-state {::sut/speaking? false
                         ::sut/last-send-time 0
                         :audio.out/sending-interval 20}
          frame1 (frame/audio-output-raw {:audio (byte-array [1 2 3]) :sample-rate 16000})
          frame2 (frame/audio-output-raw {:audio (byte-array [4 5 6]) :sample-rate 16000})]

      (with-redefs [u/mono-time (let [counter (atom 0)]
                                  #(swap! counter + 100))] ; Increment by 100ms each call
        (let [;; Process first frame (should start speaking)
              [state1 output1] (sut/base-realtime-out-transform initial-state :in frame1)

              ;; Process second frame (should continue speaking)
              [state2 output2] (sut/base-realtime-out-transform state1 :in frame2)

              ;; Process timer tick after silence threshold
              timer-frame {:timer/tick true
                           :timer/timestamp (+ (::sut/last-send-time state2) 1000)}
              [state3 output3] (sut/base-realtime-out-transform
                                 (assoc state2 :activity-detection/silence-threshold-ms 500)
                                 :timer-out timer-frame)]

          ;; Verify state progression
          (is (false? (::sut/speaking? initial-state)))
          (is (true? (::sut/speaking? state1)))
          (is (true? (::sut/speaking? state2)))
          (is (false? (::sut/speaking? state3)))

          ;; Verify events
          ;; Verify events
          (is (frame/bot-speech-start? (first (:sys-out output1))))
          (is (empty? (:sys-out output2)))
          (is (frame/bot-speech-stop? (first (:sys-out output3)))))))))

(deftest test-realtime-out-timing-accuracy
  (testing "delay-until calculations are accurate"
    (let [current-time 1000
          sending-interval 25
          state {::sut/speaking? false
                 ::sut/last-send-time 0
                 :audio.out/sending-interval sending-interval
                 ::sut/now current-time}
          frame (frame/audio-output-raw {:audio (byte-array [1 2 3]) :sample-rate 16000})
          [new-state output] (sut/base-realtime-out-transform state :in frame)
          audio-write (first (:audio-write output))
          [next-state] (sut/base-realtime-out-transform (assoc new-state ::sut/now 1020) :in frame)
          [next-state2] (sut/base-realtime-out-transform (assoc next-state ::sut/now 1025) :in frame)]

      (is (= current-time (:delay-until audio-write)))
      (is (= current-time (::sut/last-send-time new-state)))

      ;; Check timing fields specifically since audio-line is now included
      (is (= 1025 (::sut/last-send-time next-state)))
      (is (= 25 (:audio.out/sending-interval next-state)))
      (is (true? (::sut/speaking? next-state)))

      (is (= 1050 (::sut/last-send-time next-state2)))
      (is (= 25 (:audio.out/sending-interval next-state2)))
      (is (true? (::sut/speaking? next-state2)))))

  (testing "silence threshold calculations"
    (let [base-time 2000
          silence-threshold 300
          state {::sut/speaking? true
                 ::sut/last-send-time base-time
                 :activity-detection/silence-threshold-ms silence-threshold}

          ;; Timer tick just under threshold
          timer-frame-under {:timer/tick true :timer/timestamp (+ base-time 250)}
          [state-under output-under] (sut/base-realtime-out-transform state :timer-out timer-frame-under)

          ;; Timer tick over threshold
          timer-frame-over {:timer/tick true :timer/timestamp (+ base-time 350)}
          [state-over output-over] (sut/base-realtime-out-transform state :timer-out timer-frame-over)]

      ;; Under threshold: still speaking
      (is (true? (::sut/speaking? state-under)))
      (is (empty? (:sys-out output-under)))

      ;; Over threshold: stop speaking
      (is (false? (::sut/speaking? state-over)))
      (is (frame/bot-speech-stop? (first (:sys-out output-over)))))))
