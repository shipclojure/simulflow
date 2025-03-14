(ns voice-fn.processors.silence-monitor
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [voice-fn.frame :as frame]))

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

(def silence-monitor
  (flow/process
    {:describe (fn [] {:ins {:in "Channel for activity events (user-speech-start, bot-speech-start etc.)"
                             :sys-in "Channel for system messages"}
                       :outs {:out "Channel for inactivity prompts"}
                       :params {:inactivity/timeout-ms "Timeout in ms before sending inactivity prompt. Default 5000ms"
                                :inactivity/prompts "Dictionary with messages to send when inactivity is detected. Default 'Are you still there?'"}})

     :init (fn [{:inactivity/keys [timeout-ms prompts end-call-prompts]
                 :or {timeout-ms 5000
                      prompts #{"Are you still there?"}
                      end-call-prompts #{"Goodbye!"}}}]
             (let [input-ch (a/chan 1024)
                   speak-ch (a/chan 1024)
                   speaking? (atom true)
                   silence-message-count (atom 0)
                   silence-detection-loop #(loop []
                                             (when-let [[frame c] (a/alts!! [(a/timeout timeout-ms) input-ch])]
                                               (if (and (= c input-ch)
                                                        (voice-activity-frame? frame))
                                                 (do
                                                   (when (user-msg? frame) (reset! silence-message-count 0))
                                                   (when (speech-start? frame) (reset! speaking? true))
                                                   (when (speech-stop? frame) (reset! speaking? false))
                                                   (recur))
                                                 (do
                                                   (when-not @speaking?
                                                     (swap! silence-message-count inc)
                                                     ;; End the call if we prompted for activity 3 times
                                                     (if  (>= @silence-message-count  3)
                                                       (a/>!! speak-ch (frame/speak-frame (rand-nth (vec end-call-prompts))))
                                                       (a/>!! speak-ch (frame/speak-frame (rand-nth (vec prompts))))))
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
