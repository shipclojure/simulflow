(ns simulflow.processors.openai
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [malli.core :as m]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.command :as command]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]
   [simulflow.utils.core :as u]
   [simulflow.utils.openai :as uai]
   [taoensso.telemere :as t]))

(def OpenAILLMConfigSchema
  [:map
   {:description "OpenAI LLM configuration"}

   [:llm/model {:default :gpt-4o-mini}
    (schema/flex-enum
      {:description "OpenAI model identifier"
       :error/message "Must be a valid OpenAI model"
       :default "gpt-4o-mini"}
      [;; GPT-4 Models
       "gpt-4"
       "gpt-4-32k"
       "gpt-4-1106-preview" ;; GPT-4 Turbo
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

(defn handle-response
  [in-ch out-ch]
  (vthread-loop []
                (when-let [chunk (a/<!! in-ch)]
                  (a/>!! out-ch chunk)
                  (recur))))

(defn init!
  [params]
  (let [parsed-config (schema/parse-with-defaults OpenAILLMConfigSchema params)
        llm-write (a/chan 100)
        llm-read (a/chan 1024)]
    (vthread-loop []
                  (when-let [command (a/<!! llm-write)]
                    (try
                      (t/log! {:level :info :id :openai} ["Processing request command" command])
                      (assert (= (:command/kind command) :command/sse-request)
                              "OpenAI processor only supports SSE request commands")
                      ;; Execute the command and handle the streaming response
                      (handle-response (command/handle-command command) llm-read)
                      (catch Exception e
                        (t/log! {:level :error :id :openai :error e} "Error processing command")))
                    (recur)))

    (merge parsed-config
           {::flow/in-ports {::llm-read llm-read}
            ::flow/out-ports {::llm-write llm-write}})))

(defn transition
  [{::flow/keys [in-ports out-ports] :as state} transition]
  (when (= transition ::flow/stop)
    (doseq [port (concat (vals in-ports) (vals out-ports))]
      (a/close! port)))
  state)

(defn transform-handle-llm-response
  "Handle the streaming response from the LLM. Return appropriate frames based on
  the stream. "
  [state msg]
  (let [d (uai/response-chunk-delta msg)
        tool-call (first (:tool_calls d))
        c (:content d)]
    (cond
      (= msg :done)
      [state {:out [(frame/llm-full-response-end true)]}]

      tool-call
      [state {:out [(frame/llm-tool-call-chunk tool-call)]}]

      c
      [state {:out [(frame/llm-text-chunk c)]}]

      :else [state])))

(defn transform
  [state in msg]
  (cond
    (= in ::llm-read)
    (transform-handle-llm-response state msg)

    (frame/llm-context? msg)
    (let [context (:frame/data msg)
          {:llm/keys [model] :openai/keys [api-key] :api/keys [completions-url]} state
          tools (mapv u/->tool-fn (:tools context))
          request-body (u/json-str (cond-> {:messages (:messages context)
                                            :stream true
                                            :model model}
                                     (pos? (count tools)) (assoc :tools tools)))]
      [state {::llm-write [(command/sse-request-command {:url completions-url
                                                         :method :post
                                                         :body request-body
                                                         :headers {"Authorization" (str "Bearer " api-key)
                                                                   "Content-Type" "application/json"}})]
              :out [(frame/llm-full-response-start true)]}])

    :else
    [state {}]))

(defn openai-llm-fn
  "Multi-arity processor function for OpenAI LLM"
  ([] describe)
  ([params] (init! params))
  ([state trs] (transition state trs))
  ([state input-port frame] (transform state input-port frame)))

(def openai-llm-process
  "OpenAI LLM processor using separate function approach"
  (flow/process openai-llm-fn))
