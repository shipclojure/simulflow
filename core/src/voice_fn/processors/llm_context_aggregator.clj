(ns voice-fn.processors.llm-context-aggregator
  (:require
   [malli.core :as m]
   [voice-fn.frames :as f]
   [voice-fn.pipeline :as pipeline :refer [send-frame!]]
   [voice-fn.schema :as schema]))

concat

(defn concat-context
  "Concat to context a new message. If the last message from the context is from
  the same role, concattenate in the same context object.
  (concat-context [{:role :system :content \"Hello\"}] :system \", world\")
  ;; => [{:role :system :content \"Hello, world\"}]
  "
  ([context entry]
   (concat-context context (:role entry) (:content entry)))
  ([context role content]
   (let [last-entry (last context)]
     (if (= (name (:role last-entry)) (name role))
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
  (let [{:aggregator/keys [start-frame? end-frame? interim-results-frame? accumulator-frame?]
         :messages/keys [role]} processor-config
        {:keys [aggregating? seen-interim-results? aggregation seen-end-frame?]} (get-in @pipeline [type :aggregation-state])
        send-aggregation? (atom false)]
    (cond
      (start-frame? frame)
      (swap! pipeline assoc-in [type :aggregation-state] {:aggregation ""
                                                          :aggregating? true
                                                          :seen-start-frame? true
                                                          :seen-end-frame? false
                                                          :seen-interim-results? false})
      (end-frame? frame)
      (let [aggregating? (or seen-interim-results? (zero? (count aggregation)))]
        (swap! pipeline update-in [type :aggregation-state]
               merge {:seen-end-frame? true
                      :seen-start-frame? false
                      ;; WE might have received the end frame but we might still be aggregating
                      ;; (i.e we have seen interim results but not the final text)
                      :aggregating? aggregating?})
        ;; Send the aggregation if we're not aggregating anymore (no more interim results)
        (reset! send-aggregation? (not aggregating?)))

      (accumulator-frame? frame)
      (if aggregating?
        (do (swap! pipeline update-in [type :aggregation-state]
                   merge {:aggregation (str aggregation (:frame/data frame))
                          ;; We received final results so reset interim results
                          :seen-interim-results? false})

            ;; We received a complete sentence, so if we have seen the end frame
            ;; and we were still aggregating, it means we should send the
            ;; aggregation
            (reset! send-aggregation? seen-end-frame?))
        (swap! pipeline assoc-in [type :aggregation-state :seen-interim-results?] false))
      (and (fn? interim-results-frame?)
           (interim-results-frame? frame))
      (swap! pipeline assoc-in [type :aggregation-state :seen-interim-results?] true))
    (when @send-aggregation?
      (let [final-aggregation (get-in @pipeline [type :aggregation-state :aggregation])
            llm-context (concat-context
                          (get-in @pipeline [:pipeline/config :llm/context])
                          role
                          final-aggregation)]
        (send-frame! pipeline (f/llm-messages-frame llm-context))))))

(def ContextAggregatorConfig
  [:map
   {:closed true
    :description "Configuration for the LLM context aggregator processor"}
   [:messages/role schema/LLMContextessageRole]
   [:aggregator/start-frame? schema/FramePredicate]
   [:aggregator/end-frame? schema/FramePredicate]
   [:aggregator/interim-results-frame? {:optional true} [:maybe schema/FramePredicate]]
   [:aggregator/accumulator-frame? schema/FramePredicate]])

(def user-context-aggregator-options
  {:messages/role "user"
   :aggregator/start-frame? f/user-started-speaking-frame?
   :aggregator/end-frame? f/user-stopped-speaking-frame?
   :aggregator/accumulator-frame? f/transcription-frame?
   :aggregator/interim-results-frame? f/interim-transcription-frame?})

(defmethod pipeline/processor-schema :context.aggregator/user
  [_]
  ContextAggregatorConfig)

(defmethod pipeline/processor-schema :context.aggregator/assistant
  [_]
  ContextAggregatorConfig)

(defmethod pipeline/make-processor-config :context.aggregator/user
  [_ _ processor-config]
  (merge user-context-aggregator-options
         processor-config))

(defmethod pipeline/process-frame :context.aggregator/user
  [type pipeline processor frame]
  (process-aggregator-frame type pipeline processor frame))
