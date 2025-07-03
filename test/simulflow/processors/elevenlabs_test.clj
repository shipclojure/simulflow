(ns simulflow.processors.elevenlabs-test
  (:require [clojure.test :refer [deftest is testing]]
            [simulflow.frame :as frame]
            [simulflow.processors.elevenlabs :as elevenlabs]
            [simulflow.utils.core :as u]))

(def audio-base-64 (u/encode-base64 (byte-array (range 20))))

(def test-result {:audio audio-base-64
                  :alignment nil
                  :isFinal true})
(def test-result-json (u/json-str test-result))

(def incomplete-result-json (subs test-result-json 0 12))
(def rest-of-json (subs test-result-json 12))

(def current-time #inst "2025-06-27T13:04:02.717-00:00")

(deftest tts-schema
  (testing "TTS schema validation"
    (is (true? true)))) ; Placeholder test - this should be implemented with actual schema validation

(deftest elevenlabs-tts
  (testing "Sends speak frames as json payloads to the websocket connection for generating speech"
    (is (= (elevenlabs/elevenlabs-tts-transform {} :in (frame/speak-frame "Test speech"))
           [{} {::elevenlabs/ws-write ["{\"text\":\"Test speech \",\"flush\":true}"]}])))
  (testing "Keeps incomplete websocket results until they can be parsed"
    (is (= (elevenlabs/elevenlabs-tts-transform {::elevenlabs/accumulator ""} ::elevenlabs/ws-read incomplete-result-json)
           [{::elevenlabs/accumulator incomplete-result-json} {}])))
  (testing "Parses completed JSON results and sends results to out"
    (let [[state {[audio-out-frame xi-frame] :out}] (elevenlabs/elevenlabs-tts-transform
                                                     {::elevenlabs/accumulator incomplete-result-json
                                                      :now current-time}
                                                     ::elevenlabs/ws-read
                                                     rest-of-json)]
      (is (= state {::elevenlabs/accumulator "" :now current-time}))
      (is (= xi-frame (frame/xi-audio-out test-result {:timestamp current-time})))
      (is (update-in audio-out-frame [:frame/data] vec) {:frame/type ::frame/audio-output-raw
                                                         :frame/ts current-time
                                                         :frame/data (vec (range 20))}))))

(deftest test-accumulate-json-response
  (testing "accumulates partial JSON correctly"
    (is (= (elevenlabs/accumulate-json-response "" "{\"partial\":")
           ["{\"partial\":" nil]))
    (is (= (elevenlabs/accumulate-json-response "{\"partial\":" " \"data\": true}")
           ["{\"partial\": \"data\": true}" nil])))

  (testing "parses complete JSON and resets accumulator"
    (is (= (elevenlabs/accumulate-json-response "" "{\"audio\": \"test\"}")
           ["" {:audio "test"}]))
    (is (= (elevenlabs/accumulate-json-response "{\"audio\": \"d" "GVzdA==\"}")
           ["" {:audio "dGVzdA=="}])))

  (testing "handles invalid JSON gracefully"
    (is (= (elevenlabs/accumulate-json-response "" "not json")
           ["not json" nil]))))

(deftest test-process-completed-json
  (testing "creates audio frames from valid JSON"
    (let [json {:audio "dGVzdA==" :alignment nil :isFinal true}
          timestamp #inst "2025-01-01"
          [audio-frame xi-frame] (elevenlabs/process-completed-json json timestamp)]
      (is (= (:frame/type audio-frame) :simulflow.frame/audio-output-raw))
      (is (= (vec (:frame/data audio-frame)) [116 101 115 116])) ; "test" in bytes
      (is (= (:frame/ts audio-frame) timestamp))
      (is (= (:frame/type xi-frame) :simulflow.frame/xi-audio-out))
      (is (= (:frame/data xi-frame) json))))

  (testing "returns nil for JSON without audio"
    (is (nil? (elevenlabs/process-completed-json {} #inst "2025-01-01")))
    (is (nil? (elevenlabs/process-completed-json {:other "data"} #inst "2025-01-01")))))

(deftest test-process-speak-frame
  (testing "converts speak frame to WebSocket message"
    (let [speak-frame (frame/speak-frame "Hello world")
          message (elevenlabs/process-speak-frame speak-frame)]
      (is (= message "{\"text\":\"Hello world \",\"flush\":true}"))))

  (testing "handles empty speak frame"
    (let [speak-frame (frame/speak-frame "")
          message (elevenlabs/process-speak-frame speak-frame)]
      (is (= message "{\"text\":\" \",\"flush\":true}")))))

(deftest test-process-websocket-message
  (testing "accumulates partial messages"
    (let [state {::elevenlabs/accumulator ""}
          partial-msg "{\"audio\": \"dGVz"
          [new-state output] (elevenlabs/process-websocket-message state partial-msg #inst "2025-01-01")]
      (is (= (::elevenlabs/accumulator new-state) "{\"audio\": \"dGVz"))
      (is (= output {}))))

  (testing "completes and processes full JSON"
    (let [state {::elevenlabs/accumulator "{\"audio\": \"dGVz"}
          completion "dA==\", \"alignment\": null, \"isFinal\": true}"
          timestamp #inst "2025-01-01"
          [new-state output] (elevenlabs/process-websocket-message state completion timestamp)]
      (is (= (::elevenlabs/accumulator new-state) ""))
      (is (= (count (:out output)) 2))
      (is (= (:frame/type (first (:out output))) :simulflow.frame/audio-output-raw))))

  (testing "processes complete JSON in single message"
    (let [state {::elevenlabs/accumulator ""}
          complete-msg "{\"audio\": \"dGVzdA==\", \"alignment\": null, \"isFinal\": true}"
          timestamp #inst "2025-01-01"
          [new-state output] (elevenlabs/process-websocket-message state complete-msg timestamp)]
      (is (= (::elevenlabs/accumulator new-state) ""))
      (is (= (count (:out output)) 2)))))

(deftest test-elevenlabs-tts-transform
  (testing "delegates to correct pure functions"
    ;; Test speak frame processing
    (let [speak-frame (frame/speak-frame "Test")
          [_ output] (elevenlabs/elevenlabs-tts-transform {} :in speak-frame)]
      (is (= output {::elevenlabs/ws-write ["{\"text\":\"Test \",\"flush\":true}"]})))

    ;; Test WebSocket message processing
    (let [ws-msg "{\"audio\": \"dGVzdA==\", \"alignment\": null, \"isFinal\": true}"
          state {::elevenlabs/accumulator "" :now #inst "2025-01-01"}
          [new-state output] (elevenlabs/elevenlabs-tts-transform state ::elevenlabs/ws-read ws-msg)]
      (is (= (::elevenlabs/accumulator new-state) ""))
      (is (= (count (:out output)) 2)))

    ;; Test unknown input port
    (let [[state output] (elevenlabs/elevenlabs-tts-transform {} :unknown-port "message")]
      (is (= state {}))
      (is (nil? output)))))
