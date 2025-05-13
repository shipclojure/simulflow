(ns simulflow.utils.openai
  "Common logic for openai format requests. Many LLM providers use openai format
  for their APIs. This NS keeps common logic for those providers."
  (:require
   [clojure.core.async :as a]
   [hato.client :as http]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [simulflow.utils.core :as u]
   [simulflow.utils.request :as request]))

(def response-chunk-delta
  "Retrieve the delta part of a streaming completion response"
  (comp :delta first :choices))

(defn handle-completion-request!
  "Handle completion requests for OpenAI LLM models"
  [in-c out-c]

  (vthread-loop []
    (when-let [chunk (a/<!! in-c)]
      (let [d (response-chunk-delta chunk)]
        (if (= chunk :done)
          (a/>!! out-c (frame/llm-full-response-end true))
          (do
            (if-let [tool-call (first (:tool_calls d))]
              (a/>!! out-c (frame/llm-tool-call-chunk tool-call))
              ;; normal text completion
              (when-let [c (:content d)]
                (a/>!! out-c (frame/llm-text-chunk c))))
            (recur)))))))

(def openai-completions-url "https://api.openai.com/v1/chat/completions")

(defn stream-chat-completion
  [{:keys [api-key messages tools model response-format completions-url]
    :or {model "gpt-4o-mini"
         completions-url openai-completions-url}}]
  (:body (request/sse-request {:request {:url completions-url
                                         :headers {"Authorization" (str "Bearer " api-key)
                                                   "Content-Type" "application/json"}

                                         :method :post
                                         :body (u/json-str (cond-> {:messages messages
                                                                    :stream true
                                                                    :response_format response-format
                                                                    :model model}
                                                             (pos? (count tools)) (assoc :tools tools)))}
                               :params {:stream/close? true}})))

(defn normal-chat-completion
  [{:keys [api-key messages tools model response-format stream completions-url]
    :or {model "gpt-4o-mini"
         completions-url openai-completions-url
         stream false}}]
  (http/request {:url completions-url
                 :headers {"Authorization" (str "Bearer " api-key)
                           "Content-Type" "application/json"}

                 :throw-on-error? false
                 :method :post
                 :body (u/json-str (cond-> {:messages messages
                                            :stream stream
                                            :response_format response-format
                                            :model model}
                                     (pos? (count tools)) (assoc :tools tools)))}))
