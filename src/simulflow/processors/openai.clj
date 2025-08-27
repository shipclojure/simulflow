(ns simulflow.processors.openai
  (:require
   [clojure.core.async.flow :as flow]
   [malli.core :as m]
   [simulflow.schema :as schema]
   [simulflow.utils.openai :as uai]))

(def OpenAILLMConfigSchema
  [:map
   {:description "OpenAI LLM configuration"}

   [:llm/model {:default :gpt-4o-mini}
    (schema/flex-enum
      {:description "OpenAI model identifier"
       :error/message "Must be a valid OpenAI model"
       :default "gpt-4o-mini"}
      [;; GPT-4o Models (2024-2025)
       :gpt-4o
       :chatgpt-4o-latest
       :gpt-4o-mini
       :gpt-4o-audio-preview
       :gpt-4o-audio-preview-2024-12-17
       :gpt-4o-audio-preview-2024-10-01
       :gpt-4o-mini-audio-preview
       :gpt-4o-mini-audio-preview-2024-12-17

       ;; GPT-4.1 Models (2025)
       :gpt-4.1
       :gpt-4.1-mini
       :gpt-4.1-nano
       :gpt-4.1-2025-04-14
       :gpt-4.1-mini-2025-04-14
       :gpt-4.1-nano-2025-04-14

       ;; GPT-5 Models (2025)
       :gpt-5
       :gpt-5-mini
       :gpt-5-nano
       :gpt-5-chat-latest
       :gpt-5-2025-08-07
       :gpt-5-mini-2025-08-07
       :gpt-5-nano-2025-08-07

       ;; GPT-4 Turbo Models
       :gpt-4-turbo
       :gpt-4-turbo-2024-04-09
       :gpt-4-1106-preview
       :gpt-4-0125-preview
       :gpt-4-turbo-preview

       ;; GPT-4 Models
       :gpt-4
       :gpt-4-32k
       :gpt-4-vision-preview

       ;; GPT-3.5 Models
       :gpt-3.5-turbo
       :gpt-3.5-turbo-16k
       :gpt-3.5-turbo-1106
       :gpt-3.5-turbo-instruct

       ;; O-series Models (2025)
       :o3-2025-04-16
       :o3-pro
       :o4-mini-2025-04-16
       ;; Base Models
       :babbage-002
       :davinci-002])]
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
      :min 40 ;; OpenAI API keys are typically longer
      :error/message "Invalid OpenAI API key format"}]]

   [:api/completions-url {:optional true
                          :default uai/openai-completions-url} :string]])

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
      me/humanize))

(def describe
  {:ins {:in "Channel for incoming context aggregations"
         :sys-in "Channel for incoming system messages"}
   :outs {:out "Channel where streaming responses will go"}
   :params (schema/->describe-parameters OpenAILLMConfigSchema)
   :workload :io})

(def init! (partial uai/init-llm-processor! OpenAILLMConfigSchema :openai))

(defn transition
  [state transition]
  (uai/transition-llm-processor state transition))

(defn transform
  [state in msg]
  (uai/transform-llm-processor state in msg :openai/api-key :api/completions-url))

(defn openai-llm-fn
  "Multi-arity processor function for OpenAI LLM"
  ([] describe)
  ([params] (init! params))
  ([state trs] (transition state trs))
  ([state input-port frame] (transform state input-port frame)))

(def openai-llm-process
  "OpenAI LLM processor using separate function approach"
  (flow/process openai-llm-fn))
