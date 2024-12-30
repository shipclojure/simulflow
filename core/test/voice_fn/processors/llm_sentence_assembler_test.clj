(ns voice-fn.processors.llm-sentence-assembler-test
  (:require
   [clojure.core.async :as a]
   [clojure.test :refer [deftest is testing]]
   [voice-fn.frames :as f]
   [voice-fn.pipeline :as pipeline]))

(deftest sentence-assembler
  (testing "Assembles sentences correctly"
    (let [pipeline (atom {:pipeline/main-ch (a/chan 1024)})
          processor-config {:processor/config {:sentence/end-matcher #"[.?!]"}}
          chunk-frames (map f/llm-output-text-chunk-frame ["Hel" "lo" ", my " "name" " is" " Jo" "hn!"])]
      (doseq [frame chunk-frames]
        (pipeline/process-frame :llm/sentence-assembler pipeline processor-config frame))
      (let [sentence-frame (a/<!! (:pipeline/main-ch @pipeline))]
        (is (f/llm-output-text-sentence-frame? sentence-frame))
        (is (= (:frame/data sentence-frame) "Hello, my name is John!")))
      (testing "Handling other types of frames"
        (is (nil? (pipeline/process-frame :llm/sentence-assembler pipeline processor-config f/start-frame))))))
  ;; TODO
  (testing "Assembles sentences correctly when token starts with punctuation"))
