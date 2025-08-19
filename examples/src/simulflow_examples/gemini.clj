(ns simulflow-examples.gemini
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [simulflow-examples.local :as local]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.processors.google :as google]
   [simulflow.secrets :refer [secret]]
   [simulflow.vad.silero :as silero]
   [taoensso.telemere :as t]))

(comment
  (def local-flow-gemini
    (local/make-local-flow {:extra-procs {:llm {:proc google/google-llm-process
                                                :args {:llm/model :gemini-2.0-flash
                                                       :google/api-key (secret [:google :api-key])}}}
                            :vad-analyser (silero/create-silero-vad)}))

  (defonce flow-started? (atom false))

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
