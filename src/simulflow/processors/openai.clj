(ns simulflow.processors.openai
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [malli.core :as m]
            [simulflow.async :refer [vthread-loop]]
            [simulflow.frame :as frame]
            [simulflow.schema :as schema]
            [simulflow.utils.core :as u]
            [simulflow.utils.openai :as uai]
            [taoensso.telemere :as t]))

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
      me/humanize))

(defn describe
  []
  {:ins {:in "Channel for incoming context aggregations"}
   :outs {:out "Channel where streaming responses will go"}
   :params (schema/->flow-describe-parameters OpenAILLMConfigSchema)
   :workload :io})

(defn init!
  [params]
  (let [parsed-config (schema/parse-with-defaults OpenAILLMConfigSchema params)
        {:llm/keys [model] :openai/keys [api-key] :as parsed-config} parsed-config
        llm-write (a/chan 100)
        llm-read (a/chan 1024)]

    (vthread-loop []
      (when-let [frame (a/<!! llm-write)]
        (t/log! :info ["AI REQUEST" (:frame/data frame)])
        (assert (or (frame/llm-context? frame)
                    (frame/control-interrupt-start? frame))
                "Invalid frame sent to LLM. Only llm-context or interrupt-start")
        (let [context (:frame/data frame)
              stream-ch (uai/stream-chat-completion {:model model
                                                     :api-key api-key
                                                     :messages (:messages context)
                                                     :tools (mapv u/->tool-fn (:tools context))})]
          (uai/handle-completion-request! stream-ch llm-read))
        (recur)))

    (merge parsed-config
           {::flow/in-ports {:llm-read llm-read}
            ::flow/out-ports {:llm-write llm-write}})))

(defn transition
  [{::flow/keys [in-ports out-ports]} transition]
  (when (= transition ::flow/stop)
    (doseq [port (concat (vals in-ports) (vals out-ports))]
      (a/close! port))))

(defn transform
  [state in msg]
  (if (= in :llm-read)
    [state {:out [msg]}]
    (cond
      (frame/llm-context? msg)
      [state {:llm-write [msg]
              :out [(frame/llm-full-response-start true)]}]

      :else
      [state {}])))

(defn openai-llm-fn
  "Multi-arity processor function for OpenAI LLM"
  ([] (describe))
  ([params] (init! params))
  ([state trs] (transition state trs))
  ([state input-port frame] (transform state input-port frame)))

(def openai-llm-process
  "OpenAI LLM processor using separate function approach"
  (flow/process openai-llm-fn))
