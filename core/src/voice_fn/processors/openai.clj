(ns voice-fn.processors.openai
  (:require
   [clojure.core.async :as a]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.schema :as schema]
   [wkok.openai-clojure.api :as api]))

(def token-content (comp :content :delta first :choices))

(defn user-last-message?
  [context]
  (#{:user "user"} (-> context last :role)))

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
           '[malli.error :as me]
           '[voice-fn.secrets :refer [secret]])
  ;; Valid config
  (m/validate OpenAILLMConfigSchema
              {:llm/model :gpt-4o-mini
               :openai/api-key "sk-12312312312312312312312312312312312312312313..."})
  ;; => true

  ;; Invalid model
  (-> OpenAILLMConfigSchema
      (m/explain {:llm/model "invalid-model"
                  :openai/api-key "sk-..."})
      me/humanize)

  (api/create-chat-completion {:model "gpt-4o-mini"
                               :stream true
                               :messages [{:role "system", :content "Ești un agent vocal care funcționează prin telefon. Răspunde doar în limba română și fii succint. Inputul pe care îl primești vine dintr-un sistem de speech to text (transcription) care nu este intotdeauna eficient și poate trimite text neclar. Cere clarificări când nu ești sigur pe ce a spus omul."}
                                          {:role "user", :content "Salut care mă aud"}]}
                              {:api-key (secret [:openai :new-api-sk])
                               :version :http-2
                               :as :stream})

  ,)

(defmethod pipeline/processor-schema :llm/openai
  [_]
  OpenAILLMConfigSchema)

(defmethod pipeline/accepted-frames :llm/openai
  [_]
  #{:frame.context/messages})

(defmethod pipeline/process-frame :llm/openai
  [_type pipeline processor frame]

  (let [{:llm/keys [model] :openai/keys [api-key]} (:processor/config processor)]
    ;; Start request only when the last message in the context is by the user
    (when (and (frame/context-messages? frame)
               (user-last-message? (:frame/data frame)))
      (pipeline/send-frame! pipeline (frame/llm-full-response-start true))
      (let [out (api/create-chat-completion {:model model
                                             :messages (:frame/data frame)
                                             :stream true}
                                            {:api-key api-key
                                             :version :http-2
                                             :as :stream})]
        (a/go-loop []
          (when-let [chunk (a/<! out)]
            (if (= chunk :done)
              (pipeline/send-frame! pipeline (frame/llm-full-response-end true))
              (do
                (pipeline/send-frame! pipeline (frame/llm-text-chunk (token-content chunk)))
                (recur)))))))))
