(ns simulflow.processors.llm-context-aggregator
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [clojure.string :as str]
   [simulflow.frame :as frame]
   [simulflow.utils.core :as u]
   [taoensso.telemere :as t]))

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

(defn user-speech-aggregator-transform
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
    (t/log! {:level :debug :id :user-context-aggregator :data {:type (:frame/type frame) :data (:frame/data frame)} :msg "Got frame"}))

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
      ,(let [new-transcription (:frame/data frame)]
         (t/log! {:level :info :id id} ["TRANSCRIPTION: " new-transcription])
         ;; if we seen end frame, we send aggregation
         ;; else
         (if aggregating?
           (if seen-end-frame?
             ;; send aggregtation
             (let [nc (next-context {:context context :aggregation (str/trim (str aggregation " " new-transcription)) :role "user"})]
               [(reset (assoc state :llm/context nc)) {:out [(frame/llm-context nc)]}])
             [(assoc state
                     :aggregation (str aggregation new-transcription)
                     :seen-interim-results? false)])
           [(assoc state :seen-interim-results? false)]))

      (frame/transcription-interim? frame)
      ,(do
         (when debug?
           (t/log! {:level :debug :id id} ["INTERIM: " (:frame/data frame)]))
         [(assoc state :seen-interim-results? true)])

      :else [state])))

(def ^:private user-speech-predicates
  #{frame/user-speech-start?
    frame/user-speech-stop?
    frame/transcription?
    frame/transcription-interim?})

(defn- user-speech-frame?
  [frame]
  (some #(% frame) user-speech-predicates))

(defn context-aggregator-transform
  [state _ frame]
  (when (:aggregator/debug? state)
    (t/log! {:level :debug :id :llm-context} {:type (:frame/type frame) :data (:frame/data frame)}))

  (let [debug? (:aggregator/debug? state)
        {:llm/keys [context]} state

        frame-data (:frame/data frame)
        id "context-aggregator-user"]

    (cond
      (frame/system-config-change? frame)
      ,[(if-let [context (:llm/context frame-data)] (assoc state :llm/context context) state)]

      ;; context aggregator is the source of truth for the llm context. The
      ;; assistant aggregator will send tool result frames and the user context
      ;; aggregator will send back the assembled new context
      (frame/llm-tool-call-result? frame)
      ,(let [tool-result (:frame/data frame)
             {:keys [run-llm? on-update] :or {run-llm? true}} (:properties tool-result)
             _ (when debug? (t/log! {:level :debug :id id} ["TOOL CALL RESULT: " tool-result]))
             nc (update-in context [:messages] conj (:result tool-result))]
         (when (fn? on-update) (on-update))
         [(assoc state :llm/context nc)
          (frame/send
            ;; Send the context further if :run-llm? is true. :run-llm? is false
            ;; when the tool call was a scenario transition and we wait for the
            ;; next scenario-context-update frame before we request a new llm
            ;; inference
            (when run-llm? (frame/llm-context nc))
            ;; send the llm-tool-call-result further in case other processors need it
            frame)])

      (frame/llm-context-messages-append? frame)
      ,(let [{new-messages :messages
              opts :properties} (:frame/data frame)
             nc (update-in (:llm/context state) [:messages] into new-messages)
             tool-request-message (first (filter #(seq (:tool_calls %)) new-messages))]
         [(assoc state :llm/context nc) (cond-> {}
                                          ;; if it's a tool call, send to the tool-caller to obtain the result
                                          (:tool-call? opts) (assoc :tool-write [(frame/llm-context nc)]
                                                                    :sys-out [(frame/llm-tool-call-request tool-request-message)])
                                          ;; send new context further to the LLM
                                          (:run-llm? opts) (assoc :out [(frame/llm-context nc)]))])

      ;; Scenario update frames come when a scenario manager moves the LLM to a
      ;; new node. See simulflow.scenario-manager for details
      (frame/scenario-context-update? frame)
      ,(let [scenario (:frame/data frame)
             _ (when debug?
                 (t/log! {:level :debug :id id} ["SCENARIO UPDATE" scenario]))
             nc (handle-scenario-update (:llm/context state) scenario)]
         [(assoc state :llm/context nc) (when (:run-llm? (:properties scenario))
                                          {:out [(frame/llm-context nc)]})])

      (frame/speak-frame? frame)
      [(update-in state [:llm/context :messages] conj {:role :assistant :content (:frame/data frame)})]

      (user-speech-frame? frame) (user-speech-aggregator-transform state _ frame)

      :else [state])))

(defn- get-tool [tool-name tools]
  (first (filter #(= tool-name (get-in % [:function :name])) tools)))

(defn default-tool-result-adapter
  "Tool result in the openai format"
  [{:keys [result tool-id]}]
  {:role :tool
   :content [{:type :text
              :text (if (string? result) result (u/json-str result))}]
   :tool_call_id tool-id})

(defn handle-tool-call
  "Given a llm-context frame with the last message being a tool call request,
  return the result of that tool call request as a `llm-tool-call-result` frame."
  ([frame]
   (handle-tool-call frame default-tool-result-adapter))
  ([frame tool-result-adapter]
   (assert (frame/llm-context? frame) "Tool caller accepts only llm-context frames")
   (let [context (:frame/data frame)
         tool-call-msg (-> context :messages last)
         tool-call (-> tool-call-msg :tool_calls first)
         tool-id (:id tool-call)
         fname (get-in tool-call [:function :name])
         args (u/parse-if-json (get-in tool-call [:function :arguments]))
         fndef (get-tool fname (:tools context))
         f (get-in fndef [:function :handler])
         transition-cb (get-in fndef [:function :transition-cb])]
     (if (fn? f)
       (let [tool-result (u/await-or-return f args)]
         (frame/llm-tool-call-result
           {:request tool-call-msg
            :result (tool-result-adapter {:result tool-result
                                          :tool-id tool-id
                                          :fname fname})
            ;; don't run llm if this is a transition
            ;; function, to wait for the new context
            ;; messages from the new scenario node
            :properties {:run-llm? (nil? transition-cb)
                         :on-update #(when transition-cb (transition-cb args))}}))
       (frame/llm-tool-call-result
         {:request tool-call-msg
          :result (tool-result-adapter {:result "Tool not found"
                                        :tool-it tool-id
                                        :fname fname})})))))

(defn context-aggregator-init
  "Launches tool caller process. The tool caller process handles calling tool call
  requests from the LLM and sends back the results to be aggregated into the
  context."
  [{:llm/keys [tool-result-adapter]
    :or {tool-result-adapter default-tool-result-adapter} :as args}]
  (let [tool-read (a/chan 100)
        tool-write (a/chan 100)
        tool-call-loop
        #(loop []
           (when-let [frame (a/<!! tool-write)]
             (a/>!! tool-read (handle-tool-call frame tool-result-adapter))
             (recur)))]
    ((flow/futurize tool-call-loop :exec :io))
    (merge args {::flow/in-ports {:tool-read tool-read}
                 ::flow/out-ports {:tool-write tool-write}})))

(defn next-assistant-message
  [{:keys [content-aggregation function-name function-arguments tool-call-id]}]
  (if function-name
    {:role :assistant
     :tool_calls [{:id tool-call-id
                   :type :function
                   :function {:name function-name
                              :arguments function-arguments}}]}
    {:role :assistant
     :content [{:type :text
                :text content-aggregation}]}))

(defn assistant-context-assembler-transform
  "Assembles assistant messages and tool call requests."
  [state _ frame]
  (let [{:keys [content-aggregation function-name function-arguments tool-call-id debug?]} state
        reset-aggregation-state #(assoc %
                                        :content-aggregation nil
                                        :function-name nil
                                        :function-arguments nil
                                        :tool-call-id nil)

        id :assistant-context-assembler]
    (cond

      (frame/llm-full-response-start? frame)
      ,(do
         (when debug?
           (t/log! {:level :debug :id id :data frame} "START FRAME"))
         [(assoc state
                 :content-aggregation nil
                 :function-name nil
                 :function-arguments nil
                 :tool-call-id nil)])
      (frame/llm-full-response-end? frame)
      ,(do
         (when debug?
           (t/log! {:level :debug :id id} "END FRAME"))
         (let [nm (next-assistant-message {:content-aggregation content-aggregation
                                           :function-name function-name
                                           :function-arguments function-arguments
                                           :tool-call-id tool-call-id})
               tool-call? (boolean function-name)
               out-frame (frame/llm-context-messages-append {:messages [nm]
                                                             ;; don't run llm on this new context since it would cause an infinite loop
                                                             :properties {:run-llm? false
                                                                          :tool-call? tool-call?}})]
           [(reset-aggregation-state state) {:out [out-frame]}]))

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

      :else [state])))

(defn llm-sentence-assembler-transform
  [state _ msg]
  (cond
    ;; Handle interruption start - clear partial sentence
    (frame/control-interrupt-start? msg)
    (let [partial-sentence (::accumulator state)]
      (when (seq partial-sentence)
        (t/log! {:level :debug :id :sentence-assembler}
                (str "[Interrupted] Discarding partial sentence: " (subs partial-sentence 0 (min 50 (count partial-sentence))) "...")))
      [(-> state
           (assoc ::accumulator "")
           (assoc :pipeline/interrupted? true))
       {}])

    ;; Handle interruption end
    (frame/control-interrupt-stop? msg)
    [(assoc state :pipeline/interrupted? false) {}]

    ;; Only process LLM text chunks when not interrupted
    (and (frame/llm-text-chunk? msg) (not (:pipeline/interrupted? state)))
    (let [{:keys [sentence accumulator]} (u/assemble-sentence (::accumulator state) (:frame/data msg))]
      (if sentence
        (do
          (t/log! {:level :trace :id :sentence-assembler} ["New sentence assembled: " sentence])
          [(assoc state ::accumulator accumulator) (frame/send (frame/speak-frame sentence))])
        [(assoc state ::accumulator accumulator)]))

    ;; Drop LLM chunks during interruption
    (and (frame/llm-text-chunk? msg) (:pipeline/interrupted? state))
    [state {}]

    :else [state {}]))

(defn llm-sentence-assembler-fn
  "Takes in llm-text-chunk frames and returns a full sentence. Useful for
  generating speech sentence by sentence, instead of waiting for the full LLM message."
  ([] {:ins {:in "Channel for llm text chunks"
             :sys-in "Channel for system messages"}
       :outs {:out "Channel for assembled speak frames"
              :sys-out "Channel for system frames"}})
  ([_] {::accumulator ""})
  ([state _transition]
   state)
  ([state in msg]
   (llm-sentence-assembler-transform state in msg)))

;; =============================================================================
;; Processors

(def context-aggregator
  "Aggregates context messages. Keeps the full conversation history."
  (flow/process
    (flow/map->step
      {:describe (fn [] {:ins {:sys-in "Channel for receiving system messages that take priority"
                               :in "Channel for aggregation messages"}
                         :outs {:out "Channel where new context aggregations are put"
                                :sys-out "Channel on which new system frames are outputted"}
                         :params {:llm/context "Initial LLM context. See schema/LLMContext"
                                  :llm/tool-result-adapter "Adapter used to transform tool call results into accepted formats. Defaults to openai format"
                                  :aggregator/debug? "Optional When true, debug logs will be called"}})

       :workload :compute
       :init context-aggregator-init
       :transition (fn [{::flow/keys [in-ports out-ports] :as state} transition]
                     (when (= transition ::flow/stop)
                       (doseq [port (concat (vals in-ports) (vals out-ports))]
                         (a/close! port)))
                     state)
       :transform context-aggregator-transform})))

(def assistant-context-assembler
  "Assembles streaming tool-call request or message tokens from the LLM."
  (flow/process
    (flow/map->step
      {:describe
       (fn []
         {:ins {:sys-in "Channel for receiving system messages that take priority"
                :in "Channel for streaming tool call requests"}
          :outs {:out "Channel for output new contexts with tool call results"}
          :params {:llm/context "Initial LLM context. See schema/LLMContext"
                   :flow/handles-interrupt? "Wether the flow handles user interruptions. Default false"}})
       :init identity
       :transition (fn [state _transition] state)
       :transform assistant-context-assembler-transform})))

(def llm-sentence-assembler
  "Takes in llm-text-chunk frames and returns a full sentence. Useful for
  generating speech sentence by sentence, instead of waiting for the full LLM message."
  (flow/process #'llm-sentence-assembler-fn))
