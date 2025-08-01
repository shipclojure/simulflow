(ns simulflow.transport.out
  (:require
   [clojure.core.async :as a :refer [<!! >!! chan timeout]]
   [clojure.core.async.flow :as flow]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [simulflow.transport.protocols :as tp]
   [simulflow.utils.audio :refer [open-line!]]
   [simulflow.utils.core :as u]
   [taoensso.telemere :as t]
   [uncomplicate.clojure-sound.core :as sound]
   [uncomplicate.clojure-sound.sampled :as sampled]
   [uncomplicate.commons.core :refer [close!]]))


(def realtime-speakers-out-describe
  {:ins {:in "Channel for audio output frames"
         :sys-in "Channel for system messages"}
   :outs {:out "Channel for bot speech status frames"}
   :params {:audio.out/sample-rate "Sample rate of the output audio"
            :audio.out/sample-size-bits "Size in bits for each sample"
            :audio.out/channels "Number of channels. 1 or 2 (mono or stereo audio)"
            :audio.out/duration-ms "Duration in ms of each chunk that will be streamed to output"
            :audio.out/sending-interval "Sending interval for each audio chunk. Default is half of :audio.out/duration-ms"
            :activity-detection/silence-threshold-ms "Silence detection threshold in milliseconds. Default is 4x duration-ms."}})

(def realtime-out-describe
  {:ins {:in "Channel for audio output frames"
         :sys-in "Channel for system messages"}
   :outs {:out "Channel for bot speech status frames"}
   :params {:audio.out/chan "Core async channel to put audio data. The data is raw byte array or serialzed if a serializer is active"
            :audio.out/duration-ms "Duration in ms of each chunk that will be streamed to output"
            :audio.out/sending-interval "Sending interval for each audio chunk. Default is half of :audio.out/duration-ms"
            :activity-detection/silence-threshold-ms "Silence detection threshold in milliseconds. Default is 4x duration-ms."}})

(defn realtime-speakers-out-init!
  [{:audio.out/keys [duration-ms sample-rate sample-size-bits channels sending-interval]
    :activity-detection/keys [silence-threshold-ms]
    :or {sample-rate 16000
         channels 1
         sample-size-bits 16}}]
  (let [;; Configuration
        duration (or duration-ms 20)
        sending-interval (or sending-interval (/ duration 2))
        silence-threshold (or silence-threshold-ms (* 4 duration))

        ;; Audio line setup
        line (open-line! :source (sampled/audio-format sample-rate sample-size-bits channels))

        ;; Channels following activity monitor pattern
        timer-in-ch (chan 1024)
        timer-out-ch (chan 1024)
        audio-write-ch (chan 1024)]

    ;; Minimal timer process - just sends timing events (like activity monitor)
    (vthread-loop []
      (let [check-interval 1000] ; Check every second for speech timeout
        (<!! (timeout check-interval))
        (>!! timer-out-ch {:timer/tick true :timer/timestamp (u/mono-time)})
        (recur)))

    ;; Audio writer process - handles only audio I/O side effects
    (vthread-loop []
      (when-let [audio-command (<!! audio-write-ch)]

        (when (= (:command audio-command) :write-audio)
          (let [current-time (u/mono-time)
                delay-until (:delay-until audio-command 0)
                wait-time (max 0 (- delay-until current-time))]
            (when (pos? wait-time)
              (<!! (timeout wait-time)))
            (sound/write! (:data audio-command) line 0)))
        (recur)))

    ;; Return state with minimal setup
    {::flow/in-ports {:timer-out timer-out-ch}
     ::flow/out-ports {:timer-in timer-in-ch
                       :audio-write audio-write-ch}
     ;; Initial business logic state (managed in transform)
     ::speaking? false
     ::last-audio-time 0
     ::next-send-time (u/mono-time)
     ::duration-ms duration
     ::sending-interval sending-interval
     ::silence-threshold silence-threshold
     ::audio-line line}))

(defn realtime-out-init!
  [{:audio.out/keys [duration-ms chan sending-interval]
    :activity-detection/keys [silence-threshold-ms]}]
  (let [;; Configuration
        duration (or duration-ms 20)
        sending-interval (or sending-interval (/ duration 2))
        silence-threshold (or silence-threshold-ms (* 4 duration))

        ;; Channels following activity monitor pattern
        timer-in-ch (a/chan 1024)
        timer-out-ch (a/chan 1024)
        audio-write-ch (a/chan 1024)]

    ;; Minimal timer process - just sends timing events (like activity monitor)
    (vthread-loop []
      (let [check-interval 1000] ; Check every second for speech timeout
        (<!! (timeout check-interval))
        (>!! timer-out-ch {:timer/tick true :timer/timestamp (u/mono-time)})
        (recur)))

    ;; Audio writer process - handles only audio I/O side effects
    (vthread-loop []
      (when-let [audio-command (<!! audio-write-ch)]

        (when (= (:command audio-command) :write-audio)
          (let [current-time (u/mono-time)
                delay-until (:delay-until audio-command 0)
                wait-time (max 0 (- delay-until current-time))]
            (t/log! {:data {:command :write-audio
                            :delay-until (:delay-until audio-command 0)
                            :current-time current-time
                            :wait-time wait-time}
                     :level :debug
                     :sample 0.05
                     :id :realtime-out})
            (when (pos? wait-time)
              (<!! (timeout wait-time)))
            (>!! chan (:data audio-command))))
        (recur)))

    ;; Return state with minimal setup
    {::flow/in-ports {:timer-out timer-out-ch}
     ::flow/out-ports {:timer-in timer-in-ch
                       :audio-write audio-write-ch}
     ;; Initial business logic state (managed in transform)
     ::speaking? false
     ::last-send-time 0
     ::duration-ms duration
     ::sending-interval sending-interval
     ::silence-threshold silence-threshold}))

(defn realtime-out-transition
  [{::flow/keys [in-ports out-ports] :as state} transition]
  (when (= transition ::flow/stop)
    (when-let [line (::audio-line state)]
      (sound/stop! line)
      (sampled/flush! line)
      (close! line))
    (doseq [port (concat (vals in-ports) (vals out-ports))]
      (a/close! port)))
  state)

;; now > last-send-time + duration => send now
;; now < last-send-time + duration => send last-send-time + duration
;; keep track of last send time
(defn process-realtime-out-audio-frame
  "Pure function to process an audio frame and determine state changes.
   Returns [updated-state output-map] for the given state, frame, and current time."
  [{:keys [transport/serializer] :as state
    ::keys [last-send-time sending-interval]
    :or {last-send-time 0}} frame now]
  (let [should-emit-start? (not (::speaking? state))
        maybe-next-send (+ last-send-time sending-interval)
        next-send-time (if (>= now maybe-next-send) now maybe-next-send)
        updated-state (-> state
                          (dissoc ::now)
                          (assoc ::speaking? true)
                          (assoc ::last-send-time next-send-time))

        ;; Generate events based on state transitions
        events (if should-emit-start?
                 [(frame/bot-speech-start true)]
                 [])

        ;; Generate audio write command
        audio-frame (if serializer
                      (tp/serialize-frame serializer frame)
                      (:frame/data frame))
        audio-command {:command :write-audio
                       :data audio-frame
                       :delay-until next-send-time}]

    [updated-state {:out events
                    :audio-write [audio-command]}]))

(defn realtime-out-transform
  [{::keys [now] :as state
    :or {now (u/mono-time)}} input-port frame]
  (cond
    ;; Handle incoming audio frames - core business logic moved here
    (and (= input-port :in)
         (frame/audio-output-raw? frame))
    (process-realtime-out-audio-frame state frame now)

    (and (= input-port :timer-out)
         (:timer/tick frame))
    (let [silence-duration (- (:timer/timestamp frame) (::last-send-time state 0))
          should-emit-stop? (and (::speaking? state)
                                 (> silence-duration (::silence-threshold state)))

          updated-state (if should-emit-stop?
                          (assoc state ::speaking? false)
                          state)

          events (if should-emit-stop?
                   [(frame/bot-speech-stop true)]
                   [])]

      [updated-state {:out events}])

    ;; Handle system config changes
    (frame/system-config-change? frame)
    (if-let [new-serializer (:transport/serializer (:frame/data frame))]
      [(assoc state :transport/serializer new-serializer) {}]
      [state {}])

    ;; Handle system input passthrough
    (= input-port :sys-in)
    [state {:out [frame]}]

    ;; Default case
    :else [state {}]))

(defn realtime-out-fn
  "Processor fn that sends audio chunks to output channel in a realtime manner"
  ([] realtime-out-describe)
  ([params] (realtime-out-init! params))
  ([state transition] (realtime-out-transition state transition))
  ([state input-port frame] (realtime-out-transform state input-port frame)))

(def realtime-out-processor
  "Processor that sends audio chunks to output channel in a realtime manner"
  (flow/process realtime-out-fn))

(defn realtime-speakers-out-fn
  "Processor fn that sends audio chunks to output speakers in a realtime manner"
  ([] realtime-speakers-out-describe)
  ([params] (realtime-speakers-out-init! params))
  ([state transition] (realtime-out-transition state transition))
  ([state input-port frame] (realtime-out-transform state input-port frame)))

(def realtime-speakers-out-processor
  "Processor that sends audio chunks to output speakers in a realtime manner"
  (flow/process realtime-speakers-out-fn))
