(ns voice-fn.processors.silence-monitor
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [voice-fn.frame :as frame]))

(defn voice-activity-frame?
  [frame]
  (or (frame/user-speech-start? frame)
      (frame/user-speech-stop? frame)
      (frame/bot-speech-start? frame)
      (frame/bot-speech-stop? frame)))

(def silence-monitor
  (flow/process
    {:describe (fn [] {:ins {:in "Channel for activity events (user-speech-start, bot-speech-start etc.)"
                             :sys-in "Channel for system messages"}
                       :outs {:out "Channel for inactivity prompts"}
                       :params {:inactivity/timeout-ms "Timeout in ms before sending inactivity prompt. Default 5000ms"
                                :inactivity/prompts "Dictionary with messages to send when inactivity is detected. Default 'Are you still there?'"}})

     :init (fn [{:inactivity/keys [timeout-ms prompts]
                 :or {timeout-ms 5000
                      prompts #{"Are you still there?"}}}]
             (let [input-ch (a/chan 1024)
                   speak-ch (a/chan 1024)
                   silence-detection-loop #(loop []
                                             (when-let [[frame c] (a/alts!! [(a/timeout timeout-ms) input-ch])]
                                               (if (and (= c input-ch)
                                                        (voice-activity-frame? frame))
                                                 (recur)
                                                 (do (a/>!! speak-ch (frame/speak-frame (rand-nth (vec prompts))))
                                                     (recur)))))]
               ((flow/futurize silence-detection-loop :exec :io))
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
                      :else [state])))}))
