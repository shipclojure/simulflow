(ns simulflow.transport.out
  (:require
   [clojure.core.async :as a :refer [<!! >!! chan timeout]]
   [clojure.core.async.flow :as flow]
   [simulflow.async :as async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]
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
   :outs {:out "Channel for non-system frames"
          :sys-out "Channel for bot speech status frames (system frames)"}
   :params {:audio.out/duration-ms "Duration in ms of each chunk that will be streamed to output"
            :audio.out/sending-interval "Sending interval for each audio chunk. Default is half of :audio.out/duration-ms"
            :activity-detection/silence-threshold-ms "Silence detection threshold in milliseconds. Default is 4x duration-ms."}})

(def RealtimeOutConfig
  [:map
   [:audio.out/chan
    {:description "Core async channel to put audio data. The data is raw byte array or serialzed if a serializer is provided"}
    schema/CoreAsyncChannel]
   [:audio.out/duration-ms
    {:description "Duration in ms of each chunk that will be streamed to output"
     :default 20}
    :int]
   [:audio.out/sending-interval
    {:description "Sending interval for each audio chunk. This is used to send chunks in a realtime manner in order to facilitate interruption. Default is half of :audio.out/duration-ms"}
    :int]
   [:activity-detection/silence-threshold-ms
    {:description "Silence detection threshold in milliseconds. When the silence threshold is reached, the processor emits ::frame/bot-speech-stop frames. Default is 4x :audio.out/duration-ms."
     :optional true}
    :int]
   [:transport/serializer
    {:description "Frame serializer used to serialize frames before sending them on the :audio.out/chan"
     :optional true}
    schema/FrameSerializer]])

(def realtime-out-describe
  {:ins {:in "Channel for audio output frames"
         :sys-in "Channel for system messages"}
   :outs {:out "Channel for non-system frames"
          :sys-out "Channel for bot speech status frames (system frames)"}
   :params (schema/->describe-parameters RealtimeOutConfig)})

(defn realtime-speakers-out-init!
  [{:audio.out/keys [duration-ms sending-interval]
    :activity-detection/keys [silence-threshold-ms]}]
  (let [;; Configuration
        duration (or duration-ms 20)
        sending-interval (or sending-interval (/ duration 2))
        silence-threshold (or silence-threshold-ms (* 4 duration))

        ;; Audio line state - will be opened on first frame
        audio-line-atom (atom nil)

        ;; Channels following activity monitor pattern
        timer-in-ch (chan 1024)
        timer-out-ch (chan 1024)
        audio-write-ch (chan 1024)
        command-ch (chan 1024)]

    ;; Minimal timer process - just sends timing events (like activity monitor)
    (vthread-loop []
      (let [check-interval 1000] ; Check every second for speech timeout
        (<!! (timeout check-interval))
        (>!! timer-out-ch {:timer/tick true :timer/timestamp (u/mono-time)})
        (recur)))

    ;; Audio writer process - handles only audio I/O side effects, lazy line opening, and stopping playback
    (vthread-loop []
      ;; using :priority true to always prefer command-ch in case both audio-write and command chans have value
      (let [[val port] (a/alts!! [command-ch audio-write-ch] :priority true)]
        (when val
          (cond
            (= port command-ch)
            (case (:command/kind val)
              :command/drain-queue (do
                                     (async/drain-channel! audio-write-ch)
                                     (t/log! {:level :debug :id :transport-out} "Drained audio queue"))
              ;; unknown command
              nil)

            (= port audio-write-ch)
            (when (= (:command/kind val) :command/write-audio)
              (let [current-time (u/mono-time)
                    delay-until (:delay-until val 0)
                    wait-time (max 0 (- delay-until current-time))
                    sample-rate (:sample-rate val 16000) ; Fallback to 16000 Hz

                    ;; Open line if not already opened
                    line (or @audio-line-atom
                             (let [new-line (open-line! :source (sampled/audio-format sample-rate 16))]
                               (reset! audio-line-atom new-line)
                               new-line))]
                (when (pos? wait-time)
                  (<!! (timeout wait-time)))

                (sound/write! (:data val) line 0)))
            :else
            nil)
          (recur))))

    ;; Return state with minimal setup
    {::flow/in-ports {::timer-out timer-out-ch}
     ::flow/out-ports {::timer-in timer-in-ch
                       ::command command-ch
                       ::audio-write audio-write-ch}
     ;; Initial business logic state (managed in transform)
     ::speaking? false
     ::next-send-time (u/mono-time)
     :audio.out/duration-ms duration
     :audio.out/sending-interval sending-interval
     :activity-detection/silence-threshold-ms silence-threshold
     ::audio-line-atom audio-line-atom}))

(defn realtime-out-init!
  [params]
  (let [;; Configuration
        parsed-params (schema/parse-with-defaults RealtimeOutConfig params)
        {:audio.out/keys [duration-ms chan sending-interval]
         :activity-detection/keys [silence-threshold-ms]} parsed-params

        duration duration-ms
        sending-interval (or sending-interval duration)
        silence-threshold (or silence-threshold-ms (* 8 duration))

        ;; Channels following activity monitor pattern
        timer-in-ch (a/chan 1024)
        timer-out-ch (a/chan 1024)
        audio-write-ch (a/chan 1024)
        command-ch (a/chan 1024)]

    ;; Minimal timer process - just sends timing events (like activity monitor)
    (vthread-loop []
      (let [check-interval 1000] ; Check every second for speech timeout
        (<!! (timeout check-interval))
        (>!! timer-out-ch {:timer/tick true :timer/timestamp (u/mono-time)})
        (recur)))

    ;; Audio writer process - handles only audio I/O side effects and stopping playback
    (vthread-loop []
      ;; using :priority true to always prefer command-ch in case both audio-write and command chans have value
      (let [[val port] (a/alts!! [command-ch audio-write-ch] :priority true)]
        (when val
          (cond
            (= port command-ch)
            (case (:command/kind val)
              :command/drain-queue (do
                                     (async/drain-channel! audio-write-ch)
                                     (t/log! {:level :debug :id :transport-out} "Drained audio queue"))
              ;; unknown command
              nil)

            (= port audio-write-ch)
            (when (= (:command/kind val) :command/write-audio)
              (let [current-time (u/mono-time)
                    delay-until (:delay-until val 0)
                    wait-time (max 0 (- delay-until current-time))]
                (t/log! {:data {:command :write-audio
                                :delay-until (:delay-until val 0)
                                :current-time current-time
                                :wait-time wait-time}
                         :level :debug
                         :sample 0.05
                         :id :realtime-out})
                (when (pos? wait-time)
                  (<!! (timeout wait-time)))
                (>!! chan (:data val))))
            :else
            nil)
          (recur))))

    ;; Return state with minimal setup
    (into parsed-params
          {::flow/in-ports {::timer-out timer-out-ch}
           ::flow/out-ports {::timer-in timer-in-ch
                             ::command command-ch
                             ::audio-write audio-write-ch}
           ;; Initial business logic state (managed in transform)
           ::speaking? false
           ::last-send-time 0
           :audio.out/sending-interval sending-interval
           :activity-detection/silence-threshold-ms silence-threshold})))

(defn realtime-out-transition
  [{::flow/keys [in-ports out-ports] :as state} transition]
  (when (= transition ::flow/stop)
    ;; Close audio line if it exists (for speakers-out)
    (when-let [audio-line-atom (::audio-line-atom state)]
      (when-let [line @audio-line-atom]
        (sound/stop! line)
        (sampled/flush! line)
        (close! line)))
    ;; Legacy: close direct audio line (for old code)
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
    ::keys [last-send-time]
    :audio.out/keys [sending-interval]
    :or {last-send-time 0}} frame now]
  (let [should-emit-start? (not (::speaking? state))
        maybe-next-send (+ last-send-time sending-interval)
        next-send-time (if (>= now maybe-next-send) now maybe-next-send)

        ;; Extract audio data and sample rate from the frame
        frame-data (:frame/data frame)
        {audio-data :audio sample-rate :sample-rate}
        (if (map? frame-data)
          frame-data ; New format: {:audio bytes :sample-rate hz}
          {:audio frame-data :sample-rate 16000}) ; Fallback

        updated-state (-> state
                          (dissoc ::now)
                          (assoc ::speaking? true)
                          (assoc ::last-send-time next-send-time))

        ;; Generate audio write command with sample rate
        audio-frame (if serializer
                      (tp/serialize-frame serializer frame)
                      audio-data)
        audio-command {:command/kind :command/write-audio
                       :data audio-frame
                       :delay-until next-send-time
                       :sample-rate sample-rate}]

    [updated-state (-> (frame/send (when should-emit-start? (frame/bot-speech-start true)))
                       (assoc ::audio-write [audio-command]))]))

(defn base-realtime-out-transform
  [{::keys [now] :as state
    :or {now (u/mono-time)}} input-port frame]
  (cond
    ;; Handle incoming audio frames - core business logic moved here
    (and (frame/audio-output-raw? frame) (not (:pipeline/interrupted? state)))
    (process-realtime-out-audio-frame state frame now)

    (and (= input-port ::timer-out)
         (:timer/tick frame))
    (let [silence-duration (- (:timer/timestamp frame) (::last-send-time state 0))
          should-emit-stop? (and (::speaking? state)
                                 (> silence-duration (:activity-detection/silence-threshold-ms state)))

          updated-state (if should-emit-stop?
                          (assoc state ::speaking? false)
                          state)]

      [updated-state (frame/send (when should-emit-stop? (frame/bot-speech-stop true)))])

    ;; Handle system config changes
    (frame/system-config-change? frame)
    (if-let [new-serializer (:transport/serializer (:frame/data frame))]
      [(assoc state :transport/serializer new-serializer) {}]
      [state {}])

    (frame/control-interrupt-start? frame)
    [(assoc state :pipeline/interrupted? true) {::command [{:command/kind :command/drain-queue}]}]

    (frame/control-interrupt-stop? frame)
    [(assoc state :pipeline/interrupted? false) {}]

    ;; Default case
    :else [state {}]))

(defn realtime-out-fn
  "Processor fn that sends audio chunks to output channel in a realtime manner"
  ([] realtime-out-describe)
  ([params] (realtime-out-init! params))
  ([state transition] (realtime-out-transition state transition))
  ([state input-port frame] (base-realtime-out-transform state input-port frame)))

(def realtime-out-processor
  "Processor that sends audio chunks to output channel in a realtime manner"
  (flow/process realtime-out-fn))

(defn realtime-speakers-out-fn
  "Processor fn that sends audio chunks to output speakers in a realtime manner"
  ([] realtime-speakers-out-describe)
  ([params] (realtime-speakers-out-init! params))
  ([state transition] (realtime-out-transition state transition))
  ([state input-port frame] (base-realtime-out-transform state input-port frame)))

(def realtime-speakers-out-processor
  "Processor that sends audio chunks to output speakers in a realtime manner"
  (flow/process realtime-speakers-out-fn))

(comment

  (def demo-flow-config {:procs {:speakers {:proc realtime-speakers-out-processor}}})

  (def g (flow/create-flow demo-flow-config))

  (defonce flow-started? (atom false))

  ;; Start local ai flow - starts paused
  (let [{:keys [report-chan error-chan]} (flow/start g)]
    (reset! flow-started? true)
    ;; Resume local ai -> you can now speak with the AI
    (flow/resume g)
    (vthread-loop []
      (when @flow-started?
        (when-let [[msg c] (a/alts!! [report-chan error-chan])]
          (when (map? msg)
            (t/log! (cond-> {:level :debug :id (if (= c error-chan) :error :report)}
                      (= c error-chan) (assoc :error msg)) msg))
          (recur)))))

  (flow/inject g [:speakers :in] [(frame/audio-output-raw {:audio (byte-array (range 200)) :sample-rate 24000})])

  (flow/stop g)

  ,)
