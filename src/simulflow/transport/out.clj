(ns simulflow.transport.out
  (:require
   [clojure.core.async :as a :refer [<!! >!! chan timeout]]
   [clojure.core.async.flow :as flow]
   [malli.util :as mu]
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

(def BaseOutConfig
  [:map
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

(def RealtimeOutConfig
  (mu/merge BaseOutConfig
            [:map [:audio.out/chan
                   {:description "Core async channel to put audio data. The data is raw byte array or serialzed if a serializer is provided"}
                   schema/CoreAsyncChannel]]))

(def realtime-speakers-out-describe
  {:ins {:in "Channel for audio output frames"
         :sys-in "Channel for system messages"}
   :outs {:out "Channel for non-system frames"
          :sys-out "Channel for bot speech status frames (system frames)"}
   :params (schema/->describe-parameters BaseOutConfig)})

(def realtime-out-describe
  {:ins {:in "Channel for audio output frames"
         :sys-in "Channel for system messages"}
   :outs {:out "Channel for non-system frames"
          :sys-out "Channel for bot speech status frames (system frames)"}
   :params (schema/->describe-parameters RealtimeOutConfig)})

;; Helper functions for DRY init implementations

(defn- setup-transport-channels
  "Sets up the common channel infrastructure for transport-out processors"
  []
  {:timer-in-ch (chan 1024)
   :timer-out-ch (chan 1024)
   :audio-write-ch (chan 1024)
   :command-ch (chan 1024)})

(defn- start-timer-process!
  "Starts the timer process that sends periodic ticks"
  [timer-out-ch]
  (vthread-loop []
    (let [check-interval 1000] ; Check every second for speech timeout
      (<!! (timeout check-interval))
      (>!! timer-out-ch {:timer/tick true :timer/timestamp (u/mono-time)})
      (recur))))

(defn- handle-command
  "Handles command channel messages (drain-queue, etc.)"
  [command audio-write-ch]
  (case (:command/kind command)
    :command/drain-queue (do
                           (async/drain-channel! audio-write-ch)
                           (t/log! {:level :debug :id :transport-out} "Drained audio queue"))
    nil)) ; Unknown command

(defn- handle-timing
  "Common timing logic for audio commands"
  [command]
  (let [current-time (u/mono-time)
        delay-until (:delay-until command 0)
        wait-time (max 0 (- delay-until current-time))]
    (when (pos? wait-time)
      (<!! (timeout wait-time)))))

(defn- get-or-create-line!
  "Gets existing audio line or creates new one for speakers"
  [audio-line-atom sample-rate]
  (or @audio-line-atom
      (let [new-line (open-line! :source (sampled/audio-format sample-rate 16))]
        (reset! audio-line-atom new-line)
        new-line)))

(defn- speakers-writer-factory
  "Creates a writer function for speakers transport"
  [_]
  (let [audio-line-atom (atom nil)]
    {:audio-line-atom audio-line-atom
     :audio-writer (fn [command]
                     (when (= (:command/kind command) :command/write-audio)
                       (handle-timing command)
                       (let [sample-rate (:sample-rate command 16000)
                             line (get-or-create-line! audio-line-atom sample-rate)]
                         (sound/write! (:data command) line 0))))}))

(defn- channel-writer-factory
  "Creates a writer function for channel-based transport"
  [params]
  (let [output-chan (:audio.out/chan params)]
    {:audio-writer (fn [command]
                     (when (= (:command/kind command) :command/write-audio)
                       (handle-timing command)
                       (t/log! {:data {:command :write-audio
                                       :delay-until (:delay-until command 0)
                                       :current-time (u/mono-time)
                                       :wait-time (max 0 (- (:delay-until command 0) (u/mono-time)))}
                                :level :debug
                                :sample 0.05
                                :id :realtime-out})
                       (>!! output-chan (:data command))))}))

(defn- base-transport-out-init!
  "Base initialization function for transport-out processors.
   Takes writer-factory function, with params last for partial application."
  [writer-factory schema params]
  (let [parsed-params (schema/parse-with-defaults schema params)
        {:audio.out/keys [duration-ms sending-interval]
         :activity-detection/keys [silence-threshold-ms]} parsed-params
        sending-interval (or sending-interval duration-ms)
        silence-threshold (or silence-threshold-ms (* 8 duration-ms))
        channels (setup-transport-channels)
        {:keys [timer-in-ch timer-out-ch audio-write-ch command-ch]} channels
        {:keys [audio-writer audio-line-atom]} (writer-factory params)]
    (start-timer-process! timer-out-ch)
    ;; Audio writer process with priority-based command handling
    (vthread-loop []
      (let [[val port] (a/alts!! [command-ch audio-write-ch] :priority true)]
        (when val
          (cond
            (= port command-ch)
            (handle-command val audio-write-ch)
            (= port audio-write-ch)
            (audio-writer val))
          (recur))))
    (merge parsed-params
           {::speaking? false
            ::last-send-time 0
            :audio.out/sending-interval sending-interval
            :activity-detection/silence-threshold-ms silence-threshold
            ::flow/in-ports {::timer-out timer-out-ch}
            ::flow/out-ports {::timer-in timer-in-ch
                              ::command command-ch
                              ::audio-write audio-write-ch}}
           (when audio-line-atom {::audio-line-atom audio-line-atom}))))

(def realtime-speakers-out-init!
  (partial base-transport-out-init! speakers-writer-factory BaseOutConfig))

(def realtime-out-init!
  (partial base-transport-out-init! channel-writer-factory RealtimeOutConfig))

(defn realtime-out-transition
  [{::flow/keys [in-ports out-ports] :as state} transition]
  (when (= transition ::flow/stop)
    ;; Close audio line if it exists (for speakers-out)
    (when-let [audio-line-atom (::audio-line-atom state)]
      (when-let [line @audio-line-atom]
        (sound/stop! line)
        (sampled/flush! line)
        (close! line)))
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
    :or {last-send-time 0 sending-interval 20}} frame now]
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
    (do
      (t/log! {:msg "[Interrupted] Draining playback queue" :id :transport-out :level :trace})
      [(assoc state :pipeline/interrupted? true) {::command [{:command/kind :command/drain-queue}]}])

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
