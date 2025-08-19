(ns simulflow.processors.google
  (:require
   [clojure.core.async.flow :as flow]
   [clojure.string :as str]
   [hato.client :as http]
   [simulflow.schema :as schema]
   [simulflow.secrets :refer [secret]]
   [simulflow.utils.core :as u]
   [simulflow.utils.openai :as uai]))

(def google-generative-api-url "https://generativelanguage.googleapis.com/v1beta/openai")
(def google-completions-url (str google-generative-api-url "/chat/completions"))

(comment
  ;; Get list of valid models
  (->> (http/request {:url (str google-generative-api-url "/models")
                      :method :get
                      :headers {"Authorization" (str "Bearer " (secret [:google :api-key]))}})
       :body
       u/parse-if-json
       :data
       (map :id)
       (map #(last (str/split % #"/")))
       set))

(def model-schema
  (schema/flex-enum
    {:description "Google llm model identifier"
     :error/message "Must be a valid Google LLM model. Try gemini-2.0-flash"}
    [:gemini-embedding-exp :gemini-2.0-flash-thinking-exp :learnlm-1.5-pro-experimental :gemini-1.5-pro :gemini-2.0-flash-lite-001 :gemini-1.5-flash
     :gemini-2.0-flash-lite-preview :chat-bison-001 :gemini-exp-1206 :gemini-1.5-pro-002 :text-bison-001 :gemini-2.0-flash-lite-preview-02-05
     :gemini-1.5-flash-8b-latest :embedding-001 :gemini-2.0-pro-exp :gemini-1.5-flash-8b-001 :gemini-1.0-pro-vision-latest :gemini-2.5-pro-preview-03-25
     :learnlm-2.0-flash-experimental :gemini-1.5-flash-002 :gemma-3-27b-it :text-embedding-004 :gemini-1.5-flash-001-tuning :gemini-2.0-flash-lite
     :gemini-2.5-flash-preview-04-17-thinking :gemini-1.5-pro-001 :gemini-pro-vision :gemini-1.5-flash-8b-exp-0924 :gemini-2.0-flash-live-001
     :gemini-1.5-flash-8b :gemini-2.0-flash-thinking-exp-1219 :gemini-1.5-flash-latest :gemini-1.5-pro-latest :gemma-3-4b-it :embedding-gecko-001
     :gemini-2.0-flash-thinking-exp-01-21 :gemini-2.0-pro-exp-02-05 :veo-2.0-generate-001 :gemini-embedding-exp-03-07 :gemini-2.5-pro-exp-03-25 :gemini-1.5-flash-8b-exp-0827
     :gemma-3-12b-it :gemini-2.0-flash :gemini-2.5-flash-preview-04-17 :gemini-2.0-flash-001 :gemini-1.5-flash-001 :gemma-3-1b-it :gemini-2.5-pro-preview-05-06
     :imagen-3.0-generate-002 :gemini-2.0-flash-exp]))

(def GoogleLLMConfigSchema
  [:map
   {:description "Google LLM configuration"}
   [:llm/model {:default :gemini-2.0-flash
                :description "Google model used for llm inference"} model-schema]
   [:google/api-key {:description "Google API key"
                     :error/message "Invalid Google Api Key provided"} :string]
   [:api/completions-url {:default google-completions-url
                          :optional true
                          :description "Different completions url for gemini api"} :string]])

(comment

  (:body (uai/normal-chat-completion {:api-key (secret [:google :api-key])
                                      :model :gemini-2.0-flash
                                      :messages [{:role "system"
                                                  :content "You are a voice agent operating via phone. Be
                       concise in your answers. The input you receive comes from a
                       speech-to-text (transcription) system that isn't always
                       efficient and may send unclear text. Ask for
                       clarification when you're unsure what the person said."}
                                                 {:role "user" :content "Do you hear me?"}]
                                      :completions-url google-completions-url})))

(def describe
  {:ins {:in "Channel for incoming context aggregations"}
   :outs {:out "Channel where streaming responses will go"}
   :params (schema/->describe-parameters GoogleLLMConfigSchema)
   :workload :io})

(defn init!
  [params]
  (uai/init-llm-processor! GoogleLLMConfigSchema params :gemini))

(defn transition
  [state transition]
  (uai/transition-llm-processor state transition))

(defn transform
  [state in msg]
  (uai/transform-llm-processor state in msg :google/api-key :api/completions-url))

(defn google-llm-process-fn
  ([] describe)
  ([params] (init! params))
  ([state trs]
   (transition state trs))
  ([state in msg] (transform state in msg)))

(def google-llm-process (flow/process google-llm-process-fn))
