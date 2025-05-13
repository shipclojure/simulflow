(ns simulflow.processors.groq
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [hato.client :as http]
   [malli.core :as m]
   [malli.transform :as mt]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]
   [simulflow.secrets :refer [secret]]
   [simulflow.utils.core :as u]
   [simulflow.utils.request :as request]
   [taoensso.telemere :as t]))

(def groq-api-url "https://api.groq.com/openai/v1")
(def groq-completions-url (str groq-api-url "/chat/completions"))

(def GroqLLMConfigSchema
  [:map
   {:closed true
    :description "Groq LLM configuration"}

   [:llm/model
    (schema/flex-enum
      {:description "Groq model identifier"
       :error/message "Must be a valid Groq model"
       :default "llama-3.3-70b-versatile"}
      ["llama-3.2-3b-preview"
       "llama-3.1-8b-instant"
       "llama-3.3-70b-versatile"
       "llama-3.2-11b-vision-preview"
       "whisper-large-v3-turbo"
       "llama-3.1-70b-versatile"
       "llama3-8b-8192"
       "llama3-70b-8192"
       "llama-guard-3-8b"
       "whisper-large-v3"
       "llama-3.2-1b-preview"
       "mixtral-8x7b-32768"
       "gemma2-9b-it"
       "llama-3.2-90b-vision-preview"
       "llama-3.3-70b-specdec"
       "distil-whisper-large-v3-en"])]

   [:groq/api-key
    [:string
     {:description "Groq API key"
      :secret true ;; Marks this as sensitive data
      :min 40      ;; Groq API keys are typically longer
      :error/message "Invalid Groq API key format"}]]])

(defn flow-do-completion!
  "Handle completion requests for Groq LLM models"
  [state out-c context]
  (let [{:llm/keys [model] :groq/keys [api-key]} state]
    ;; Start request only when the last message in the context is by the user

    (a/>!! out-c (frame/llm-full-response-start true))
    (let [stream-ch (try (stream-groq-chat-completion (merge {:model model
                                                              :api-key api-key
                                                              :messages (:messages context)
                                                              :tools (mapv u/->tool-fn (:tools context))}))
                         (catch Exception e
                           (t/log! :error e)))]

      (a/go-loop []
        (when-let [chunk (a/<! stream-ch)]
          (let [d (delta chunk)]
            (if (= chunk :done)
              (a/>! out-c (frame/llm-full-response-end true))
              (do
                (if-let [tool-call (first (:tool_calls d))]
                  (do
                    (t/log! ["SENDING TOOL CALL" tool-call])
                    (a/>! out-c (frame/llm-tool-call-chunk tool-call)))
                  (when-let [c (:content d)]
                    (a/>! out-c (frame/llm-text-chunk c))))
                (recur)))))))))

(defn tool-result-adapter
  "Transform tool results to the groq format"
  [{:keys [result tool-id fname]}]
  {:role :tool
   :name fname
   :content (if (string? result) result (u/json-str result))
   :tool_call_id tool-id})

(def groq-llm-process
  (flow/process
    (flow/map->step
      {:describe (fn [] {:ins {:in "Channel for incoming context aggregations"}
                         :outs {:out "Channel where streaming responses will go"}
                         :params {:llm/model "Groq model used"
                                  :groq/api-key "Groq Api key"
                                  :llm/temperature "Optional temperature parameter for the llm inference"
                                  :llm/max-tokens "Optional max tokens to generate"
                                  :llm/presence-penalty "Optional (-2.0 to 2.0)"
                                  :llm/top-p "Optional nucleus sampling threshold"
                                  :llm/seed "Optional seed used for deterministic sampling"
                                  :llm/max-completion-tokens "Optional Max tokens in completion"}
                         :workload :io})

       :transition (fn [{::flow/keys [in-ports out-ports]} transition]
                     (when (= transition ::flow/stop)
                       (doseq [port (concat (vals in-ports) (vals out-ports))]
                         (a/close! port))))
       :init (fn [params]
               (let [state (m/decode GroqLLMConfigSchema params mt/default-value-transformer)
                     llm-write (a/chan 100)
                     llm-read (a/chan 1024)
                     write-to-llm #(loop []
                                     (if-let [frame (a/<!! llm-write)]
                                       (do
                                         (t/log! :info ["AI REQUEST" (:frame/data frame)])
                                         (assert (or (frame/llm-context? frame)
                                                     (frame/control-interrupt-start? frame)) "Invalid frame sent to LLM. Only llm-context or interrupt-start")
                                         (flow-do-completion! state llm-read (:frame/data frame))
                                         (recur))
                                       (t/log! {:level :info :id :llm} "Closing llm loop")))]
                 ((flow/futurize write-to-llm :exec :io))
                 {::flow/in-ports {:llm-read llm-read}
                  ::flow/out-ports {:llm-write llm-write}}))

       :transform (fn [state in msg]
                    (if (= in :llm-read)
                      [state {:out [msg]}]
                      (cond
                        (frame/llm-context? msg)
                        [state {:llm-write [msg]}])))})))
