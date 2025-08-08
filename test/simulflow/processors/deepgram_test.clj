(ns simulflow.processors.deepgram-test
  (:require
   [clojure.core.async.flow :as flow]
   [clojure.string :as str]

   [simulflow.frame :as frame]
   [simulflow.processors.deepgram :as deepgram]
   [simulflow.schema :as schema]
   [simulflow.utils.core :as u]
   [clojure.test :refer [deftest is testing]]))

(def current-time #inst "2025-06-27T06:13:35.236-00:00")

;; Mock websocket messages for testing
(def speech-started-event
  {:type "SpeechStarted"})

(def utterance-end-event
  {:type "UtteranceEnd"})

(def final-transcript-event
  {:is_final true
   :channel {:alternatives [{:transcript "Hello world"}]}})

(def interim-transcript-event
  {:is_final false
   :channel {:alternatives [{:transcript "Hello"}]}})

(def empty-interim-transcript-event
  {:is_final false
   :channel {:alternatives [{:transcript ""}]}})

(deftest make-websocket-url-test
  (testing "Basic websocket URL creation"
    (let [config {:transcription/api-key "test-key"
                  :transcription/sample-rate 16000
                  :transcription/model :nova-2-general
                  :transcription/language :en
                  :transcription/encoding :linear16}
          url (deepgram/make-websocket-url config)]
      (is (str/starts-with? url "wss://api.deepgram.com/v1/listen"))
      (is (str/includes? url "sample_rate=16000"))
      (is (str/includes? url "model=nova-2-general"))
      (is (str/includes? url "language=en"))))

  (testing "WebSocket URL with optional parameters"
    (let [config {:transcription/api-key "test-key"
                  :transcription/model :nova-2-meeting
                  :transcription/language :es
                  :transcription/interim-results? true
                  :transcription/punctuate? true
                  :transcription/vad-events? true
                  :transcription/smart-format? false
                  :transcription/utterance-end-ms 2000}
          url (deepgram/make-websocket-url config)]
      (is (str/includes? url "sample_rate=16000"))
      (is (str/includes? url "model=nova-2-meeting"))
      (is (str/includes? url "language=es"))
      (is (str/includes? url "interim_results=true"))
      (is (str/includes? url "punctuate=true"))
      (is (str/includes? url "vad_events=true"))
      (is (str/includes? url "smart_format=false"))
      (is (str/includes? url "channels=1"))
      (is (str/includes? url "utterance_end_ms=2000"))))

  (testing "WebSocket URL excludes nil values"
    (let [config {:transcription/api-key "test-key"
                  :transcription/sample-rate 16000
                  :transcription/model :nova-2-general
                  :transcription/language :en
                  :transcription/encoding :linear16
                  :transcription/utterance-end-ms nil}
          url (deepgram/make-websocket-url config)]
      (is (not (str/includes? url "utterance_end_ms"))))))

(deftest deepgram-event->frames-test
  (testing "Speech started event"
    (let [frames (deepgram/deepgram-event->frames speech-started-event)]
      (is (= 1 (count frames)))
      (is (frame/user-speech-start? (first frames)))))

  (testing "Utterance end event without interrupt support"
    (let [frames (deepgram/deepgram-event->frames utterance-end-event)]
      (is (= 1 (count frames)))
      (is (frame/user-speech-stop? (first frames)))))

  (testing "Utterance end event with interrupt support"
    (let [frames (deepgram/deepgram-event->frames utterance-end-event
                                                  :supports-interrupt? true)]
      (is (= 2 (count frames)))
      (is (frame/user-speech-stop? (first frames)))
      (is (frame/control-interrupt-stop? (second frames)))))

  (testing "Final transcript event"
    (let [frames (deepgram/deepgram-event->frames final-transcript-event)]
      (is (= 1 (count frames)))
      (is (frame/transcription? (first frames)))
      (is (= "Hello world" (:frame/data (first frames))))))

  (testing "Interim transcript event without interrupt"
    (let [frames (deepgram/deepgram-event->frames interim-transcript-event)]
      (is (= 1 (count frames)))
      (is (frame/transcription-interim? (first frames)))
      (is (= "Hello" (:frame/data (first frames))))))

  (testing "Interim transcript event with interrupt support and send-interrupt"
    (let [frames (deepgram/deepgram-event->frames interim-transcript-event
                                                  :supports-interrupt? true
                                                  :send-interrupt? true)]
      (is (= 2 (count frames)))
      (is (frame/transcription-interim? (first frames)))
      (is (frame/control-interrupt-start? (second frames)))
      (is (= "Hello" (:frame/data (first frames))))))

  (testing "Empty interim transcript event"
    (let [frames (deepgram/deepgram-event->frames empty-interim-transcript-event)]
      (is (empty? frames))))

  (testing "Unknown event type"
    (let [frames (deepgram/deepgram-event->frames {:type "Unknown"})]
      (is (empty? frames)))))

(deftest transform-test
  (testing "WebSocket read message - speech started"
    (let [state {:some :state}
          json-msg (u/json-str speech-started-event)
          [new-state outputs] (deepgram/transform state :ws-read json-msg)]
      (is (= state new-state))
      (is (= 1 (count (:out outputs))))
      (is (frame/user-speech-start? (first (:out outputs))))))

  (testing "WebSocket read message - final transcript"
    (let [state {:some :state}
          json-msg (u/json-str final-transcript-event)
          [new-state outputs] (deepgram/transform state :ws-read json-msg)]
      (is (= state new-state))
      (is (= 1 (count (:out outputs))))
      (is (frame/transcription? (first (:out outputs))))
      (is (= "Hello world" (:frame/data (first (:out outputs)))))))

  (testing "WebSocket read message - interim transcript"
    (let [state {:some :state}
          json-msg (u/json-str interim-transcript-event)
          [new-state outputs] (deepgram/transform state :ws-read json-msg)]
      (is (= state new-state))
      (is (= 1 (count (:out outputs))))
      (is (frame/transcription-interim? (first (:out outputs))))
      (is (= "Hello" (:frame/data (first (:out outputs)))))))

  (testing "Audio input frame"
    (let [state {:some :state}
          audio-frame (frame/audio-input-raw (byte-array [1 2 3 4]))
          [new-state outputs] (deepgram/transform state :in audio-frame)]
      (is (= state new-state))
      (is (= 1 (count (:ws-write outputs))))
      (is (frame/audio-input-raw? (first (:ws-write outputs))))))

  (testing "Unknown frame type"
    (let [state {:some :state}
          unknown-frame (frame/system-start true)
          [new-state outputs] (deepgram/transform state :in unknown-frame)]
      (is (= state new-state))
      (is (empty? outputs))))

  (testing "Unknown input channel"
    (let [state {:some :state}
          some-frame (frame/system-start true)
          [new-state outputs] (deepgram/transform state :unknown-channel some-frame)]
      (is (= state new-state))
      (is (empty? outputs)))))

(deftest transition-test
  (testing "Transition with stop signal"
    (let [alive-atom (atom true)
          state {:websocket/alive? alive-atom
                 ::flow/in-ports {}
                 ::flow/out-ports {}}
          result (deepgram/transition state ::flow/stop)]
      (is (= state result))))

  (testing "Transition with non-stop signal"
    (let [alive-atom (atom true)
          state {:websocket/alive? alive-atom}
          result (deepgram/transition state ::flow/resume)]
      (is (= state result))
      (is (true? @alive-atom))))

  (testing "Transition with nil channels"
    (let [state {:websocket/conn nil
                 :websocket/alive? nil
                 ::flow/in-ports {:ws-read nil}
                 ::flow/out-ports {:ws-write nil}}
          result (deepgram/transition state ::flow/stop)]
      (is (= state result)))))

(deftest schema-validation-test
  (testing "Valid configuration with required fields"
    (let [config {:transcription/api-key "test-key-12345"
                  :transcription/sample-rate 16000}
          result (schema/parse-with-defaults deepgram/DeepgramConfig config)]
      (is (map? result))
      (is (= "test-key-12345" (:transcription/api-key result)))

      ;; Check defaults are applied
      (is (= :nova-2-general (:transcription/model result)))
      (is (= false (:transcription/interim-results? result)))
      (is (= true (:transcription/smart-format? result)))
      (is (= :en (:transcription/language result)))))

  (testing "Configuration with utterance-end-ms requires interim-results"
    (let [config {:transcription/api-key "test-key-12345"
                  :transcription/sample-rate 16000
                  :transcription/utterance-end-ms 2000
                  :transcription/interim-results? false}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Parameters invalid after applying defaults"
            (schema/parse-with-defaults deepgram/DeepgramConfig config)))))

  (testing "Valid configuration with utterance-end-ms and interim-results"
    (let [config {:transcription/api-key "test-key-12345"
                  :transcription/sample-rate 16000
                  :transcription/utterance-end-ms 2000
                  :transcription/interim-results? true}
          result (schema/parse-with-defaults deepgram/DeepgramConfig config)]
      (is (map? result))
      (is (= 2000 (:transcription/utterance-end-ms result)))
      (is (= true (:transcription/interim-results? result)))))

  (testing "Smart format and punctuate conflict"
    (let [config {:transcription/api-key "test-key-12345"
                  :transcription/sample-rate 16000
                  :transcription/smart-format? true
                  :transcription/punctuate? true}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Parameters invalid after applying defaults"
            (schema/parse-with-defaults deepgram/DeepgramConfig config)))))

  (testing "Missing required field throws error"
    (let [config {:transcription/sample-rate 16000}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Missing required parameters"
            (schema/parse-with-defaults deepgram/DeepgramConfig config))))))

(deftest init-validation-test
  (testing "Init with invalid config throws error"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Parameters invalid after applying defaults"
          (deepgram/init! {:transcription/api-key "test-key"
                           :transcription/sample-rate 16000
                           :transcription/smart-format? true
                           :transcription/punctuate? true}))))

  (testing "Init with missing required field throws error"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Missing required parameters"
          (deepgram/init! {:transcription/sample-rate 16000})))))

(deftest processor-fn-test
  (testing "0 arity describe"
    (is (= (deepgram/processor-fn)
           {:ins {:sys-in "Channel for system messages that take priority"
                  :in "Channel for audio input frames (from transport-in)"}
            :outs {:sys-out "Channel for system messages that have priority"
                   :out "Channel on which transcription frames are put"}
            :params #:transcription{:model "Type: :enum"
                                    :utterance-end-ms "Type: integer; Optional "
                                    :punctuate? "Type: boolean"
                                    :smart-format? "Type: boolean; Optional "
                                    :language "Type: :enum"
                                    :interim-results? "Type: boolean; Optional "
                                    :api-key "Type: string"
                                    :vad-events? "Type: boolean; Optional "
                                    :supports-interrupt? "Type: boolean; Optional "
                                    :profanity-filter? "Type: boolean; Optional "}
            :workload :io})))

  (testing "1 arity init throws on invalid config"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Parameters invalid after applying defaults"
          (deepgram/processor-fn {:transcription/api-key "test-key"
                                  :transcription/sample-rate 16000
                                  :transcription/smart-format? true
                                  :transcription/punctuate? true}))))

  (testing "2 arity transition"
    (let [alive-atom (atom true)
          state {:websocket/conn nil
                 :websocket/alive? alive-atom
                 ::flow/in-ports {:ws-read nil}
                 ::flow/out-ports {:ws-write nil}}]
      (testing "Transition always returns back the state"
        (is (= (deepgram/processor-fn state ::flow/stop) state)))
      (testing "Alive state is set to false on stop"
        (is (false? @alive-atom)))))

  (testing "3 arity transform"
    (let [state {:some :state}
          audio-frame (frame/audio-input-raw (byte-array [1 2 3 4]))]
      (is (= (deepgram/processor-fn state :in audio-frame)
             [state {:ws-write [audio-frame]}]))))

  (testing "3 arity transform with WebSocket message"
    (let [state {:some :state}
          json-msg (u/json-str final-transcript-event)]
      (is (= 2 (count (deepgram/processor-fn state :ws-read json-msg))))
      (let [[new-state outputs] (deepgram/processor-fn state :ws-read json-msg)]
        (is (= state new-state))
        (is (= 1 (count (:out outputs))))
        (is (frame/transcription? (first (:out outputs))))))))
