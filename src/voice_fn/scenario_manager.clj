(ns voice-fn.scenario-manager
  "Scenario Manager is a way to build structured conversations with the underlying
  LLM. In enables you to create predefined conversation scenarios that follow a
  specific flow. Use it when the interaction is highly structured.

  The scenario manager works by appending the messages that define the current
  node to the existing llm context."
  (:require
   [clojure.core.async.flow :as flow]
   [malli.core :as m]
   [malli.error :as me]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.schema :as schema]))

(def ScenarioAction
  [:or
   [:map
    [:type (schema/flex-enum ["tts-say"])]
    [:text :string]]
   [:map
    [:type (schema/flex-enum ["end-conversation"])]]
   [:map
    [:type :keyword]
    [:handler [:=> :cat [:any] :any]]]])

(def ScenarioConfig
  [:and [:map {:closed true}
         [:initial-node :keyword]
         [:nodes [:map-of
                  :keyword
                  [:map {:closed true}
                   [:run-llm? {:optional true} :boolean]
                   [:role-messages {:optional true} [:vector schema/LLMSystemMessage]]
                   [:task-messages [:vector schema/LLMSystemMessage]]
                   [:functions [:vector [:or
                                         schema/LLMFunctionToolDefinitionWithHandling
                                         schema/LLMTransitionToolDefinition]]]
                   [:pre-actions {:optional true
                                  :description "Actions to be invoked when the node is selected."} [:vector ScenarioAction]]
                   [:post-actions {:optional true
                                   :description "Actions to be invoked when the node will be replaced."} [:vector ScenarioAction]]]]]]
   [:fn {:error/message "Initial node not defined"}
    (fn [sc]
      (boolean (get-in sc [:nodes (:initial-node sc)])))]
   [:fn {:error/fn (fn [{:keys [value]} _]
                     (let [nodes (set (keys (:nodes value)))
                           transitions (->> value
                                            :nodes
                                            vals
                                            (mapcat :functions)
                                            (keep (fn [f] (get-in f [:function :transition-to])))
                                            (remove nil?))
                           invalid-transition (first (remove nodes transitions))]
                       (when invalid-transition
                         (format "Unreachable node: %s" invalid-transition))))}
    (fn [{:keys [nodes]}]
      (let [defined-nodes (set (keys nodes))
            transitions (->> nodes
                             vals
                             (mapcat :functions)
                             (keep (fn [f] (get-in f [:function :transition-to])))
                             (remove nil?))]
        (every? defined-nodes transitions)))]])

(defprotocol Scenario
  (start [s] "Start the scenario")
  (set-node [s node] "Moves to the current node of the conversation")
  (current-node [s] "Get current node"))

(defn transition-fn
  "Transform a function declaration into a transition function. A transition
  function calls the original function handler, and then transitions the
  scenario to the :transition-to node from f

  scenario - scenario that will be transitioned
  tool - transition tool declaration. See `schema/LLMTransitionToolDefinition`
  "
  [scenario tool]
  (let [fndef (:function tool)
        handler (:handler fndef)
        next-node (:transition-to fndef)
        cb #(set-node scenario next-node)]
    (cond-> tool
      true (update-in [:function] dissoc :transition-to)
      true (assoc-in [:function :transition-cb] cb)
      (nil? handler) (assoc-in [:function :handler] (fn [_] {:status :success})))))

(defn scenario-manager
  [{:keys [scenario-config flow flow-in-coord]}]
  (when-let [errors (me/humanize (m/explain ScenarioConfig scenario-config))]
    (throw (ex-info "Invalid scenario config" {:errors errors})))

  (let [current-node (atom nil)
        nodes (:nodes scenario-config)
        initialized? (atom false)
        tts-action? #(contains? #{:tts-say "tts-say"} (:type %))
        end-action? #(contains? #{:end-conversation "end-conversation"} (:type %))
        handle-action #(cond
                         (tts-action? %) (flow/inject flow flow-in-coord [(frame/speak-frame (:text %))])
                         (end-action? %) (flow/stop flow)
                         :else ((:handler %)))]
    (reify Scenario
      (current-node [_] @current-node)
      (set-node [this node-id]
        (assert (get-in scenario-config [:nodes node-id]) (str "Invalid node: " node-id))
        (t/log! :info ["SCENARIO" "NEW NODE" node-id])
        (let [node (get nodes node-id)
              tools (mapv (partial transition-fn this) (:functions node))
              append-context (vec (concat (:role-messages node) (:task-messages node)))
              prev-node-post-actions (get-in nodes [@current-node] :post-actions)]
          (when prev-node-post-actions
            (doseq [a (:post-actions node)] (handle-action a)))
          (reset! current-node node-id)
          (try
            (t/log! "Sending new scenario")
            (doseq [a (:pre-actions node)] (handle-action a))
            (flow/inject flow flow-in-coord [(frame/scenario-context-update {:messages append-context
                                                                             :tools tools
                                                                             :properties {:run-llm? (if (boolean? (:run-llm? node)) (:run-llm? node) true)}})])

            (catch Exception e
              (t/log! :error e)))))
      (start [s]
        (when-not @initialized?
          (reset! initialized? true)
          (set-node s (:initial-node scenario-config)))))))

(defn scenario-in-process
  "Process that acts as a input for the scenario manager into the flow. This
  process will direct specific frames to specific outs. Example: speak-frame
  will be directed to :speak-out channel (should be connected to a text to
  speech process)"
  ([] {:ins {:scenario-in "Channel on which the scenario will put frames."}
       :outs {:speak-out "Channel where speak-frames will be put. Should be connected to text to speech process"
              :context-out "Channel where context frames will be put"}})
  ([_] nil)
  ([_ _ frame]
   (cond
     (frame/speak-frame? frame) [nil {:speak-out [frame]}]
     :else [nil {:context-out [frame]}])))

(comment
  (scenario-manager
    {:flow (flow/create-flow {:procs {}
                              :conns []})
     :scenario
     {:initial-node :start
      :nodes
      {:start
       {:role-messages [{:role :system
                         :content "You are a restaurant reservation assistant for La Maison, an upscale French restaurant. You must ALWAYS use one of the available functions to progress the conversation. This is a phone conversations and your responses will be converted to audio. Avoid outputting special characters and emojis. Be casual and friendly."}]
        :task-messages [{:role :system
                         :content "Warmly greet the customer and ask how many people are in their party."}]
        :functions [{:type :function
                     :function
                     {:name "record_party_size"
                      :handler (fn [{:keys [size]}] size)
                      :description "Record the number of people in the party"
                      :parameters
                      {:type :object
                       :properties
                       {:size {:type :integer
                               :minimum 1
                               :maximum 12}}
                       :required [:size]}
                      :transition-to :get-time}}]}
       :get-time
       {:task-messages [{:role :system
                         :content "Ask what time they'd like to dine. Restaurant is open 5 PM to 10 PM. After they provide a time, confirm it's within operating hours before recording. Use 24-hour format for internal recording (e.g., 17:00 for 5 PM)."}]
        :functions [{:type :function
                     :function {:name "record_time"
                                :handler (fn [{:keys [time]}] time)
                                :description "Record the requested time"
                                :parameters {:type :object
                                             :properties {:time {:type :string
                                                                 :pattern "^(17|18|19|20|21|22):([0-5][0-9])$"
                                                                 :description "Reservation time in 24-hour format (17:00-22:00)"}}
                                             :required [:time]}
                                :transition_to "confirm"}}]}}}}))

(comment
  (me/humanize (m/explain schema/LLMTransitionToolDefinition {:type :function
                                                              :function
                                                              {:name "record_party_size"
                                                               :handler (fn [] :1)
                                                               :description "Record the number of people in the party"
                                                               :parameters
                                                               {:type :object
                                                                :properties
                                                                {:size {:type :integer
                                                                        :min 1
                                                                        :max 12
                                                                        :description "The people that want to dine"}}
                                                                :required [:size]}
                                                               :transition-to :get-time}})))
