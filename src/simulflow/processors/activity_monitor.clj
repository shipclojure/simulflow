(ns simulflow.processors.activity-monitor
  "Process to monitor activity on the call. "
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]))

(defn speech-start?
  [frame]
  (or (frame/user-speech-start? frame)
      (frame/bot-speech-start? frame)))

(defn speech-stop?
  [frame]
  (or
    (frame/user-speech-stop? frame)
    (frame/bot-speech-stop? frame)))

(defn user-msg?
  [frame]
  (or
    (frame/user-speech-stop? frame)
    (frame/user-speech-start? frame)))

(defn voice-activity-frame?
  [frame]
  (or (frame/user-speech-start? frame)
      (frame/user-speech-stop? frame)
      (frame/bot-speech-start? frame)
      (frame/bot-speech-stop? frame)))

(def ActivityMonitorConfigSchema
  [:map
   [:inactivity/timeout-ms
    {:default 5000
     :optional true
     :description "Timeout in ms before sending inactivity message. Default 5000ms"}
    :int]
   [:inactivity/end-call-phrase
    {:default "Goodbye!"
     :optional true
     :description "Message for bot to say in order to end the call"}
    :string]
   [:inactivity/max-pings-before-end
    {:default 3
     :optional true
     :description "Maximum number of inactivity pings before ending the call"}
    :int]
   [:inactivity/ping-phrases
    {:default #{"Are you still there?"}
     :optional true
     :description "Collection (set or vector) with messages to send when inactivity is detected."}
    [:or
     [:set :string]
     [:vector :string]]]])

(def activity-monitor
  (flow/process
    (flow/map->step
      {:describe (fn [] {:ins {:in "Channel for activity events (user-speech-start, bot-speech-start etc.)"
                               :sys-in "Channel for system messages"}
                         :outs {:out "Channel for inactivity prompts"}
                         :params (schema/->flow-describe-parameters ActivityMonitorConfigSchema)})

       :init (fn [params]
               (let [{:inactivity/keys [timeout-ms end-call-phrase ping-phrases max-pings-before-end]}
                     (schema/parse-with-defaults ActivityMonitorConfigSchema params)
                     input-ch (a/chan 1024)
                     speak-ch (a/chan 1024)
                     speaking? (atom true)
                     silence-message-count (atom 0)]
                 (vthread-loop []
                   (when-let [[frame c] (a/alts!! [(a/timeout timeout-ms) input-ch])]
                     (if (and (= c input-ch)
                              (voice-activity-frame? frame))
                       (do
                         (when (user-msg? frame) (reset! silence-message-count 0))
                         (when (speech-start? frame) (reset! speaking? true))
                         (when (speech-stop? frame) (reset! speaking? false))
                         (recur))
                       (when-not @speaking?
                         (swap! silence-message-count inc)
                         ;; End the call if we prompted for activity 3 times
                         (if  (>= @silence-message-count  max-pings-before-end)
                           (a/>!! speak-ch (frame/speak-frame end-call-phrase))
                           (a/>!! speak-ch (frame/speak-frame (rand-nth (vec ping-phrases)))))))))

                 {::flow/out-ports {:input input-ch}
                  ::flow/in-ports {:speak-out speak-ch}}))
       :transition (fn [{::flow/keys [out-ports]} transition]
                     (when (= transition ::flow/stop)
                       (doseq [port (vals out-ports)]
                         (a/close! port))))
       :transform (fn [state in msg]
                    (if (= in :speak-out)
                      [state {:out [msg]}]
                      (cond
                        (voice-activity-frame? msg)
                        [state {:input [msg]}]
                        :else [state])))})))
