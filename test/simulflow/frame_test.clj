(ns simulflow.frame-test
  "Comprehensive tests for the simulflow.frame namespace"
  (:require

   [simulflow.frame :as frame]
   [clojure.test :refer [are deftest is testing]])
  (:import
   (java.util Date)))

;; Test Data
(def test-audio-data (byte-array [1 2 3 4 5]))
(def test-timestamp #inst "2024-10-05T05:00:00.000Z")
(def test-timestamp-ms 1728104400000)

;; Core Frame Functions Tests

(deftest test-frame?
  (testing "frame? predicate"
    (let [valid-frame (frame/create-frame ::test-frame "data")
          invalid-frame {:frame/type ::test-frame :frame/data "data"}]
      (is (frame/frame? valid-frame) "Should recognize valid frames")
      (is (not (frame/frame? invalid-frame)) "Should reject frames without proper metadata")
      (is (not (frame/frame? nil)) "Should reject nil")
      (is (not (frame/frame? {})) "Should reject empty maps")
      (is (not (frame/frame? "string")) "Should reject non-maps"))))

(deftest test-normalize-timestamp
  (testing "timestamp normalization"
    (is (= 1728104400000 (frame/normalize-timestamp 1728104400000))
        "Should pass through integer milliseconds")

    (is (= 1728104400000 (frame/normalize-timestamp test-timestamp))
        "Should convert java.util.Date to milliseconds")

    (is (thrown? Exception (frame/normalize-timestamp "invalid"))
        "Should throw on invalid timestamp types")

    (is (thrown? Exception (frame/normalize-timestamp nil))
        "Should throw on nil timestamp")))

(deftest test-timestamp->date
  (testing "timestamp to Date conversion"
    (let [result-from-millis (frame/timestamp->date 1728104400000)
          result-from-date (frame/timestamp->date test-timestamp)]

      (is (instance? Date result-from-millis)
          "Should return Date from milliseconds")

      (is (= 1728104400000 (.getTime result-from-millis))
          "Should preserve timestamp value from milliseconds")

      (is (instance? Date result-from-date)
          "Should return Date from Date")

      (is (= test-timestamp result-from-date)
          "Should return same Date object")

      (is (thrown? Exception (frame/timestamp->date "invalid"))
          "Should throw on invalid timestamp types"))))

(deftest test-create-frame
  (testing "frame creation"
    (testing "with default timestamp"
      (let [frame (frame/create-frame ::test-frame "test-data")]
        (is (frame/frame? frame) "Should create valid frame")
        (is (= ::test-frame (:frame/type frame)) "Should set correct type")
        (is (= "test-data" (:frame/data frame)) "Should set correct data")
        (is (inst? (:frame/ts frame)) "Should have integer timestamp")))

    (testing "with explicit millisecond timestamp"
      (let [frame (frame/create-frame ::test-frame "test-data" {:timestamp test-timestamp-ms})]
        (is (= test-timestamp-ms (.getTime (:frame/ts frame))) "Should use provided timestamp")))

    (testing "with explicit Date timestamp"
      (let [frame (frame/create-frame ::test-frame "test-data" {:timestamp test-timestamp})]
        (is (= test-timestamp (:frame/ts frame)) "Should normalize Date to milliseconds")))

    (testing "with invalid timestamp"
      (is (thrown? Exception
                   (frame/create-frame ::test-frame "test-data" {:timestamp "invalid"}))
          "Should throw on invalid timestamp"))))

;; System Frame Tests

(deftest test-system-frame?
  (testing "system frame detection"
    (are [frame-fn data expected] (= expected (frame/system-frame? (frame-fn data)))
      frame/system-start true true
      frame/system-stop true true
      frame/user-speech-start true true
      frame/user-speech-stop true true
      frame/control-interrupt-start true true
      frame/control-interrupt-stop true true
      frame/bot-speech-start true true
      frame/bot-speech-stop true true
      frame/vad-user-speech-start true true
      frame/vad-user-speech-stop true true
      frame/system-config-change {:pipeline/language :es} true
      ;; Non-system frames
      frame/audio-input-raw test-audio-data false
      frame/llm-text-chunk "test" false
      frame/transcription "test" false)))

;; Frame Creator Function Tests

(deftest test-system-frames
  (testing "system frame creation and predicates"
    (testing "system-start"
      (let [frame (frame/system-start true)]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/system-start (:frame/type frame)))
        (is (= true (:frame/data frame)))
        (is (frame/system-start? frame))
        (is (not (frame/system-stop? frame)))))

    (testing "system-stop"
      (let [frame (frame/system-stop false)]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/system-stop (:frame/type frame)))
        (is (= false (:frame/data frame)))
        (is (frame/system-stop? frame))
        (is (not (frame/system-start? frame)))))

    (testing "system-error"
      (let [frame (frame/system-error "error message")]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/system-error (:frame/type frame)))
        (is (= "error message" (:frame/data frame)))
        (is (frame/system-error? frame))))))

(deftest test-audio-frames
  (testing "audio frame creation and predicates"
    (testing "audio-input-raw"
      (let [frame (frame/audio-input-raw test-audio-data)]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/audio-input-raw (:frame/type frame)))
        (is (= test-audio-data (:frame/data frame)))
        (is (frame/audio-input-raw? frame))
        (is (not (frame/audio-output-raw? frame)))))

    (testing "audio-output-raw"
      (let [frame (frame/audio-output-raw {:audio test-audio-data
                                           :sample-rate 16000})]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/audio-output-raw (:frame/type frame)))
        (is (frame/audio-output-raw? frame))
        (is (not (frame/audio-input-raw? frame)))))

    (testing "audio-tts-raw"
      (let [frame (frame/audio-tts-raw test-audio-data)]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/audio-tts-raw (:frame/type frame)))
        (is (frame/audio-tts-raw? frame))))))

(deftest test-transcription-frames
  (testing "transcription frame creation and predicates"
    (testing "transcription"
      (let [frame (frame/transcription "Hello world")]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/transcription-result (:frame/type frame)))
        (is (= "Hello world" (:frame/data frame)))
        (is (frame/transcription? frame))
        (is (not (frame/transcription-interim? frame)))))

    (testing "transcription-interim"
      (let [frame (frame/transcription-interim "Hello...")]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/transcription-interim (:frame/type frame)))
        (is (= "Hello..." (:frame/data frame)))
        (is (frame/transcription-interim? frame))
        (is (not (frame/transcription? frame)))))))

(deftest test-llm-frames
  (testing "LLM frame creation and predicates"
    (testing "llm-text-chunk"
      (let [frame (frame/llm-text-chunk "Response chunk")]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/llm-text-chunk (:frame/type frame)))
        (is (= "Response chunk" (:frame/data frame)))
        (is (frame/llm-text-chunk? frame))
        (is (not (frame/llm-tool-call-chunk? frame)))))

    (testing "llm-tool-call-chunk"
      (let [frame (frame/llm-tool-call-chunk "tool chunk")]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/llm-tool-call-chunk (:frame/type frame)))
        (is (frame/llm-tool-call-chunk? frame))
        (is (not (frame/llm-text-chunk? frame)))))

    (testing "llm-text-sentence"
      (let [frame (frame/llm-text-sentence "Complete sentence.")]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/llm-text-sentence (:frame/type frame)))
        (is (frame/llm-text-sentence? frame))))

    (testing "llm-full-response-start/end"
      (let [start-frame (frame/llm-full-response-start true)
            end-frame (frame/llm-full-response-end true)]
        (is (= :simulflow.frame/llm-response-start (:frame/type start-frame)))
        (is (= :simulflow.frame/llm-response-end (:frame/type end-frame)))
        (is (frame/llm-full-response-start? start-frame))
        (is (frame/llm-full-response-end? end-frame))))))

(deftest test-user-interaction-frames
  (testing "user interaction frame creation and predicates"
    (testing "user-speech-start"
      (let [frame (frame/user-speech-start true)]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/user-speech-start (:frame/type frame)))
        (is (= true (:frame/data frame)))
        (is (frame/user-speech-start? frame))
        (is (not (frame/user-speech-stop? frame)))))

    (testing "user-speech-stop"
      (let [frame (frame/user-speech-stop false)]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/user-speech-stop (:frame/type frame)))
        (is (= false (:frame/data frame)))
        (is (frame/user-speech-stop? frame))
        (is (not (frame/user-speech-start? frame)))))))

(deftest test-bot-interaction-frames
  (testing "bot interaction frame creation and predicates"
    (testing "bot-speech-start"
      (let [frame (frame/bot-speech-start true)]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/bot-speech-start (:frame/type frame)))
        (is (frame/bot-speech-start? frame))
        (is (not (frame/bot-speech-stop? frame)))))

    (testing "bot-speech-stop"
      (let [frame (frame/bot-speech-stop true)]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/bot-speech-stop (:frame/type frame)))
        (is (frame/bot-speech-stop? frame))
        (is (not (frame/bot-speech-start? frame)))))))

(deftest test-control-frames
  (testing "control frame creation and predicates"
    (testing "control-interrupt-start"
      (let [frame (frame/control-interrupt-start true)]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/control-interrupt-start (:frame/type frame)))
        (is (frame/control-interrupt-start? frame))))

    (testing "control-interrupt-stop"
      (let [frame (frame/control-interrupt-stop true)]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/control-interrupt-stop (:frame/type frame)))
        (is (frame/control-interrupt-stop? frame))))))

(deftest test-text-frames
  (testing "text frame creation and predicates"
    (testing "speak-frame"
      (let [frame (frame/speak-frame "Hello, world!")]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/speak-frame (:frame/type frame)))
        (is (= "Hello, world!" (:frame/data frame)))
        (is (frame/speak-frame? frame))))

    (testing "text-input"
      (let [frame (frame/text-input "User input")]
        (is (frame/frame? frame))
        (is (= :simulflow.frame/text-input (:frame/type frame)))
        (is (= "User input" (:frame/data frame)))
        (is (frame/text-input? frame))))))

;; Frame Type Namespace Tests

(deftest test-frame-type-namespacing
  (testing "frame types use simulflow.frame namespace"
    (let [frames [(frame/system-start true)
                  (frame/user-speech-start true)
                  (frame/audio-input-raw test-audio-data)
                  (frame/transcription "test")
                  (frame/llm-text-chunk "test")
                  (frame/speak-frame "test")]]

      (is (every? #(= "simulflow.frame" (namespace (:frame/type %))) frames)
          "All frame types should use simulflow.frame namespace")

      (is (every? #(string? (name (:frame/type %))) frames)
          "All frame type names should be strings"))))

;; Edge Cases and Error Handling

(deftest test-edge-cases
  (testing "edge cases and error handling"
    (testing "nil data"
      (let [frame (frame/llm-text-chunk nil)]
        (is (frame/frame? frame))
        (is (nil? (:frame/data frame)))))

    (testing "empty data structures"
      (are [data] (frame/frame? (frame/llm-text-chunk data))
        ""
        []
        {}))

    (testing "large data"
      (let [large-text (apply str (repeat 10000 "x"))
            frame (frame/transcription large-text)]
        (is (frame/frame? frame))
        (is (= large-text (:frame/data frame)))))

    (testing "frame metadata preservation"
      (let [frame (frame/user-speech-start true)]
        (is (= ::frame/frame (:type (meta frame)))
            "Should preserve frame metadata type")))))

;; Schema Validation Tests (when enabled)

(deftest test-schema-validation
  (testing "schema validation integration"
    ;; Note: These tests depend on schema checking being enabled
    ;; In production, schema validation can be turned on/off
    (testing "timestamp schema validation"
      ;; Test that our Timestamp schema accepts valid types
      (is (frame/frame? (frame/user-speech-start true {:timestamp 1234567890}))
          "Should accept integer timestamps")

      (is (frame/frame? (frame/user-speech-start true {:timestamp test-timestamp}))
          "Should accept Date timestamps"))

    (testing "frame structure"
      (let [frame (frame/audio-input-raw test-audio-data)]
        (is (contains? frame :frame/type))
        (is (contains? frame :frame/data))
        (is (contains? frame :frame/ts))))))

;; Integration Tests

(deftest test-frame-workflow
  (testing "typical frame workflow scenarios"
    (testing "conversation flow"
      (let [frames [(frame/user-speech-start true {:timestamp test-timestamp-ms})
                    (frame/transcription "Hello" {:timestamp (+ test-timestamp-ms 1000)})
                    (frame/user-speech-stop true {:timestamp (+ test-timestamp-ms 2000)})
                    (frame/llm-text-chunk "Hi there!" {:timestamp (+ test-timestamp-ms 3000)})]]

        (is (every? frame/frame? frames)
            "All frames should be valid")

        (is (= [#inst "2024-10-05T05:00:00.000-00:00"
                #inst "2024-10-05T05:00:01.000-00:00"
                #inst "2024-10-05T05:00:02.000-00:00"
                #inst "2024-10-05T05:00:03.000-00:00"]
               (map :frame/ts frames))
            "Timestamps should be preserved in order")))

    (testing "audio processing flow"
      (let [input-frame (frame/audio-input-raw test-audio-data)
            output-frame (frame/audio-output-raw {:audio test-audio-data
                                                  :sample-rate 16000})]

        (is (frame/audio-input-raw? input-frame))
        (is (frame/audio-output-raw? output-frame))
        (is (not (frame/audio-input-raw? output-frame)))
        (is (not (frame/audio-output-raw? input-frame)))))

    (testing "system lifecycle"
      (let [start-frame (frame/system-start true)
            stop-frame (frame/system-stop true)]

        (is (frame/system-frame? start-frame))
        (is (frame/system-frame? stop-frame))
        (is (not= (:frame/type start-frame) (:frame/type stop-frame)))))))

;; Performance Tests

(deftest test-performance-characteristics
  (testing "frame creation performance"
    (testing "predicate functions are fast"
      (let [frame (frame/user-speech-start true)]
        ;; These should be very fast operations
        (dotimes [_ 1000]
          (is (frame/user-speech-start? frame))
          (is (not (frame/user-speech-stop? frame))))))

    (testing "frame creation is efficient"
      ;; Should be able to create many frames quickly
      (let [frames (doall (repeatedly 100 #(frame/llm-text-chunk "test")))]
        (is (= 100 (count frames)))
        (is (every? frame/llm-text-chunk? frames))))))
