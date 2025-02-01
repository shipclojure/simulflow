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
   [voice-fn.frame :as frame]
   [voice-fn.schema :as schema]
   [voice-fn.utils.core :as u]))

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
        next-node (:transition-to fndef)
        cb #(set-node scenario next-node)]
    (assoc-in (u/->tool-fn tool) [:function :transition-cb] cb)))

(defn validate-scenario
  [scenario] true)

(defn scenario-manager
  [{:keys [scenario-config flow flow-in-coord]}]
  (let [current-node (atom nil)
        nodes (:nodes scenario-config)]
    (reify Scenario
      (current-node [_] @current-node)
      (set-node [this node-id]
        (assert (get-in scenario-config [:nodes node-id]) (str "Invalid node: " node-id))

        (let [node (get nodes node-id)
              tools (map (partial transition-fn this) (:functions node))
              append-context (concat (:role-messages node) (:task-messages node))]
          (reset! current-node node-id)
          (flow/inject flow flow-in-coord [frame/scenario-context-update {:messages append-context
                                                                          :tools tools}])))
      (start [s]
        (set-node s (:initial-node scenario-config))))))

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
                      :handler (fn [{:keys [size]}] ...)
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
                                :handler (fn [{:keys [time]}] ...)
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
