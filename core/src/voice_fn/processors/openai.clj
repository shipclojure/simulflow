(ns voice-fn.processors.openai
  (:require
   [clojure.core.async :as a]
   [voice-fn.frames :as frames]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.schema :as schema]
   [wkok.openai-clojure.api :as api]))

(def token-content (comp :content :delta first :choices))

(def OpenAILLMConfigSchema
  [:map
   {:closed true
    :description "OpenAI LLM configuration"}

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

   [:openai/api-key
    [:string
     {:description "OpenAI API key"
      :secret true ;; Marks this as sensitive data
      :min 40      ;; OpenAI API keys are typically longer
      :error/message "Invalid OpenAI API key format"}]]])

;; Example validation:
(comment
  (require '[malli.core :as m]
           '[malli.error :as me])
  ;; Valid config
  (m/validate OpenAILLMConfigSchema
              {:llm/model :gpt-4o-mini
               :openai/api-key "sk-12312312312312312312312312312312312312312313..."})
  ;; => true

  ;; Invalid model
  (-> OpenAILLMConfigSchema
      (m/explain {:llm/model "invalid-model"
                  :openai/api-key "sk-..."})
      me/humanize))

(defmethod pipeline/processor-schema :llm/openai
  [_]
  OpenAILLMConfigSchema)

(defmethod pipeline/process-frame :llm/openai
  [_type pipeline processor frame]
  (let [{:llm/keys [model] :openai/keys [api-key]} (:processor/config processor)
        {:llm/keys [context]} (:pipeline/config @pipeline)]
    (case (:frame/type frame)
      :llm/user-context-added (a/pipeline
                                1
                                (:pipeline/main-ch @pipeline)
                                (comp (map token-content) (filter some?) (map frames/llm-output-text-chunk-frame))
                                (api/create-chat-completion {:model model
                                                             :messages context
                                                             :stream true}
                                                            {:api-key api-key
                                                             :version :http-2 :as :stream})))))
