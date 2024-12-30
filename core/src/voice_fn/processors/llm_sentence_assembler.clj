(ns voice-fn.processors.llm-sentence-assembler
  (:require
   [clojure.core.async :as a]
   [voice-fn.frames :as frames]
   [voice-fn.pipeline :as pipeline]))

(def default-end-sentence-mather #"[.?!]")

(defmethod pipeline/process-frame :llm/sentence-assembler
  [processor-type pipeline {:processor/keys [config]} {:frame/keys [data type]}]
  (let [end-sentence-matcher (:sentence/end-matcher config default-end-sentence-mather)
        sentence (get-in @pipeline [processor-type :sentence] "")]
    (case type
      :llm/output-text-chunk
      (if (re-find end-sentence-matcher data)
        (do
          (swap! pipeline assoc-in [processor-type :sentence] "")
          (a/put! (:pipeline/main-ch @pipeline)
                  (frames/llm-output-text-sentence-frame (str sentence data))))
        (swap! pipeline assoc-in [processor-type :sentence] (str sentence data)))
      nil)))
