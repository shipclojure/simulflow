(ns voice-fn.processors.openai
  (:require
   [clojure.core.async :as a]
   [hato.client :as http]
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

(def argument-chunk (comp :arguments :function first :tool_calls :delta first :choices))

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
       "gpt-4-1106-preview"    ;; GPT-4 Turbo
       "gpt-4-vision-preview"  ;; GPT-4 Vision
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

(def delta (comp  :delta first :choices))

(defn process-context-frame
  "Handle completion requests for OpenAI LLM models"
  [id pipeline processor-config frame]
  (let [{:llm/keys [model] :openai/keys [api-key]} processor-config]
    ;; Start request only when the last message in the context is by the user

    (pipeline/send-frame! pipeline (frame/llm-full-response-start true))
    (let [stream-ch (stream-openai-chat-completion {:model model
                                                    :api-key api-key
                                                    :messages (:frame/data frame)})]
      (swap! pipeline assoc-in [id :stream-ch] stream-ch)
      (a/go-loop [function-name nil
                  function-arguments nil
                  tool-call-id nil]
        (when-let [chunk (a/<! stream-ch)]
          (let [d (delta chunk)
                tool-call (first (:tool_calls d))]
            (cond
              (= chunk :done)
              (do
                (swap! pipeline update-in [id] dissoc :stream-ch)
                ;; When this is a tool call completion and we are done
                ;; parsing tool call completion, send a tool-call
                ;; request frame to the aggregators so we get the result in the context

                (let [parsed-args (u/parse-if-json function-arguments)]
                  (when
                    (and function-name
                         (map? parsed-args))
                    (pipeline/send-frame!
                      pipeline
                      (frame/llm-tools-call-request
                        {:function-name function-name
                         :arguments parsed-args
                         :tool-call-id tool-call-id}))))
                (pipeline/send-frame! pipeline (frame/llm-full-response-end true))
                nil)                    ; explicit nil return when done

              ;; text completion chunk
              (:content d)
              (do
                (pipeline/send-frame! pipeline (frame/llm-text-chunk (:content d)))
                (recur function-name function-arguments tool-call-id))

              ;;  We're streaming the LLM response to enable the fastest response times.
              ;;  For text, we just send each chunk as we receive it and count on consumers
              ;;  to do whatever coalescing they need (eg. to pass full sentences to TTS)
              ;;
              ;;  If the LLM response is a function call, we'll do some coalescing here.
              ;;  We accumulate all the arguments for the rest of the streamed response, then when
              ;;  the response is done, we package up all the arguments and the function name and
              ;;  send a frame containing the function name and the arguments.
              tool-call
              (let [{:keys [arguments name]} (:function tool-call)
                    tci (:id tool-call)]
                (recur (or function-name name)
                       (str function-arguments arguments)
                       (or tool-call-id tci)))

              ;; Should never get to this point
              :else
              (recur function-name function-arguments tool-call-id))))))))

(defmethod pipeline/create-processor :processor.llm/openai
  [id]
  (reify p/Processor
    (processor-id [_] id)

    (processor-schema [_] OpenAILLMConfigSchema)

    (accepted-frames [_] #{:frame.context/messages :frame.control/interrupt-start})

    (make-processor-config [_ _ processor-config]
      (m/decode OpenAILLMConfigSchema processor-config mt/default-value-transformer))

    (process-frame [_ pipeline processor-config frame]
      (cond
        (and (frame/context-messages? frame)
             ;; LLM shouldn't respond to itself
             (not (u/assistant-last-message? (:frame/data frame)))
             (not (pipeline/interrupted? @pipeline)))
        (process-context-frame id pipeline processor-config frame)

        ;; If interrupt-start frame is sent, we cancel the current token
        ;; generation if one is in progress
        (frame/control-interrupt-start? frame)
        (when-let [stream-ch (get-in @pipeline [id :stream-ch])]
          ;; Closing the stream-ch, prevents new events to be put on it
          (a/close! stream-ch)
          (swap! pipeline update-in [id] dissoc :stream-ch))))))
