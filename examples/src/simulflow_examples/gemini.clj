(ns simulflow-examples.gemini
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [simulflow-examples.local :as local]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.processors.google :as google]
   [simulflow.secrets :refer [secret]]
   [taoensso.telemere :as t]))

(def local-flow-gemini
  (local/make-local-flow {:extra-procs {:llm {:proc google/google-llm-process
                                              :args {:llm/model :gemini-2.0-flash
                                                     :google/api-key (secret [:google :api-key])}}}
                          :llm-context {:messages
                                        [{:role "system"
                                          :content "You are a voice agent operating via phone. Be
                       concise in your answers. The input you receive comes from a
                       speech-to-text (transcription) system that isn't always
                       efficient and may send unclear text. Ask for
                       clarification when you're unsure what the person said."}]
                                        :tools []}}))

(defonce flow-started? (atom false))

(comment
  ;; Start local ai flow - starts paused
  (let [{:keys [report-chan error-chan]} (flow/start local-flow-gemini)]
    (reset! flow-started? true)
    ;; Resume local ai -> you can now speak with the AI
    (flow/resume local-flow-gemini)
    (vthread-loop []
      (when @flow-started?
        (when-let [[msg c] (a/alts!! [report-chan error-chan])]
          (when (map? msg)
            (t/log! {:level :debug :id (if (= c error-chan) :error :report)} msg))
          (recur)))))

  ;; Stop the conversation
  (do
    (flow/stop local-flow-gemini)
    (reset! flow-started? false))
  ,)
