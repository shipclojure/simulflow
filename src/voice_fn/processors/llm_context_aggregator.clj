(ns voice-fn.processors.llm-context-aggregator
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [clojure.string :as str]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
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

(defn- handle-scenario-update
  [context {:keys [messages tools]}]
  (let [new-messages (into (or (:messages context) []) messages)]
    (assoc context :messages new-messages :tools tools)))

;; Aggregator for user
(defn user-aggregator-transform
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

  (let [debug? (:aggregator/debug? state)
        {:llm/keys [context]
         :keys [aggregating? seen-interim-results? aggregation seen-end-frame?]} state
        reset #(assoc %
                      :aggregation ""
                      :aggregating? false
                      :seen-start-frame? false
                      :seen-end-frame? false
                      :seen-interim-results? false)

        frame-data (:frame/data frame)
        id "context-aggregator-user"]
    (cond
      (frame/system-config-change? frame)
      [(if-let [context (:llm/context frame-data)] (assoc state :llm/context context) state)]
      ;; user context aggregator is the source of truth for the llm context. The
      ;; assistant aggregator will send tool result frames and the user context
      ;; aggregator will send back the assembled new context
      (frame/llm-tool-call-result? frame)
      (let [tool-result (:frame/data frame)
            {:keys [run-llm? on-update] :or {run-llm? true}} (:properties tool-result)
            _ (when debug? (t/log! {:level :debug :id id} ["TOOL CALL RESULT: " tool-result]))
            nc (assoc context :messages (conj (:messages context) (:result tool-result)))]
        (when (fn? on-update) (on-update))
        [(assoc state :llm/context nc)
         ;; Send the context to LLM Inference if :run-llm? is true. :run-llm? is
         ;; false when the tool call was a scenario transition and we wait for
         ;; the next scenario-context-update frame before we request a new llm inference
         (when run-llm? {:out [frame/llm-context nc]})])
      (frame/scenario-context-update? frame)
      (do
        (when debug?
          (t/log! {:level :debug :id id} "SCENARIO UPDATE"))
        (let [scenario (:frame/data frame)
              nc (handle-scenario-update (:llm/context state) scenario)]
          [(assoc state :llm/context nc) {:out [(frame/llm-context nc)]}]))

      (frame/user-speech-start? frame)
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

      (frame/user-speech-stop? frame)
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
             (let [nc (next-context {:context context :aggregation aggregation :role "user"})]
               [(reset (assoc state :llm/context nc)) {:out [(frame/llm-context nc)]}])
             [(assoc state
                     :seen-end-frame? true
                     :seen-start-frame? false
                     :aggregation aggregation)])))

      (and (frame/transcription? frame)
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
             (let [nc (next-context {:context context :aggregation (str aggregation new-agg) :role "user"})]
               [(reset (assoc state :llm/context nc)) {:out [(frame/llm-context nc)]}])
             [(assoc state
                     :aggregation (str aggregation new-agg)
                     :seen-interim-results? false)])
           [(assoc state :seen-interim-results? false)]))
      (frame/transcription-interim? frame)
      ,(do
         (when debug?
           (t/log! {:level :debug :id id} ["INTERIM: " (:frame/data frame)]))
         [(assoc state :seen-interim-results? true)])

      :else [state])))

(def user-aggregator-process
  "Aggregates user messages into the conversation context"
  (flow/process
    {:describe (fn [] {:ins {:sys-in "Channel for receiving system messages that take priority"
                             :in "Channel for aggregation messages"}
                       :outs {:out "Channel where new context aggregations are put"}
                       :params {:llm/context "Initial LLM context. See schema/LLMContext"
                                :aggregator/debug? "Optional When true, debug logs will be called"}})

     :workload :compute
     :init identity
     :transform user-aggregator-transform}))

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

(defn next-assistant-context
  [{:keys [context content-aggregation function-name function-arguments tool-call-id]}]
  (let [next-msg (if function-name
                   {:role :assistant
                    :tool_calls [{:id tool-call-id
                                  :type :function
                                  :function {:name function-name
                                             :arguments function-arguments}}]}
                   {:role :assistant
                    :content [{:type :text
                               :text content-aggregation}]})]
    (assoc context :messages (conj (:messages context) next-msg))))

(defn assistant-aggregator-transform
  "Assembles assistant messages and tool call requests. When a tool call request
  is encountered, it is sent to the tool caller process. See the :init of the
  assistant-aggregator-process for details on the tool-caller."
  [state _ frame]
  (let [{:llm/keys [context]
         :flow/keys [handles-interrupt?]
         :keys [content-aggregation function-name function-arguments tool-call-id debug?]} state
        reset-aggregation-state #(assoc %
                                        :content-aggregation nil
                                        :function-name nil
                                        :function-arguments nil
                                        :tool-call-id nil)

        id "context-aggregator-assistant"]
    (cond
      (frame/system-config-change? frame)
      (do
        (when debug? (t/log! {:level :debug :id id} ["SYSTEM CONFIG CHANGE" (:frame/data frame)]))
        (let [config (:frame/data frame)]
          [(cond-> state
             (:llm/context config) (assoc :llm/context (:llm/context config)))]))
      (frame/llm-context? frame)
      [(assoc state :llm/context (:frame/data frame))]

      (frame/llm-full-response-start? frame)
      ,(do
         (when debug?
           (t/log! {:level :debug :id id} "START FRAME"))
         [(assoc state
                 :content-aggregation nil
                 :function-name nil
                 :function-arguments nil
                 :tool-call-id nil)])
      (frame/llm-full-response-end? frame)
      ,(do
         (when debug?
           (t/log! {:level :debug :id id} "END FRAME"))
         (let [nc (next-assistant-context {:context context
                                           :content-aggregation content-aggregation
                                           :function-name function-name
                                           :function-arguments function-arguments
                                           :tool-call-id tool-call-id})
               tool-call? (boolean function-name)
               nf (frame/llm-context nc)]
           [(reset-aggregation-state (assoc state :llm/context nc)) (cond-> {:out [nf]}
                                                                      tool-call? (assoc :tool-write [nf]))]))

      (frame/llm-text-chunk? frame)
      (let [chunk (:frame/data frame)]
        (when debug?
          (t/log! {:level :debug :id id} ["LLM CHUNK: " chunk]))
        ;; if we seen end frame, we send aggregation
        ;; else
        [(assoc state
                :content-aggregation (str content-aggregation chunk))])

      (frame/llm-tool-call-chunk? frame)
      (let [tool-call (:frame/data frame)
            _ (when debug? (t/log! {:level :debug :id id} ["TOOL CALL CHUNK: " tool-call]))
            {:keys [arguments name]} (:function tool-call)
            tci (:id tool-call)]
        [(assoc state
                :function-name (or function-name name)
                :function-arguments (str function-arguments arguments)
                :tool-call-id (or tool-call-id tci))])

      (frame/llm-tool-call-result? frame)
      (let [tool-result (:frame/data frame)
            _ (when debug? (t/log! {:level :debug :id id} ["TOOL CALL RESULT: " tool-result]))]
        ;; send tool call results further (should be user context aggregator to
        ;; assemble the full context)
        [state {:out [frame]}])

      ;; handle interruptions if the aggregator supports it
      (and (frame/control-interrupt-start? frame)
           handles-interrupt?)
      ,(let [nc (next-context {:context context :aggregation content-aggregation :role "assistant"})
             v? (valid-aggregation? content-aggregation)
             next-state (if v? (assoc state :llm/context nc) state)]
         [(reset-aggregation-state next-state) (when (valid-aggregation? content-aggregation) {:out [(frame/llm-context nc)]})])

      :else [state])))

(defn- context->tool-call
  [context]
  (-> context :messages last :tool_calls first))

(defn- get-tool [tool-name tools]
  (first (filter #(= tool-name (get-in % [:function :name])) tools)))

(defn assistant-aggregator-init
  [args]
  (let [tool-read (a/chan 100)
        tool-write (a/chan 100)
        tool-call-loop
        #(loop []
           (when-let [frame (a/<!! tool-write)]
             (assert (frame/llm-context? frame) "Tool caller accepts only llm-context frames")
             (let [context (:frame/data frame)
                   tool-call (context->tool-call context)
                   tool-id (:id tool-call)
                   fname (get-in tool-call [:function :name])
                   args (u/parse-if-json (get-in tool-call [:function :arguments]))
                   fndef (get-tool fname (:tools context))
                   f (get-in fndef [:function :handler])
                   transition-cb (get-in fndef [:function :transition-cb])]
               (t/log! {:id :tool-caller :level :debug} ["Got tool-call-request" tool-call])
               (if (fn? f)
                 (let [tool-result (u/await-or-return f args)]
                   (a/>!! tool-read  (frame/llm-tool-call-result
                                       {:result {:role :tool
                                                 :content [{:type :text
                                                            :text (u/json-str tool-result)}]
                                                 :tool_call_id tool-id}
                                        ;; don't run llm if this is a transition
                                        ;; function, to wait for the new context
                                        ;; messages from the new scenario node
                                        :properties {:run-llm? (fn? transition-cb)
                                                     :on-update transition-cb}})))
                 (a/>!! tool-read (frame/llm-tool-call-result
                                    {:result {:role :tool
                                              :content [{:type :text
                                                         :text "Tool not found"}]
                                              :tool_call_id tool-id}}))))
             (recur)))]
    ((flow/futurize tool-call-loop :exec :io))
    (merge args {::flow/in-ports {:tool-read tool-read}
                 ::flow/out-ports {:tool-write tool-write}})))

(def assistant-context-aggregator
  "Takes streaming tool-call request tokens and returns a new context with the
  tool call result if a tool registered is available."
  (flow/process
    {:describe
     (fn []
       {:ins {:sys-in "Channel for receiving system messages that take priority"
              :in "Channel for streaming tool call requests"}
        :outs {:out "Channel for output new contexts with tool call results"}
        :params {:llm/context "Initial LLM context. See schema/LLMContext"
                 :flow/handles-interrupt? "Wether the flow handles user interruptions. Default false"}})
     :init assistant-aggregator-init
     :transform assistant-aggregator-transform}))
