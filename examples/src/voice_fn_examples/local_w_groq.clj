(ns voice-fn-examples.local-w-groq
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [taoensso.telemere :as t]
   [voice-fn-examples.local :as local]
   [voice-fn.processors.groq :as groq]
   [voice-fn.secrets :refer [secret]]))

(comment

  (def local-flow-groq (local/make-local-flow {:extra-procs {:llm {:proc groq/groq-llm-process
                                                                   :args {:llm/model "llama-3.3-70b-versatile"
                                                                          :groq/api-key (secret [:groq :api-key])}}}
                                               :llm-context {:messages
                                                             [{:role "system"
                                                               :content "You are a voice agent operating via phone. Be
                       concise in your answers. The input you receive comes from a
                       speech-to-text (transcription) system that isn't always
                       efficient and may send unclear text. Ask for
                       clarification when you're unsure what the person said."}]
                                                             :tools []}}))

  (defonce flow-started? (atom false))

  ;; Start local ai flow - starts paused
  (let [{:keys [report-chan error-chan]} (flow/start local-flow-groq)]
    (reset! flow-started? true)
    ;; Resume local ai -> you can now speak with the AI
    (flow/resume local-flow-groq)
    (a/thread
      (loop []
        (when @flow-started?
          (when-let [[msg c] (a/alts!! [report-chan error-chan])]
            (when (map? msg)
              (t/log! {:level :debug :id (if (= c error-chan) :error :report)} msg))
            (recur))))))

  ;; Stop the conversation
  (do
    (flow/stop local-flow-groq)
    (reset! flow-started? false))

  ,)
