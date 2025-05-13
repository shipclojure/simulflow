(ns simulflow.processors.google
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [clojure.string :as str]
   [hato.client :as http]
   [malli.core :as m]
   [malli.error :as me]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]
   [simulflow.secrets :refer [secret]]
   [simulflow.utils.core :as u]
   [simulflow.utils.openai :as uai]
   [simulflow.utils.request :as request]
   [taoensso.telemere :as t]))

(def google-generative-api-url "https://generativelanguage.googleapis.com/v1beta/openai")

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
    ["gemini-embedding-exp" "gemini-2.0-flash-thinking-exp" "learnlm-1.5-pro-experimental" "gemini-1.5-pro" "gemini-2.0-flash-lite-001" "gemini-1.5-flash"
     "gemini-2.0-flash-lite-preview" "chat-bison-001" "gemini-exp-1206" "gemini-1.5-pro-002" "text-bison-001" "gemini-2.0-flash-lite-preview-02-05"
     "gemini-1.5-flash-8b-latest" "embedding-001" "gemini-2.0-pro-exp" "gemini-1.5-flash-8b-001" "gemini-1.0-pro-vision-latest" "gemini-2.5-pro-preview-03-25"
     "learnlm-2.0-flash-experimental" "gemini-1.5-flash-002" "gemma-3-27b-it" "text-embedding-004" "gemini-1.5-flash-001-tuning" "gemini-2.0-flash-lite"
     "gemini-2.5-flash-preview-04-17-thinking" "gemini-1.5-pro-001" "gemini-pro-vision" "gemini-1.5-flash-8b-exp-0924" "gemini-2.0-flash-live-001" "aqa"
     "gemini-1.5-flash-8b" "gemini-2.0-flash-thinking-exp-1219" "gemini-1.5-flash-latest" "gemini-1.5-pro-latest" "gemma-3-4b-it" "embedding-gecko-001"
     "gemini-2.0-flash-thinking-exp-01-21" "gemini-2.0-pro-exp-02-05" "veo-2.0-generate-001" "gemini-embedding-exp-03-07" "gemini-2.5-pro-exp-03-25" "gemini-1.5-flash-8b-exp-0827"
     "gemma-3-12b-it" "gemini-2.0-flash" "gemini-2.5-flash-preview-04-17" "gemini-2.0-flash-001" "gemini-1.5-flash-001" "gemma-3-1b-it" "gemini-2.5-pro-preview-05-06"
     "imagen-3.0-generate-002" "gemini-2.0-flash-exp"]))

(def GoogleLLMConfigSchema
  [:map
   {:description "Google LLM configuration"}
   [:llm/model model-schema]
   [:google/api-key {:optional true} [:string
                                      {:description "Google API key"
                                       :secret true
                                       :error/message "Invalid Google"}]]])

(defn google-llm-process
  ([]
   {:ins {:in "Channel for incoming context aggregations"}
    :outs {:out "Channel where streaming responses will go"}
    :params (schema/->flow-describe-parameters GoogleLLMConfigSchema)
    :workload :io})
  ([params]
   (when-let [error (m/explain GoogleLLMConfigSchema params)]
     (throw (ex-info "google-llm-processor: Invalid configuration"
                     {:humanized (me/humanize error)
                      :error error
                      :type ::invalid-configuration})))
   (let [llm-write (a/chan 1024)
         llm-read (a/chan 1024)
         {:llm/keys [model] :google/keys [api-key]} params]
     (vthread-loop []
       (when-let [frame (a/<!! llm-write)]
         (t/log! :info ["AI REQUEST" (:frame/data frame)])
         (assert (or (frame/llm-context? frame)
                     (frame/control-interrupt-start? frame)) "Invalid frame sent to LLM. Only llm-context or interrupt-start")
         (let [context (:frame/data frame)
               stream-ch (request/stream-chat-completion {:model model
                                                          :api-key api-key
                                                          :messages (:messages context)
                                                          :tools (mapv u/->tool-fn (:tools context))})]
           (uai/handle-completion-request! stream-ch llm-read))

         (recur)))
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
       [state {:llm-write [msg]
               :out [(frame/llm-full-response-start true)]}]))))
