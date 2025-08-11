(ns simulflow.processors.activity-monitor
  "Process to monitor activity on the call. Used to ping user when no activity is detected."
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]
   [taoensso.telemere :as t]))

(def ActivityMonitorConfigSchema
  [:map
   [::timeout-ms
    {:default 5000
     :optional true
     :description "Timeout in ms before sending inactivity message. Default 5000ms"}
    :int]
   [::end-phrase
    {:default "Goodbye!"
     :optional true
     :description "Message for bot to say in order to end the conversation"}
    :string]
   [::max-pings
    {:default 3
     :optional true
     :description "Maximum number of inactivity pings before ending the conversation"}
    :int]
   [::ping-phrases
    {:default #{"Are you still there?"}
     :optional true
     :description "Collection (set or vector) with messages to send when inactivity is detected."}
    [:or
     [:set :string]
     [:vector :string]]]])

;; When a frame is received
;; 1. If that frame is VAD for either bot or user
;; 1.1 If user speaking frame, set user speaking true and send frame to timer process to reset activity timer
;; 1.2 If user stopped speaking, set user speaking false and send frame to timer process to reset activity timer
;; 2.1 If bot speaking frame, set bot speaking true and send frame to timer process to reset activity timer
;; 2.2 If bot stopped speaking, set bot speaking false and send frame to timer process to reset activity timer
;; 3. If the timeout for activity has passed and no VAD frame came AND nobody is speaking
;; 3.1 Increment the activity ping message count
;; 3.2 If the activity ping message count is bigger or equal to max pings send end conversation message
;; 4.1 If the timeout for activity has passed and no VAD frame came, but the user is speaking, don't send inactivity ping
;; 4.2 If the timeout for activity has passed and no VAD frame came, but the bot is speaking, don't send inactivity ping

(defn speaking?
  [state]
  (or (::bot-speaking? state)
      (::user-speaking? state)))

(defn transform
  [state in msg]
  (if (= :in in)
    (cond
      (frame/user-speech-start? msg)
      [(assoc state ::user-speaking? true) {:timer-process-in [msg]}]

      (frame/user-speech-stop? msg)
      [(assoc state ::user-speaking? false) {:timer-process-in [msg]}]

      (frame/bot-speech-start? msg)
      [(assoc state ::bot-speaking? true) {:timer-process-in [msg]}]

      (frame/bot-speech-stop? msg)
      [(assoc state ::bot-speaking? false) {:timer-process-in [msg]}]

      :else [state])
    (let [ping-count (::ping-count state 0)
          max-pings (::max-pings state 0)
          ping-phrases-raw (::ping-phrases state #{"Are you still there?"})
          ping-phrases (if (seq ping-phrases-raw) ping-phrases-raw #{"Are you still there?"})
          end-phrase (::end-phrase state "Goodbye!")
          now (:now state)]
      (cond
        (and (= in :timer-process-out)
             (::timeout? msg)
             (< (inc ping-count) max-pings)
             (not (speaking? state)))
        [(update-in state [::ping-count] (fnil inc 0)) {:out [(frame/speak-frame (rand-nth (vec ping-phrases)) {:timestamp now})]}]

        (and (= in :timer-process-out)
             (::timeout? msg)
             (= (inc ping-count) max-pings)
             (not (speaking? state)))
        [(assoc state ::ping-count 0) {:out [(frame/speak-frame end-phrase {:timestamp now})]}]

        :else [state]))))

(def describe
  {:ins {:in "Channel for activity events (user-speech-start, bot-speech-start etc.)"
         :sys-in "Channel for system messages"}
   :outs {:out "Channel for inactivity prompts"}
   :params (schema/->describe-parameters ActivityMonitorConfigSchema)})

(defn init! [params]
  (let [{::keys [timeout-ms] :as parsed} (schema/parse-with-defaults ActivityMonitorConfigSchema params)
        timer-in-ch (a/chan 1024)
        timer-out-ch (a/chan 1024)]
    (vthread-loop []
      (let [timeout-ch (a/timeout timeout-ms)
            [v c] (a/alts!! [timer-in-ch timeout-ch])]
        (when (= c timeout-ch)
          (t/log! {:msg "Activity timeout activated!"
                   :data {:timeout-ms timeout-ms :c c :v v}
                   :id :activity-monitor
                   :level :debug})
          (a/>!! timer-out-ch {::timeout? true}))
        (recur)))

    (merge {::flow/out-ports {:timer-process-in timer-in-ch}
            ::flow/in-ports {:timer-process-out timer-out-ch}}
           parsed)))

(defn transition [{::flow/keys [out-ports in-ports] :as state} transition]
  (when (= transition ::flow/stop)
    (doseq [port (concat (vals out-ports) (vals in-ports))]
      (a/close! port)))
  state)

(defn processor-fn
  ([] describe)
  ([params] (init! params))
  ([state trs] (transition state trs))
  ([state in msg] (transform state in msg)))

(def process (flow/process processor-fn))
