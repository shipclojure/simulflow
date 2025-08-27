(ns simulflow.utils.openai
  "Common logic for openai format requests. Many LLM providers use openai format
  for their APIs. This NS keeps common logic for those providers."
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [hato.client :as http]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.command :as command]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]
   [simulflow.utils.core :as u]
   [simulflow.utils.request :as request]
   [taoensso.telemere :as t]))

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

;; Common processor functions
(defn vthread-pipe-response-with-interrupt
  "Common function to handle streaming response from LLM"
  [{:keys [in-ch out-ch interrupt-chan on-end]}]
  (vthread-loop []
    (if-let [[chunk c] (a/alts!! [in-ch interrupt-chan])]
      (when (= c in-ch)
        (a/>!! out-ch chunk)
        (recur))
      ;; No more data or interruption, call on-end and exit
      (when (fn? on-end)
        (on-end)))))

(defn init-llm-processor!
  "Common initialization function for OpenAI-compatible LLM processors"
  [schema params log-id]
  (let [parsed-config (schema/parse-with-defaults schema params)
        llm-write (a/chan 100)
        llm-read (a/chan 1024)
        interrupt-ch (a/chan 10)
        request-in-progress? (atom false)]
    (vthread-loop []
      (when-let [command (a/<!! llm-write)]
        (try
          (t/log! {:level :debug :id log-id :data command} "Processing request command")
          (assert (#{:command/sse-request :command/interrupt-request} command)
                  "LLM processor only supports SSE request or interrupt request commands")
          ;; Execute the command and handle the streaming response
          (cond
            (= command :command/sse-request)
            (do
              (reset! request-in-progress? true)
              (vthread-pipe-response-with-interrupt {:in-ch (command/handle-command command)
                                                     :out-ch llm-read
                                                     :interrupt-ch interrupt-ch
                                                     :on-end #(reset! request-in-progress? false)}))

            (= command :command/interrupt-request)
            (when @request-in-progress?
              (a/>!! interrupt-ch command)))
          (catch Exception e
            (t/log! {:level :error :id log-id :error e} "Error processing command")))
        (recur)))

    (merge parsed-config
           {::flow/in-ports {::llm-read llm-read}
            ::flow/out-ports {::llm-write llm-write}
            ::interrupt-ch interrupt-ch})))

(defn transition-llm-processor
  "Common transition function for LLM processors"
  [{::flow/keys [in-ports out-ports] :as state} transition]
  (when (= transition ::flow/stop)
    (doseq [port (concat (vals in-ports) (vals out-ports))]
      (a/close! port))
    (when-let [c (::interrupt-ch state)]
      (a/close! c)))
  state)

(defn transform-handle-llm-response
  "Common function to handle the streaming response from the LLM"
  [state msg]
  (let [d (response-chunk-delta msg)
        tool-call (first (:tool_calls d))
        c (:content d)]
    (cond
      (= msg :done)
      [state (frame/send (frame/llm-full-response-end true))]

      tool-call
      [state (frame/send (frame/llm-tool-call-chunk tool-call))]

      c
      [state (frame/send (frame/llm-text-chunk c))]

      :else [state])))

(defn transform-llm-context
  "Common function to transform LLM context into SSE request"
  [state context-frame api-key-key completions-url-key]
  (let [context-data (:frame/data context-frame)
        {:llm/keys [model]} state
        api-key (get state api-key-key)
        completions-url (get state completions-url-key)
        tools (mapv u/->tool-fn (:tools context-data))
        request-body (cond-> {:messages (:messages context-data)
                              :stream true
                              :model model}
                       (pos? (count tools)) (assoc :tools tools))]
    [state {::llm-write [(command/sse-request-command {:url completions-url
                                                       :method :post
                                                       :body request-body
                                                       :headers {"Authorization" (str "Bearer " api-key)
                                                                 "Content-Type" "application/json"}})]
            :out [(frame/llm-full-response-start true)]}]))

(defn transform-llm-processor
  "Common transform function for LLM processors"
  [state in msg api-key-key completions-url-key]
  (cond
    (= in ::llm-read)
    (transform-handle-llm-response state msg)

    (frame/llm-context? msg)
    (transform-llm-context state msg api-key-key completions-url-key)

    (frame/control-interrupt-start? msg)
    [state {:llm-write [{:command/kind :command/interrupt-request}]}]

    :else
    [state {}]))
