(ns voice-fn.processors.llm-sentence-assembler
  (:require
   [clojure.core.async :as a]
   [taoensso.telemere :as t]
   [voice-fn.frames :as frames]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.schema :as schema]))

(def default-end-sentence-matcher #"[.?!;:]")

(def SentenceAssemblerConfig
  [:map
   {:closed true
    :description "Configuration for sentence assembly and detection"}
   [:sentence/end-matcher
    [:fn
     {:error/message "Must be a valid regex patter (java.util.regex.Pattern)"
      :default #"[.?!;:]"
      :description "Regular expression pattern for detecting sentence endings"}
     schema/regex?]]])

(defmethod pipeline/processor-schema :llm/sentence-assembler
  [_]
  SentenceAssemblerConfig)

(defmethod pipeline/process-frame :llm/sentence-assembler
  [processor-type pipeline {:processor/keys [config]} {:frame/keys [data type]}]
  (let [end-sentence-matcher (:sentence/end-matcher config default-end-sentence-matcher)
        sentence (get-in @pipeline [processor-type :sentence] "")]
    (case type
      :llm/output-text-chunk
      (if (re-find end-sentence-matcher data)
        (let [full-sentence (str sentence data)]
          (t/log! :debug ["Full sentence" full-sentence])
          (swap! pipeline assoc-in [processor-type :sentence] "")
          (a/put! (:pipeline/main-ch @pipeline)
                  (frames/llm-output-text-sentence-frame full-sentence)))
        (swap! pipeline assoc-in [processor-type :sentence] (str sentence data)))
      nil)))
