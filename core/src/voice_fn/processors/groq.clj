(ns voice-fn.processors.groq
  (:require
   [voice-fn.secrets :refer [secret]]
   [wkok.openai-clojure.api :as api]))

(def groq-api-url "https://api.groq.com/openai/v1")

(comment
  (api/create-completion
    {:model "llama3-8b-8192"
     :messages [{:role "system" :content "You are a helpful assistant."}
                {:role "user" :content "Who won the world series in 2020?"}
                {:role "assistant" :content "The Los Angeles Dodgers won the World Series in 2020."}
                {:role "user" :content "Where was it played?"}]}

    {:api-key (secret [:groq :api-key])

     :api-endpoint groq-api-url}))
