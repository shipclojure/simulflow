(ns simulflow.transport.text-out
  (:refer-clojure :exclude [send])
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [taoensso.telemere :as t]))

(def ^:private default-config
  {:text-out/response-prefix "Assistant: "
   :text-out/response-suffix ""
   :text-out/show-thinking false
   :text-out/user-prompt "You: "
   :text-out/manage-prompts true})

(defn text-output-transform
  "Pure transform function - handles text output logic and emits commands"
  [state _input-port frame]
  (let [{:text-out/keys [response-prefix response-suffix show-thinking
                         user-prompt]} state]
    (cond
      ;; LLM response start - show prefix, clean formatting
      (frame/llm-full-response-start? frame)
      [(assoc state :response-active? true)
       {::command [{:command/kind :command/print
                    :command/data response-prefix}]}]
      ;; LLM response end - show suffix and prepare for next input
      (frame/llm-full-response-end? frame)
      [(assoc state :response-active? false)
       (cond-> {::command [{:command/kind :command/print
                            :command/data response-suffix}
                           {:command/kind :command/println
                            :command/data ""} ; newline
                           {:command/kind :command/print
                            :command/data user-prompt}]})]
      ;; Stream LLM text chunks
      (frame/llm-text-chunk? frame)
      [state {::command [{:command/kind :command/print
                          :command/data (:frame/data frame)}]}]


      (frame/speak-frame? frame)
      [state {::command [{:command/kind :command/println
                          :command/data ""}
                         {:command/kind :command/print
                          :command/data response-prefix}
                         {:command/kind :command/println
                          :command/data (:frame/data frame)}
                         {:command/kind :command/print
                          :command/data user-prompt}]}]
      ;; Handle tool calls quietly unless in debug mode
      (frame/llm-tool-call-chunk? frame)
      [state (if show-thinking
               {::command [{:command/kind :command/debug
                            :command/data (str "Tool call: " (:frame/data frame))}]}
               {})]
      :else [state {}])))

(defn text-output-init!
  "Initialize text output processor with command handling loop"
  [params]
  (let [config (merge default-config params)
        {:text-out/keys [user-prompt manage-prompts]} config
        command-ch (a/chan 32)
        running? (atom true)
        ;; Close function to be called on stop
        close-fn (fn []
                   (reset! running? false)
                   (a/close! command-ch))]
    ;; Start command handling loop in virtual thread
    (vthread-loop []
      (when @running?
        (try
          (when-let [command (a/<!! command-ch)]
            (when @running?
              (case (:command/kind command)
                :command/print
                (do (print (:command/data command))
                    (flush))
                :command/println
                (println (:command/data command))
                :command/debug
                (t/log! {:level :debug :id :text-output} (:command/data command))
                (t/log! {:level :warn :id :text-output}
                        (str "Unknown command: " (:command/kind command))))))
          (catch Exception e
            (when @running?
              (t/log! {:level :error :id :text-output :error e} "Error processing command")
              (Thread/sleep 100))))
        (recur)))
    ;; Show initial prompt if managing prompts
    (when manage-prompts
      (print user-prompt)
      (flush))
    ;; Set up state with channels and config  
    (merge config
           {::flow/out-ports {::command command-ch}
            ::close close-fn
            :response-active? false})))

(defn text-output-processor
  "Multi-arity text output processor following simulflow patterns"
  ([]
   ;; Describe processor
   {:ins {:in "LLM output frames and user speech frames"
          :sys-in "System frames"}
    :outs {:out "Processed frames (passthrough)"
           :sys-out "System frames"}
    :params {:text-out/response-prefix "Text to show before LLM response"
             :text-out/response-suffix "Text to show after LLM response"
             :text-out/show-thinking "Show thinking indicators"
             :text-out/user-prompt "Prompt to show for user input"
             :text-out/manage-prompts "Whether to manage user prompts"}})
  ([config]
   ;; Init processor
   (text-output-init! config))
  ([state transition]
   ;; Handle transitions
   (case transition
     ::flow/stop (when-let [close-fn (::close state)]
                   (close-fn))
     state))
  ([state input-port frame]
   ;; Transform function
   (text-output-transform state input-port frame)))

;; Export for use in flows
(def text-output-process
  (flow/process text-output-processor))
