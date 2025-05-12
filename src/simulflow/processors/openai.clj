(ns simulflow.processors.openai
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [malli.core :as m]
   [malli.transform :as mt]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]
   [simulflow.utils.core :as u]
   [simulflow.utils.request :as request]
   [taoensso.telemere :as t]))

(def openai-completions-url "https://api.openai.com/v1/chat/completions")

(def OpenAILLMConfigSchema
  [:map
   {:description "OpenAI LLM configuration"}

   [:llm/model
    (schema/flex-enum
      {:description "OpenAI model identifier"
       :error/message "Must be a valid OpenAI model"
       :default "gpt-4o-mini"}
      [;; GPT-4 Models
       "gpt-4"
       "gpt-4-32k"
       "gpt-4-1106-preview"   ;; GPT-4 Turbo
       "gpt-4-vision-preview" ;; GPT-4 Vision
       ;; GPT-3.5 Models
       "gpt-3.5-turbo"
       "gpt-3.5-turbo-16k"
       "gpt-3.5-turbo-1106"
       ;; Base Models
       "babbage-002"
       "davinci-002"
       ;; Include your custom model
       "gpt-4o-mini"])]
   [:llm/temperature {:optional true}
    [:float
     {:description "Sampling temperature (0-2)"
      :default 0.7
      :min 0.0
      :max 2.0}]]

   [:llm/max-tokens {:optional true}
    [:int
     {:description "Maximum number of tokens to generate"
      :min 1}]]

   [:llm/frequency-penalty {:optional true}
    [:float
     {:description "Frequency penalty (-2.0 to 2.0)"
      :min -2.0
      :max 2.0}]]

   [:llm/presence-penalty {:optional true}
    [:float
     {:description "Presence penalty (-2.0 to 2.0)"
      :min -2.0
      :max 2.0}]]

   [:llm/top-p {:optional true}
    [:float
     {:description "Nucleus sampling threshold"
      :min 0.0
      :max 1.0}]]

   [:llm/seed {:optional true}
    [:int
     {:description "Random seed for deterministic sampling"}]]

   [:llm/max-completion-tokens {:optional true}
    [:int
     {:description "Maximum tokens in completion"
      :min 1}]]

   [:llm/extra {:optional true}
    [:map
     {:description "Additional model parameters"}]]

   [:openai/api-key
    [:string
     {:description "OpenAI API key"
      :secret true ;; Marks this as sensitive data
      :min 40      ;; OpenAI API keys are typically longer
      :error/message "Invalid OpenAI API key format"}]]])

;; Example validation:
(comment
  (require
    '[malli.error :as me])
  ;; Valid config
  (m/validate OpenAILLMConfigSchema
              {:openai/api-key "sk-12312312312312312312312312312312312312312313..."})
  ;; => true

  ;; Invalid model
  (-> OpenAILLMConfigSchema
      (m/explain {:llm/model "invalid-model"
                  :openai/api-key "sk-..."})
      me/humanize)

  ,)

(def delta (comp :delta first :choices))

(defn flow-do-completion!
  "Handle completion requests for OpenAI LLM models"
  [state out-c context]
  (let [{:llm/keys [model] :openai/keys [api-key]} state]
    ;; Start request only when the last message in the context is by the user

    (a/>!! out-c (frame/llm-full-response-start true))
    (let [stream-ch (try (request/stream-chat-completion (merge {:model model
                                                                 :api-key api-key
                                                                 :messages (:messages context)
                                                                 :tools (mapv u/->tool-fn (:tools context))}))
                         (catch Exception e
                           (t/log! :error e)))]

      (vthread-loop []
        (when-let [chunk (a/<!! stream-ch)]
          (let [d (delta chunk)]
            (if (= chunk :done)
              (a/>!! out-c (frame/llm-full-response-end true))
              (do
                (if-let [tool-call (first (:tool_calls d))]
                  (do
                    (t/log! ["Sending tool call" tool-call])
                    (a/>!! out-c (frame/llm-tool-call-chunk tool-call)))
                  (when-let [c (:content d)]
                    (a/>!! out-c (frame/llm-text-chunk c))))
                (recur)))))))))

(def openai-llm-process
  (flow/process
    (fn
      ([] {:ins {:in "Channel for incoming context aggregations"}
           :outs {:out "Channel where streaming responses will go"}
           :params (schema/->flow-describe-parameters OpenAILLMConfigSchema)
           :workload :io})
      ([params]
       (let [state (m/decode OpenAILLMConfigSchema params mt/default-value-transformer)
             llm-write (a/chan 100)
             llm-read (a/chan 1024)]
         (vthread-loop []
           (if-let [frame (a/<!! llm-write)]
             (do
               (t/log! :info ["AI REQUEST" (:frame/data frame)])
               (assert (or (frame/llm-context? frame)
                           (frame/control-interrupt-start? frame)) "Invalid frame sent to LLM. Only llm-context or interrupt-start")
               (flow-do-completion! state llm-read (:frame/data frame))
               (recur))
             (t/log! {:level :info :id :llm} "Closing llm loop")))

         {::flow/in-ports {:llm-read llm-read}
          ::flow/out-ports {:llm-write llm-write}}))

      ([{::flow/keys [in-ports out-ports]} transition]
       (when (= transition ::flow/stop)
         (doseq [port (concat (vals in-ports) (vals out-ports))]
           (a/close! port))))

      ([state in msg]
       (if (= in :llm-read)
         [state {:out [msg]}]
         (cond
           (frame/llm-context? msg)
           [state {:llm-write [msg]}]))))))
