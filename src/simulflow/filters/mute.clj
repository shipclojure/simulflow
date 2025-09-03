(ns simulflow.filters.mute
  "This filter handles muting the user input based on specific strategies:
  - A function call is in progress
  - Bot's first speech (introductions)
  - Bot speech (don't process user speech while bot is speaking)
  "
  (:require
   [clojure.core.async.flow :as flow]
   [clojure.set :as set]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]))

(def MuteStrategy
  [:enum
   ;; Mute user during introduction
   :mute.strategy/first-speech
   ;; Mute user during bot tool calls
   :mute.strategy/tool-call
   ;; Mute user during all bot speech
   :mute.strategy/bot-speech])

(def MuteFilterConfig
  [:map
   [:mute/strategies
    {:description "Collection of strategies used to trigger muting the user input."}
    [:set MuteStrategy]]])

(def describe
  {:ins {:in "Channel for normal frames"
         :sys-in "Channel for system frames"}
   :outs {:sys-out "Channel for mute-start/stop frames"}
   :params (schema/->describe-parameters MuteFilterConfig)})

(defn init
  [params]
  (let [parsed-params (schema/parse-with-defaults MuteFilterConfig params)]
    parsed-params))

(defn transform
  [{:keys [mute/strategies ::muted?] :as state} _ msg]
  (cond
    ;; Function call strategy
    (and (frame/llm-tool-call-request? msg)
         (strategies :mute.strategy/tool-call)
         (not muted?))
    [(assoc state ::muted? true) (frame/send (frame/mute-input-start))]

    (and (frame/llm-tool-call-result? msg)
         (strategies :mute.strategy/tool-call)
         muted?)
    [(assoc state ::muted? false) (frame/send (frame/mute-input-stop))]

    ;; bot speech & first-speech strategies
    (and (frame/bot-speech-start? msg)
         (seq (set/intersection strategies #{:mute.strategy/first-speech :mute.strategy/bot-speech}))
         (not muted?))
    (let [emit-mute-first-speech? (and (strategies :mute.strategy/first-speech)
                                    (not (true? (::first-speech-started? state))))
          emit-mute-bot-speech? (strategies :mute.strategy/bot-speech)
          emit-mute? (or emit-mute-first-speech? emit-mute-bot-speech?)

          ns (cond-> state
               emit-mute-first-speech? (assoc ::first-speech-started? true)
               emit-mute? (assoc ::muted? true))]
      [ns (when emit-mute? (frame/send (frame/mute-input-start)))])

    (and (frame/bot-speech-stop? msg)
         (seq (set/intersection strategies #{:mute.strategy/first-speech :mute.strategy/bot-speech}))
         muted?)
    (let [emit-unmute-first-speech? (and (strategies :mute.strategy/first-speech)
                                      (true? (::first-speech-started? state))
                                      (not (true? (::first-speech-ended? state))))
          emit-unmute-bot-speech? (strategies :mute.strategy/bot-speech)
          emit-unmute? (or emit-unmute-first-speech? emit-unmute-bot-speech?)

          ns (cond-> state
               emit-unmute-first-speech? (assoc ::first-speech-ended? true)
               emit-unmute? (assoc ::muted? false))]
      [ns (when emit-unmute? (frame/send (frame/mute-input-stop)))])

    :else
    [state]))

(defn processor-fn
  ([] describe)
  ([params] (init params))
  ([state _transition] state)
  ([state in msg]
   (transform state in msg)))

(def processor (flow/process processor-fn))
