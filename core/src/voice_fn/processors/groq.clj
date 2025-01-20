(ns voice-fn.processors.groq
  (:require
   [clojure.core.async :as a]
   [hato.client :as http]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.protocol :as p]
   [voice-fn.schema :as schema]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.utils.core :as u]
   [voice-fn.utils.request :as request]))

(def groq-api-url "https://api.groq.com/openai/v1")
(def groq-completions-url (str groq-api-url "/chat/completions"))

(defn stream-groq-chat-completion
  [{:keys [api-key messages model]}]
  (:body (request/sse-request {:request {:url groq-completions-url
                                         :headers {"Authorization" (str "Bearer " api-key)
                                                   "Content-Type" "application/json"}

                                         :method :post
                                         :body (u/json-str {:messages messages
                                                            :stream true
                                                            :model model})}
                               :params {:stream/close? true}})))

(comment

  (map u/token-content (a/<!! (a/into [] (stream-groq-chat-completion
                                           {:model "llama-3.3-70b-versatile"
                                            :api-key (secret [:groq :api-key])
                                            :messages [{:role "system" :content  "Ești un agent vocal care funcționează prin telefon. Răspunde doar în limba română și fii succint. Inputul pe care îl primești vine dintr-un sistem de speech to text (transcription) care nu este intotdeauna eficient și poate trimite text neclar. Cere clarificări când nu ești sigur pe ce a spus omul."}
                                                       {:role "user" :content "Salutare ma auzi?"}]}))))

  ,)

(comment

  (->> (http/get (str groq-api-url "/models")
                 {:headers {"Authorization" (str "Bearer " (secret [:groq :api-key]))
                            "Content-Type" "application/json"}})
       :body
       u/parse-if-json
       :data
       (map :id))
  ,)

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
      :min 40      ;; OpenAI API keys are typically longer
      :error/message "Invalid Groq API key format"}]]])

(defmethod pipeline/create-processor :processor.llm/groq
  [id]
  (reify p/Processor
    (processor-id [_] id)

    (processor-schema [_] GroqLLMConfigSchema)

    (accepted-frames [_] #{:frame.context/messages :frame.control/interrupt-start})

    (make-processor-config [_ _ processor-config]
      processor-config)

    (process-frame [_ pipeline processor-config frame]
      (let [{:llm/keys [model] :groq/keys [api-key]} processor-config]
        ;; Start request only when the last message in the context is by the user
        (cond
          (and (frame/llm-context? frame)
               (u/user-last-message? (:frame/data frame))
               (not (pipeline/interrupted? @pipeline)))
          (do
            (pipeline/send-frame! pipeline (frame/llm-full-response-start true))
            (let [stream-ch (stream-groq-chat-completion {:model model
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
