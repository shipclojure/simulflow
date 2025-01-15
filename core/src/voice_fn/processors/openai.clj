(ns voice-fn.processors.openai
  (:require
   [clojure.core.async :as a]
   [malli.core :as m]
   [malli.transform :as mt]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.protocol :as p]
   [voice-fn.schema :as schema]
   [voice-fn.utils.core :as u]
   [voice-fn.utils.request :as request]))

(def openai-completions-url "https://api.openai.com/v1/chat/completions")

(defn stream-openai-chat-completion
  [{:keys [api-key messages model]}]
  (:body (request/sse-request {:request {:url openai-completions-url
                                         :headers {"Authorization" (str "Bearer " api-key)
                                                   "Content-Type" "application/json"}

                                         :method :post
                                         :body (u/json-str {:messages messages
                                                            :stream true
                                                            :model model})}
                               :params {:stream/close? true}})))

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

  ,)

(defmethod pipeline/create-processor :processor.llm/openai
  [id]
  (reify p/Processor
    (processor-id [_] id)

    (processor-schema [_] OpenAILLMConfigSchema)

    (accepted-frames [_] #{:frame.context/messages :frame.control/interrupt-start})

    (make-processor-config [_ _ processor-config]
      (m/decode OpenAILLMConfigSchema processor-config mt/default-value-transformer))

    (process-frame [_ pipeline processor-config frame]
      (let [{:llm/keys [model] :openai/keys [api-key]} processor-config]
        ;; Start request only when the last message in the context is by the user
        (cond
          (and (frame/context-messages? frame)
               (u/user-last-message? (:frame/data frame))
               (not (pipeline/interrupted? @pipeline)))
          (do
            (pipeline/send-frame! pipeline (frame/llm-full-response-start true))
            (let [stream-ch (stream-openai-chat-completion {:model model
                                                            :api-key api-key
                                                            :messages (:frame/data frame)})]
              (swap! pipeline assoc-in [id :stream-ch] stream-ch)
              (a/go-loop []
                (when-let [chunk (a/<! stream-ch)]
                  (if (= chunk :done)
                    (do
                      (pipeline/send-frame! pipeline (frame/llm-full-response-end true))
                      (swap! pipeline update-in [id] dissoc :stream-ch))
                    (do
                      (pipeline/send-frame! pipeline (frame/llm-text-chunk (u/token-content chunk)))
                      (recur)))))))

          ;; If interrupt-start frame is sent, we cancel the current token
          ;; generation if one is in progress
          (frame/control-interrupt-start? frame)
          (when-let [stream-ch (get-in @pipeline [id :stream-ch])]
            (a/close! stream-ch)
            (swap! pipeline update-in [id] dissoc :stream-ch)))))))
