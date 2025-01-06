(ns voice-fn.processors.llm-context-aggregator
  (:require
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline :refer [send-frame!]]
   [voice-fn.schema :as schema]))

(defn concat-context
  "Concat to context a new message. If the last message from the context is from
  the same role, concattenate in the same context object.
  (concat-context [{:role :system :content \"Hello\"}] :system \", world\")
  ;; => [{:role :system :content \"Hello, world\"}]
  "
  ([context entry]
   (concat-context context (:role entry) (:content entry)))
  ([context role content]
   (let [last-entry (last context)
         last-entry-role (when (and last-entry (:role last-entry)) (name (:role last-entry)))]
     (if (= last-entry-role (name role))
       (into (vec (butlast context))
             [{:role (name role)
               :content (str (:content last-entry) " " content)}])
       (into context
             [{:role (name role) :content content}])))))

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
  [type pipeline processor frame]
  (let [{:aggregator/keys [start-frame? debug? end-frame? interim-results-frame? accumulator-frame?]
         :messages/keys [role]} (:processor/config processor)
        {:keys [aggregating? seen-interim-results? aggregation seen-end-frame?]} (get-in @pipeline [type :aggregation-state])
        send-aggregation? (atom false)]
    (cond
      (start-frame? frame)
      (do
        (when debug?
          (t/log! {:level :debug :id type} "Got start frame"))
        (swap! pipeline assoc-in [type :aggregation-state] {:aggregation ""
                                                            :aggregating? true
                                                            :seen-start-frame? true
                                                            :seen-end-frame? false
                                                            :seen-interim-results? false}))
      (end-frame? frame)
      (do
        (when debug?
          (t/log! {:level :debug :id type} "Got end frame"))
        (let [aggregating? (or seen-interim-results? (zero? (count aggregation)))]
          (swap! pipeline update-in [type :aggregation-state]
                 merge {:seen-end-frame? true
                        :seen-start-frame? false
                        ;; WE might have received the end frame but we might still be aggregating
                        ;; (i.e we have seen interim results but not the final text)
                        :aggregating? aggregating?})
          ;; Send the aggregation if we're not aggregating anymore (no more interim results)
          (reset! send-aggregation? (not aggregating?))))

      (accumulator-frame? frame)
      (do
        (when debug?
          (t/log! {:level :debug :id type} ["Got accumulator frame" (:frame/data frame)]))
        (if aggregating?
          (do (swap! pipeline update-in [type :aggregation-state]
                     merge {:aggregation (str aggregation (:frame/data frame))
                            ;; We received final results so reset interim results
                            :seen-interim-results? false})

              ;; We received a complete sentence, so if we have seen the end frame
              ;; and we were still aggregating, it means we should send the
              ;; aggregation
              (reset! send-aggregation? seen-end-frame?))
          (swap! pipeline assoc-in [type :aggregation-state :seen-interim-results?] false)))
      (and (fn? interim-results-frame?)
           (interim-results-frame? frame))
      (do
        (when debug?
          (t/log! {:level :debug :id type} ["Got interim frame" (:frame/data frame)]))
        (swap! pipeline assoc-in [type :aggregation-state :seen-interim-results?] true)))
    (when @send-aggregation?
      (let [final-aggregation (get-in @pipeline [type :aggregation-state :aggregation])
            llm-context (concat-context
                          (get-in @pipeline [:pipeline/config :llm/context])
                          role
                          final-aggregation)]
        (when debug?
          (t/log! {:level :debug :id type} ["Sending new context" llm-context]))
        (swap! pipeline assoc-in [:pipeline/config :llm/context] llm-context)
        (send-frame! pipeline (frame/context-messages llm-context))))))

(def ContextAggregatorConfig
  [:map
   {:closed true
    :description "Configuration for the LLM context aggregator processor"}
   [:messages/role schema/LLMContextessageRole]
   [:aggregator/start-frame? schema/FramePredicate]
   [:aggregator/debug? {:optional true :default false} :boolean]
   [:aggregator/end-frame? schema/FramePredicate]
   [:aggregator/interim-results-frame? {:optional true} [:maybe schema/FramePredicate]]
   [:aggregator/accumulator-frame? schema/FramePredicate]])

;; Aggregator for user

(def user-context-aggregator-options
  {:messages/role "user"
   :aggregator/start-frame? frame/user-speech-start?
   :aggregator/end-frame? frame/user-speech-stop?
   :aggregator/accumulator-frame? frame/transcription-complete?
   :aggregator/interim-results-frame? frame/transcription-interim?
   :aggregator/debug? true})

(defmethod pipeline/processor-schema :context.aggregator/user
  [_]
  ContextAggregatorConfig)

(defmethod pipeline/make-processor-config :context.aggregator/user
  [_ _ processor-config]
  (merge user-context-aggregator-options
         processor-config))
(defmethod pipeline/accepted-frames :context.aggregator/user
  [_]
  #{:frame.user/speech-start
    :frame.user/speech-stop
    :frame.transcription/interim
    :frame.transcription/complete})

(defmethod pipeline/process-frame :context.aggregator/user
  [type pipeline processor frame]
  (process-aggregator-frame type pipeline processor frame))

;; Aggregator for assistant

(def assistant-context-aggregation-options
  {:messages/role "assistant"
   :aggregator/start-frame? frame/llm-full-response-start?
   :aggregator/end-frame? frame/llm-full-response-end?
   :aggregator/accumulator-frame? frame/llm-text-chunk?})

(defmethod pipeline/processor-schema :context.aggregator/assistant
  [_]
  ContextAggregatorConfig)

(defmethod pipeline/make-processor-config :context.aggregator/assistant
  [_ _ processor-config]
  (merge assistant-context-aggregation-options
         processor-config))
(defmethod pipeline/accepted-frames :context.aggregator/assistant
  [_]
  #{:frame.llm/response-start
    :frame.llm/text-chunk
    :frame.llm/response-end})

(defmethod pipeline/process-frame :context.aggregator/assistant
  [type pipeline processor frame]
  (process-aggregator-frame type pipeline processor frame))
