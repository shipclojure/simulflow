(ns voice-fn.processors.openai
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [hato.client :as http]
   [malli.core :as m]
   [malli.transform :as mt]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.schema :as schema]
   [voice-fn.utils.core :as u]
   [voice-fn.utils.request :as request]))

(def openai-completions-url "https://api.openai.com/v1/chat/completions")

(defn stream-openai-chat-completion
  [{:keys [api-key messages tools model]}]
  (:body (request/sse-request {:request {:url openai-completions-url
                                         :headers {"Authorization" (str "Bearer " api-key)
                                                   "Content-Type" "application/json"}

                                         :method :post
                                         :body (u/json-str (cond-> {:messages messages
                                                                    :stream true
                                                                    :model model}
                                                             tools (assoc :tools tools)))}
                               :params {:stream/close? true}})))
(defn normal-chat-completion
  [{:keys [api-key messages tools model]}]
  (http/request {:url openai-completions-url
                 :headers {"Authorization" (str "Bearer " api-key)
                           "Content-Type" "application/json"}

                 :throw-on-error? false
                 :method :post
                 :body (u/json-str (cond-> {:messages messages
                                            :stream true
                                            :model model}
                                     tools (assoc :tools tools)))}))

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
  [state out-c frame]
  (let [{:llm/keys [model] :openai/keys [api-key]} state]
    ;; Start request only when the last message in the context is by the user

    (a/>!! out-c (frame/llm-full-response-start true))
    (let [stream-ch (stream-openai-chat-completion (merge {:model model
                                                           :api-key api-key}
                                                          (:frame/data frame)))]

      (a/go-loop []
        (when-let [chunk (a/<! stream-ch)]
          (let [d (delta chunk)]
            (if (= chunk :done)
              (a/>! out-c (frame/llm-full-response-end true))
              (do
                (if-let [tool-call (first (:tool_calls d))]
                  (do
                    (t/log! ["SENDING TOOL CALL" tool-call])
                    (a/>! out-c (frame/llm-tool-call-chunk tool-call)))
                  (when-let [c (:content d)]
                    (a/>! out-c (frame/llm-text-chunk c))))
                (recur)))))))))

(def openai-llm-process
  (flow/process
    {:describe (fn [] {:ins {:in "Channel for incoming context aggregations"}
                       :outs {:out "Channel where streaming responses will go"}})
     :params {:llm/model "Openai model used"
              :openai/api-key "OpenAI Api key"
              :llm/temperature "Optional temperature parameter for the llm inference"
              :llm/max-tokens "Optional max tokens to generate"
              :llm/presence-penalty "Optional (-2.0 to 2.0)"
              :llm/top-p "Optional nucleus sampling threshold"
              :llm/seed "Optional seed used for deterministic sampling"
              :llm/max-completion-tokens "Optional Max tokens in completion"
              :llm/extra "Optional extra model parameters"}
     :workload :io
     :transition (fn [{::flow/keys [in-ports out-ports]} transition]
                   (when (= transition ::flow/stop)
                     (doseq [port (concat (vals in-ports) (vals out-ports))]
                       (a/close! port))))
     :init (fn [params]
             (let [state (m/decode OpenAILLMConfigSchema params mt/default-value-transformer)
                   llm-write (a/chan 100)
                   llm-read (a/chan 1024)
                   write-to-llm #(loop []
                                   (if-let [msg (a/<!! llm-write)]
                                     (do
                                       (assert (or (frame/llm-context? msg)
                                                   (frame/control-interrupt-start? msg)) "Invalid frame sent to LLM. Only llm-context or interrupt-start")
                                       (flow-do-completion! state llm-read msg)
                                       (recur))
                                     (t/log! {:level :info :id :llm} "Closing llm loop")))]
               ((flow/futurize write-to-llm :exec :io))
               {::flow/in-ports {:llm-read llm-read}
                ::flow/out-ports {:llm-write llm-write}}))

     :transform (fn [state in msg]
                  (if (= in :llm-read)
                    [state {:out [msg]}]
                    (cond
                      (frame/llm-context? msg)
                      [state {:llm-write [msg]}])))}))
