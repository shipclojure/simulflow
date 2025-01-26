(ns voice-fn.processors.llm-context-aggregator
  (:require
   [clojure.core.async.flow :as flow]
   [clojure.string :as str]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
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

(defn valid-aggregation?
  [a]
  (and (string? a)
       (not= "" (str/trim a))))

(defn next-context
  [{:keys [context role aggregation]}]
  (assoc context :messages (concat-context-messages
                             (:messages context)
                             role
                             aggregation)))

(defn aggregator-transform
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
  [state _ frame]
  (when (:aggregator/debug? state)
    (t/log! {:level :debug :id :aggregator} {:type (:frame/type frame) :data (:frame/data frame)}))

  (let [{:aggregator/keys [start-frame? debug? end-frame? interim-results-frame? accumulator-frame? handles-interrupt?]
         :messages/keys [role]
         :llm/keys [context]
         :keys [aggregating? seen-interim-results? aggregation seen-end-frame?]} state
        reset #(assoc %
                      :aggregation ""
                      :aggregating? false
                      :seen-start-frame? false
                      :seen-end-frame? false
                      :seen-interim-results? false)

        frame-data (:frame/data frame)
        id (str "context-aggregator-" (name role))]
    (cond
      (frame/llm-context? frame)
      (do
        (when debug?
          (t/log! {:level :debug :id id} ["CONTEXT FRAME" frame-data]))
        [(assoc state :llm/context frame-data)])

      (start-frame? frame)
      ,(do
         (when debug?
           (t/log! {:level :debug :id id} "START FRAME"))
         [(assoc state
                 ;; NOTE: On start, we don't reset the aggregation. This is for
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
                 :seen-interim-results? false)])
      (end-frame? frame)
      ,(do
         (when debug?
           (t/log! {:level :debug :id id} "END FRAME"))
         ;; WE might have received the end frame but we might still be aggregating
         ;; (i.e we have seen interim results but not the final
         ;; S E       -> No aggregation (len == 0), keep aggregating
         ;; S I E T   -> No aggregation when E arrives, keep aggregating until T
         (let [keep-aggregating? (or seen-interim-results? (zero? (count aggregation)))
               ;; Send the aggregation if we're not aggregating anymore (no more interim results)
               send-agg? (not keep-aggregating?)]
           (if send-agg?
             (let [nc (next-context {:context context :aggregation aggregation :role role})]
               [(reset (assoc state :llm/context nc)) {:out [(frame/llm-context nc)]}])
             [(assoc state
                     :seen-end-frame? true
                     :seen-start-frame? false
                     :aggregation aggregation)])))

      (and (accumulator-frame? frame)
           (not (nil? frame-data))
           (not= "" (str/trim frame-data)))
      ,(let [new-agg (:frame/data frame)]
         (when debug?
           (t/log! {:level :debug :id id} ["FRAME: " new-agg]))
         ;; if we seen end frame, we send aggregation
         ;; else
         (if aggregating?
           (if seen-end-frame?
             ;; send aggregtation
             (let [nc (next-context {:context context :aggregation (str aggregation new-agg) :role role})]
               [(reset (assoc state :llm/context nc)) {:out [(frame/llm-context nc)]}])
             [(assoc state
                     :aggregation (str aggregation new-agg)
                     :seen-interim-results? false)])
           [(assoc state :seen-interim-results? false)]))
      (and (fn? interim-results-frame?)
           (interim-results-frame? frame))
      ,(do
         (when debug?
           (t/log! {:level :debug :id id} ["INTERIM: " (:frame/data frame)]))
         [(assoc state :seen-interim-results? true)])

      ;; handle interruptions if the aggregator supports it
      (and (frame/control-interrupt-start? frame)
           handles-interrupt?)
      ,(let [nc (next-context {:context context :aggregation aggregation :role role})
             v? (valid-aggregation? aggregation)
             next-state (if v? (assoc state :llm/context nc) state)]
         [(reset next-state) (when (valid-aggregation? aggregation) {:out [(frame/llm-context nc)]})])
      :else [state])))

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

;; Aggregator for assistant
;; TODO should handle interrupt
(def assistant-context-aggregator-options
  {:messages/role "assistant"
   :aggregator/start-frame? frame/llm-full-response-start?
   :aggregator/end-frame? frame/llm-full-response-end?
   :aggregator/accumulator-frame? frame/llm-text-chunk?})

(def context-aggregator-process
  (flow/process
    {:describe (fn [] {:ins {:in "Channel for aggregation messages"}
                       :outs {:out "Channel where new context aggregations are put"}})
     :params {:llm/context "Initial LLM context. See schema/LLMContext"
              :messages/role "Role that this processor aggregates"
              :aggregator/start-frame? "Predicate checking if the frame is a start-frame?"
              :aggregator/end-frame? "Predicate checking if the frame is a end-frame?"
              :aggregator/accumulator-frame? "Predicate checking the main type of frame we are aggregating"
              :aggregator/interim-results-frame? "Optional predicate checking if the frame is an interim results frame"
              :aggregator/handles-interrupt? "Optional Wether this aggregator should handle or not interrupts"
              :aggregator/debug? "Optional When true, debug logs will be called"}
     :workload :compute
     :init identity
     :transform aggregator-transform}))

(defn sentence-assembler
  "Takes in llm-text-chunk frames and returns a full sentence. Useful for
  generating speech sentence by sentence."
  ([] {:ins {:in "Channel for llm text chunks"}
       :outs {:out "Channel for assembled speak frames"}})
  ([_] {:acc nil})
  ([{:keys [acc]} _ msg]
   (when (frame/llm-text-chunk? msg)
     (let [{:keys [sentence accumulator]} (u/assemble-sentence acc (:frame/data msg))]
       (if sentence
         [{:acc accumulator} {:out [(frame/speak-frame sentence)]}]
         [{:acc accumulator}])))))
