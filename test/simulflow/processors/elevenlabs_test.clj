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
  (testing ""))

(deftest elevenlabs-tts
  (testing "Sends speak frames as json payloads to the websocket connection for generating speech"
    (is (= (elevenlabs/tts-transform {} :in (frame/speak-frame "Test speech"))
           [{} {::elevenlabs/ws-write ["{\"text\":\"Test speech \",\"flush\":true}"]}])))
  (testing "Keeps incomplete websocket results until they can be parsed"
    (is (= (elevenlabs/tts-transform {::elevenlabs/accumulator ""} ::elevenlabs/ws-read incomplete-result-json )
           [{::elevenlabs/accumulator incomplete-result-json}])))
  (testing "Parses completed JSON results and sends results to out"
    (let [[state {[audio-out-frame xi-frame] :out}] (elevenlabs/tts-transform
                                                    {::elevenlabs/accumulator incomplete-result-json
                                                     :now current-time}
                                                    ::elevenlabs/ws-read
                                                    rest-of-json)]
      (is (= state {::elevenlabs/accumulator "" :now current-time}))
      (is (= xi-frame (frame/xi-audio-out test-result {:timestamp current-time})))
      (is (update-in audio-out-frame [:frame/data] vec) {:frame/type ::frame/audio-output-raw
                                                         :frame/ts current-time
                                                         :frame/data (vec (range 20))}))))
