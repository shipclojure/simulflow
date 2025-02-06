(ns voice-fn-examples.scenario-example
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [taoensso.telemere :as t]
   [voice-fn-examples.local :as local]
   [voice-fn.scenario-manager :as sm]))

(def scenario-config
  {:initial-node :start
   :nodes
   {:start
    ;; Role messages dictate how the AI should behave. Ideally :role-messages
    ;; should be present on the :initial-node as they persist for the rest of the conversation
    {:role-messages [{:role :system
                      :content "You are a restaurant reservation assistant for La Maison, an upscale French restaurant. You must ALWAYS use one of the available functions to progress the conversation. This is a phone conversations and your responses will be converted to audio. Avoid outputting special characters and emojis. Be casual and friendly."}]
     ;; task-messages signify the current task the AI has. Each node requires a
     ;; task
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
                            :description "The number of people that will dine."
                            :minimum 1
                            :maximum 12}}
                    :required [:size]}
                   ;; transition-to dictates the next node the AI will go to
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
                             :transition-to :confirm}}]}

    :confirm
    {:task-messages [{:role :system
                      :content "Confirm the reservation details and end the conversation. Say back to the client the details of the reservation: \"Ok! So a reservation for X people at Y PM. Is this corret?\" "}]
     :functions [{:type :function
                  :function {:name "end"
                             :description "End the conversation"
                             :parameters {:type :object, :properties {}}
                             :transition-to :end}}]}
    :end {:task-messages [{:role :system, :content "Thank them and end the conversation."}]
          :functions []
          :post-actions [{:type :end-conversation}]}}})

(defn scenario-example
  "A scenario is a predefined, highly structured conversation. LLM performance
  degrades when it has a big complex prompt to enact, so to ensure a consistent
  output use scenarios that transition the LLM into a new scenario node with a clear
  instruction for the current node."
  []
  (let [flow (local/make-local-flow
               {;; Don't add any context because the scenario will handle that
                :llm/context {:messages []
                              :tools []}

                ;; add gateway process for scenario to inject frames
                :extra-procs {:scenario {:proc (flow/step-process #'sm/scenario-in-process)}}
                :extra-conns [[[:scenario :speak-out] [:tts :in]]
                              [[:scenario :context-out] [:context-aggregator :in]]]})

        s (sm/scenario-manager {:flow flow
                                :flow-in-coord [:scenario :scenario-in] ;; scenario-manager will inject frames through this channel
                                :scenario-config scenario-config})]

    {:flow flow
     :scenario s}))

(comment

  ;; create flow & scenario
  (def s (scenario-example))

  ;; Start local ai flow - starts paused
  (let [{:keys [report-chan error-chan]} (flow/start (:flow s))]
    ;; start the scenario by setting initial node
    (sm/start (:scenario s))
    ;; Resume local ai -> you can now speak with the AI
    (flow/resume (:flow s))
    ;; Monitor report & error channels
    (a/go-loop []
      (when-let [[msg c] (a/alts! [report-chan error-chan])]
        (when (map? msg)
          (t/log! {:level :debug :id (if (= c error-chan) :error :report)} msg))
        (recur))))

  ;; Stop the conversation
  (flow/stop (:flow s))

  ,)
