(ns voice-fn.processors.llm-context-aggregator
  (:require
   [clojure.string :as str]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline :refer [send-frame!]]
   [voice-fn.protocol :as p]
   [voice-fn.schema :as schema]
   [voice-fn.utils.core :as u]))

(defn concat-context-messages
  "Concat to context a new message. If the last message from the context is from
  the same role, concattenate in the same context object.
  (concat-context [{:role :system :content \"Hello\"}] :system \", world\")
  ;; => [{:role :system :content \"Hello, world\"}]
  "
  ([context entry]
   (if (vector? entry)
     (reduce concat-context-messages context entry)
     (concat-context-messages context (:role entry) (:content entry))))
  ([context role content]
   (let [last-entry (last context)
         last-entry-role (when (and last-entry (:role last-entry)) (name (:role last-entry)))]
     (if (= last-entry-role (name role))
       (into (vec (butlast context))
             [{:role role
               :content (str (:content last-entry) " " content)}])
       (into context
             [{:role role :content content}])))))

(defn process-aggregator-frame
  "Use cases implemented:
S: Start, E: End, T: Transcription, I: Interim, X: Text

         S E -> None
       S T E -> X
     S I T E -> X
     S I E T -> X
   S I E I T -> X
       S E T -> X
     S E I T -> X

 The following case would not be supported:

 S I E T1 I T2 -> X
  "
  [type pipeline processor-config frame]
  (let [{:aggregator/keys [start-frame? debug? end-frame? interim-results-frame? accumulator-frame? handles-interrupt?]
         :messages/keys [role]} processor-config
        {:keys [aggregating? seen-interim-results? aggregation seen-end-frame?]} (get-in @pipeline [type :aggregation-state])
        send-aggregation? (atom false)
        reset (fn [pipeline] (assoc-in pipeline [type :aggregation-state] {:aggregation ""
                                                                           :aggregating? false
                                                                           :seen-start-frame? false
                                                                           :seen-end-frame? false
                                                                           :seen-interim-results? false}))

        frame-data (:frame/data frame)
        maybe-send-aggregation!
        (fn []
          (let [current-aggregation (get-in @pipeline [type :aggregation-state :aggregation])]
            (when (and (string? current-aggregation)
                       (not= "" (str/trim current-aggregation)))
              (let [old-context (get-in @pipeline [:pipeline/config :llm/context])
                    llm-messages (concat-context-messages
                                   (:messages old-context)
                                   role
                                   current-aggregation)]
                (when debug?
                  (t/log! {:level :debug :id type} ["Sending new context messages" llm-messages]))
                ;; Update state first so later invocations of aggregation
                ;; processor to have the latest result
                (swap! pipeline (fn [p]
                                  (-> p
                                      (assoc-in [:pipeline/config :llm/context :messages] llm-messages)
                                      (assoc-in [type :aggregation-state :aggregation] ""))))
                (send-frame! pipeline (frame/llm-context (assoc old-context :messages llm-messages)))))))]
    (cond
      (start-frame? frame)
      ,(do
         (when debug?
           (t/log! {:level :debug :id type} "START FRAME"))
         (swap! pipeline
                assoc-in
                [type :aggregation-state]
                {;; NOTE: On start, we don't reset the aggregation. This is for
                 ;; a specific reason with deepgram where it tends to send
                 ;; multiple start speech events before we get an end utterance
                 ;; event. In the future it might be possible that we change
                 ;; this behaviour to fully reset the aggregation on start
                 ;; events based on the behaviour of other VAD systems. If this
                 ;; interferes with aggregations from LLM token streaming,
                 ;; consider splitting the implementation between user &
                 ;; assistant aggregation
                 :aggregation (or aggregation "")
                 :aggregating? true
                 :seen-start-frame? true
                 :seen-end-frame? false
                 :seen-interim-results? false}))
      (end-frame? frame)
      ,(do
         (when debug?
           (t/log! {:level :debug :id type} "END FRAME"))
         ;; WE might have received the end frame but we might still be aggregating
         ;; (i.e we have seen interim results but not the final
         ;; S E       -> No aggregation (len == 0), keep aggregating
         ;; S I E T   -> No aggregation when E arrives, keep aggregating until T
         (let [aggregating? (or seen-interim-results? (zero? (count aggregation)))]
           (swap! pipeline update-in [type :aggregation-state]
                  merge {:seen-end-frame? true
                         :seen-start-frame? false

                         :aggregating? aggregating?})
           ;; Send the aggregation if we're not aggregating anymore (no more interim results)
           (reset! send-aggregation? (not aggregating?))))

      (and (accumulator-frame? frame)
           (not (nil? frame-data))
           (not= "" (str/trim frame-data)))
      ,(do
         (when debug?
           (t/log! {:level :debug :id type} ["FRAME: " (:frame/data frame)]))
         (if aggregating?
           (do (swap! pipeline update-in [type :aggregation-state]
                      merge {:aggregation (str aggregation frame-data)
                             ;; We received final results so reset interim results
                             :seen-interim-results? false})

               ;; We received a complete sentence, so if we have seen the end
               ;; frame and we were still aggregating, it means we should send
               ;; the aggregation.
               (reset! send-aggregation? seen-end-frame?))
           (swap! pipeline assoc-in [type :aggregation-state :seen-interim-results?] false)))
      (and (fn? interim-results-frame?)
           (interim-results-frame? frame))
      ,(do
         (when debug?
           (t/log! {:level :debug :id type} ["INTERIM: " (:frame/data frame)]))
         (swap! pipeline assoc-in [type :aggregation-state :seen-interim-results?] true))
      ;; handle interruptions if the aggregator supports it
      (and (frame/control-interrupt-start? frame)
           handles-interrupt?)
      ,(do
         (maybe-send-aggregation!)
         (reset pipeline)))

    ;; maybe send new context
    (when @send-aggregation? (maybe-send-aggregation!))))

;; TODO This should be async
(defn handle-tool-call
  "Calls the registered function associated with the tool-call request if one
  exists. Emits a context frame with the added call result if it succeeded.

  pipeline - pipeline state snapshot
  frame - llm-tool-call-request frame"
  [pipeline frame]
  (let [registered-tools (get-in pipeline [:pipeline/config :llm/registered-functions])
        old-context (get-in pipeline [:pipeline/config :llm/context])
        tool (:frame/data frame)
        tool-id (:tool-call-id tool)
        tool-call-message {:role :assistant
                           :tool_calls [{:id tool-id
                                         :type :function
                                         :function {:name (:function-name tool)
                                                    :arguments (:arguments tool)}}]}]
    (when-let [f (get registered-tools (:funtion-name tool))]
      (try
        (let [tool-result (f (:arguments tool))
              new-messages [tool-call-message
                            {:role :tool
                             :content [{:type :text
                                        :text (u/json-str tool-result)}]
                             :tool_call_id tool-id}]]
          (frame/llm-context (update-in old-context [:messages] concat-context-messages new-messages)))
        (catch Exception e
          (let [error-messages [tool-call-message
                                {:role :tool
                                 :content [{:type :text
                                            :text (str "Something went wrong getting. Error: " (.getMessage e))}]
                                 :tool_call_id tool-id}]]
            (frame/llm-context (update-in old-context [:messages] concat-context-messages error-messages))))))))

(def ContextAggregatorConfig
  [:map
   {:closed true
    :description "Configuration for the LLM context aggregator processor"}
   [:messages/role schema/LLMContextMessageRole]
   [:aggregator/start-frame? frame/FramePredicate]
   [:aggregator/debug? {:optional true :default false} :boolean]
   [:aggregator/end-frame? frame/FramePredicate]
   [:aggregator/interim-results-frame? {:optional true} [:maybe frame/FramePredicate]]
   [:aggregator/handles-interrupt? {:default false} :boolean]
   [:aggregator/accumulator-frame? frame/FramePredicate]])

;; Aggregator for user

(def user-context-aggregator-options
  {:messages/role "user"
   :aggregator/start-frame? frame/user-speech-start?
   :aggregator/end-frame? frame/user-speech-stop?
   :aggregator/accumulator-frame? frame/transcription?
   :aggregator/interim-results-frame? frame/transcription-interim?
   :aggregator/handles-interrupt? false ;; User speaking shouldn't be interrupted
   :aggregator/debug? true})

(defmethod pipeline/create-processor :context.aggregator/user
  [id]
  (reify p/Processor
    (processor-id [_] id)

    (processor-schema [_] ContextAggregatorConfig)

    (accepted-frames [_] #{:frame.user/speech-start
                           :frame.user/speech-stop
                           :frame.transcription/interim
                           :frame.transcription/result})

    (make-processor-config [_ _ processor-config]
      (merge  user-context-aggregator-options
              processor-config))

    (process-frame [this pipeline processor-config frame]
      (process-aggregator-frame (p/processor-id this) pipeline processor-config frame))))

;; Aggregator for assistant
;; TODO should handle interrupt
(def assistant-context-aggregator-options
  {:messages/role "assistant"
   :aggregator/start-frame? frame/llm-full-response-start?
   :aggregator/end-frame? frame/llm-full-response-end?
   :aggregator/accumulator-frame? frame/llm-text-chunk?})

(defmethod pipeline/create-processor :context.aggregator/assistant
  [id]
  (reify p/Processor
    (processor-id [_] id)

    (processor-schema [_] ContextAggregatorConfig)

    (accepted-frames [_]
      #{:frame.llm/response-start
        :frame.llm/text-chunk
        :frame.llm/response-end
        :frame.llm/tool-request})

    (make-processor-config [_ pipeline-config processor-config]
      ;; defaults
      (merge
        assistant-context-aggregator-options
        processor-config
        {:aggregator/handles-interrupt? (:pipeline/supports-interrupt? pipeline-config)}))

    (process-frame [_ pipeline processor-config frame]

      (cond
        (frame/llm-tools-call-request? frame) (handle-tool-call @pipeline frame)
        :else (process-aggregator-frame id pipeline processor-config frame)))))
