(ns simulflow.utils.openai
  "Common logic for openai format requests. Many LLM providers use openai format
  for their APIs. This NS keeps common logic for those providers."
  (:require
   [clojure.core.async :as a]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]))

(def response-chunk-delta
  "Retrieve the delta part of a streaming completion response"
  (comp :delta first :choices))

(defn handle-completion-request!
  "Handle completion requests for OpenAI LLM models"
  [in-c out-c]

  (vthread-loop []
    (when-let [chunk (a/<!! in-c)]
      (let [d (response-chunk-delta chunk)]
        (if (= chunk :done)
          (a/>!! out-c (frame/llm-full-response-end true))
          (do
            (if-let [tool-call (first (:tool_calls d))]
              (a/>!! out-c (frame/llm-tool-call-chunk tool-call))
              ;; normal text completion
              (when-let [c (:content d)]
                (a/>!! out-c (frame/llm-text-chunk c))))
            (recur)))))))
