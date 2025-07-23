(ns simulflow.transport
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [simulflow.async :refer [vthread-loop]]
            [simulflow.frame :as frame]
            [simulflow.schema :as schema]
            [simulflow.transport.out :as out]
            [simulflow.transport.protocols :as tp]
            [simulflow.transport.serializers :refer [make-twilio-serializer]]
            [simulflow.utils.audio :as au]
            [simulflow.utils.core :as u :refer [defaliases]]
            [taoensso.telemere :as t]
            [uncomplicate.clojure-sound.core :as sound]
            [uncomplicate.clojure-sound.sampled :as sampled]
            [uncomplicate.commons.core :refer [close!]])
  (:import (java.util Arrays)
           (javax.sound.sampled AudioFormat AudioSystem DataLine$Info)))

(def AsyncOutputProcessorSchema
  [:map
   [:transport/sample-rate schema/SampleRate]
   [:transport/sample-size-bits schema/SampleSizeBits]
   [:transport/channels schema/AudioChannels]
   [:transport/supports-interrupt? :boolean]
   [:transport/audio-chunk-duration :int] ;; duration in ms for each audio chunk. Default 20ms
   [:transport/audio-chunk-size :int]
   [:transport/out-ch schema/CoreAsyncChannel]])

(defn twilio-transport-in-transform
  [{:twilio/keys [handle-event] :as state} _ input]
  (let [data (u/parse-if-json input)
        output (if (fn? handle-event) (handle-event data) nil)
        out-frames (partial merge-with into output)]
    (condp = (:event data)
      "start" [state (if-let [stream-sid (:streamSid data)]
                       (out-frames {:sys-out [(frame/system-config-change
                                               (u/without-nils {:twilio/stream-sid stream-sid
                                                                :twilio/call-sid (get-in data [:start :callSid])
                                                                :transport/serializer (make-twilio-serializer stream-sid)}))]})
                       (out-frames {}))]
      "media"
      [state (out-frames {:out [(frame/audio-input-raw
                                 (u/decode-base64 (get-in data [:media :payload])))]})]

      "close"
      [state (out-frames {:sys-out [(frame/system-stop true)]})]
      [state])))

(defn async-in-transform
  [state _ input]
  (if (bytes? input)
    [state {:out [(frame/audio-input-raw input)]}]
    [state]))

(defn line-supported?
  [^DataLine$Info info]
  (AudioSystem/isLineSupported info))

(defn open-line!
  "Opens the microphone with specified format. Returns the TargetDataLine."
  [line-type ^AudioFormat format]
  (assert (#{:target :source} line-type) "Invalid line type")
  (let [info (sampled/line-info line-type format)
        line (sampled/line info)]
    (when-not (line-supported? info)
      (throw (ex-info "Audio line not supported"
                      {:format format})))
    (sound/open! line format)
    (sound/start! line)
    line))

(defn- frame-buffer-size
  "Get read buffer size based on the sample rate for input"
  [sample-rate]
  (* 2 (/ sample-rate 100)))

(defn process-mic-buffer
  "Process audio buffer read from microphone.
   Returns map with :audio-data and :timestamp, or nil if no valid data."
  [buffer bytes-read]
  (when (pos? bytes-read)
    {:audio-data (Arrays/copyOfRange buffer 0 bytes-read)
     :timestamp (java.util.Date.)}))

(defn mic-resource-config
  "Calculate audio resource configuration."
  [{:keys [sample-rate sample-size-bits channels buffer-size signed endian]
    :or {signed :signed endian :little-endian}}]
  (let [calculated-buffer-size (or buffer-size (frame-buffer-size sample-rate))]
    {:buffer-size calculated-buffer-size
     :audio-format (sampled/audio-format sample-rate sample-size-bits channels signed endian)
     :channel-size 1024}))

;; =============================================================================
;; Processors

(defn split-audio-into-chunks
  "Split audio byte array into chunks of specified size.
   Returns vector of byte arrays, each of chunk-size or smaller for the last chunk."
  [audio-data chunk-size]
  (when (and audio-data chunk-size (pos? chunk-size) (pos? (count audio-data)))
    (loop [audio audio-data
           chunks []]
      (let [audio-size (count audio)
            chunk-actual-size (min chunk-size audio-size)
            chunk (byte-array chunk-actual-size)]
        ;; Copy chunk-size amount of data into next chunk
        (System/arraycopy audio 0 chunk 0 chunk-actual-size)
        (if (> audio-size chunk-actual-size)
          (let [new-audio-size (- audio-size chunk-actual-size)
                remaining-audio (byte-array new-audio-size)]
            (System/arraycopy audio chunk-actual-size remaining-audio 0 new-audio-size)
            (recur remaining-audio (conj chunks chunk)))
          ;; No more chunks to process, return final result
          (conj chunks chunk))))))

(defn audio-splitter-config
  "Calculate and validate audio splitter configuration.
   Returns map with validated :audio.out/chunk-size."
  [{:audio.out/keys [chunk-size sample-rate sample-size-bits channels duration-ms] :as config}]
  (assert (or chunk-size (and sample-rate sample-size-bits channels duration-ms))
          "Either provide :audio.out/chunk-size or sample-rate, sample-size-bits, channels and chunk duration for the size to be computed")
  (assoc config :audio.out/chunk-size
         (or chunk-size
             (au/audio-chunk-size {:sample-rate sample-rate
                                   :sample-size-bits sample-size-bits
                                   :channels channels
                                   :duration-ms duration-ms}))))

(defn audio-splitter-fn
  "Audio splitter processor function with multi-arity support."
  ([] {:ins {:in "Channel for raw audio frames"}
       :outs {:out "Channel for audio frames split by chunk size"}
       :params {:audio.out/chunk-size "The chunk size by which to split each audio frame. Specify either this or the other parameters so that chunk size can be computed"
                :audio.out/sample-rate "Sample rate of the output audio"
                :audio.out/sample-size-bits "Size in bits for each sample"
                :audio.out/channels "Number of channels. 1 or 2 (mono or stereo audio)"
                :audio.out/duration-ms "Duration in ms of each chunk that will be streamed to output"}})
  ([config]
   (audio-splitter-config config))
  ([state _]
   ;; Transition function must return the state
   state)
  ([state _ frame]
   (cond
     (frame/audio-output-raw? frame)
     (let [{:audio.out/keys [chunk-size]} state
           audio-data (:frame/data frame)]
       (if-let [chunks (split-audio-into-chunks audio-data chunk-size)]
         [state {:out (mapv frame/audio-output-raw chunks)}]
         [state]))
     :else [state])))

(def audio-splitter
  "Takes in audio-output-raw frames and splits them up into :audio.out/duration-ms
  chunks. Chunks are split to achieve realtime streaming."
  (flow/process audio-splitter-fn))

(def twilio-transport-in
  "Takes in twilio events and transforms them into audio-input-raw and config
  changes."
  (flow/process
   (flow/map->step {:describe (fn [] {:outs {:sys-out "Channel for system messages that have priority"
                                             :out "Channel on which audio frames are put"
                                             :speak-out "Channel for speak frames. Used when the user joins the conversation"}
                                      :params {:transport/in-ch "Channel from which input comes"
                                               :twilio/handle-event "Optional function to be called when a new twilio event is received. Return a map like {cid [frame1 frame2]} to put new frames on the pipeline"}})

                    :init (fn [{:transport/keys [in-ch] :twilio/keys [handle-event]}]
                            {::flow/in-ports {:twilio-in in-ch}
                             :twilio/handle-event handle-event})

                    :transform twilio-transport-in-transform})))

(def async-transport-in
  "Takes in twilio events and transforms them into audio-input-raw and config
  changes."
  (flow/process
   (flow/map->step {:describe (fn [] {:outs {:out "Channel on which audio frames are put"}
                                      :params {:transport/in-ch "Channel from which input comes. Input should be byte array"}})

                    :transition (fn [{::flow/keys [in-ports out-ports]} transition]
                                  (when (= transition ::flow/stop)
                                    (doseq [port (remove nil? (concat (vals in-ports) (vals out-ports)))]
                                      (a/close! port))))

                    :init (fn [{:transport/keys [in-ch] :twilio/keys [handle-event]}]
                            {::flow/in-ports {:in in-ch}})

                    :transform async-in-transform})))


(defn realtime-out-describe
  []
  {:ins {:in "Channel for audio output frames "
         :sys-in "Channel for system messages"}
   :outs {:out "Channel for bot speech status frames"}
   :params {:transport/out-chan "Channel on which to put buffered serialized audio"
            :audio.out/duration-ms "Duration of each audio chunk. Defaults to 20ms"
            :transport/supports-interrupt? "Whether the processor supports interrupt or not"}})

(defn realtime-out-transition
  [{::flow/keys [in-ports out-ports] :as state} transition]
  (when (= transition ::flow/stop)
    (doseq [port (concat (vals in-ports) (vals out-ports))]
      (a/close! port)))
  state)



(defn mic-transport-in-describe
  []
  {:outs {:out "Channel on which audio frames are put"}
   :params {:audio-in/sample-rate "Sample rate for audio input. Default 16000"
            :audio-in/channels "Channels for audio input. Default 1"
            :audio-in/sample-size-bits "Sample size in bits. Default 16"
            :audio-in/buffer-size "Size of the buffer mic capture buffer"
            :audio-in/signed "If audio in is signed or not. Can be :signed :unsigned true(signed) false (unsigned) "
            :audio-in/endian "Suggests endianess of the audio format. Can be :big-endian, :big, true (big-endian), :little-endian :little false (little endian)"}})

(defn mic-transport-in-init!
  [{:audio-in/keys [sample-rate sample-size-bits channels buffer-size signed endian]
    :or {sample-rate 16000
         channels 1
         sample-size-bits 16
         signed :signed
         endian :little-endian}}]
  (let [{:keys [buffer-size audio-format channel-size]}
        (mic-resource-config {:sample-rate sample-rate
                              :sample-size-bits sample-size-bits
                              :channels channels
                              :buffer-size buffer-size
                              :signed signed
                              :endian? endian})
        line (open-line! :target audio-format)
        mic-in-ch (a/chan channel-size)
        buffer (byte-array buffer-size)
        running? (atom true)
        close #(do
                 (reset! running? false)
                 (a/close! mic-in-ch)
                 (sound/stop! line)
                 (close! line))]
    (vthread-loop []
      (when @running?
        (try
          (let [bytes-read (sound/read! line buffer 0 buffer-size)]
            (when-let [processed-data (and @running? (process-mic-buffer buffer bytes-read))]
              (a/>!! mic-in-ch processed-data)))
          (catch Exception e
            (t/log! {:level :error :id :microphone-transport :error e}
                    "Error reading audio data")
            ;; Brief pause before retrying to prevent tight error loop
            (Thread/sleep 100)))
        (recur)))
    {::flow/in-ports {::mic-in mic-in-ch}
     :audio-in/sample-size-bits sample-size-bits
     :audio-in/sample-rate sample-rate
     :audio-in/channels channels
     ::close close}))

(defn mic-transport-in-transition
  [state transition]
  (when (and (= transition ::flow/stop)
             (fn? (::close state)))
    (t/log! :info "Closing transport in")
    ((::close state)))
  state)

(defn mic-transport-in-transform
  [state _ {:keys [audio-data timestamp]}]
  [state {:out [(frame/audio-input-raw audio-data {:timestamp timestamp})]}])

(defn mic-transport-in-fn
  "Records microphone and sends raw-audio-input frames down the pipeline."
  ([] (mic-transport-in-describe))
  ([params] (mic-transport-in-init! params))
  ([state transition]
   (mic-transport-in-transition state transition))
  ([state in msg]
   (mic-transport-in-transform state in msg)))

(def microphone-transport-in (flow/process mic-transport-in-fn))

(defn process-realtime-out-audio-frame
  "Pure function to process an audio frame and determine state changes.
   Returns [updated-state output-map] for the given state, frame, and current time."
  [state frame serializer current-time]
  (let [should-emit-start? (not (::speaking? state))
        updated-state (-> state
                          (assoc ::speaking? true)
                          (assoc ::last-audio-time current-time)
                          (assoc ::next-send-time (+ current-time (::sending-interval state))))

        ;; Generate events based on state transitions
        events (if should-emit-start?
                 [(frame/bot-speech-start true)]
                 [])

        ;; Generate audio write command
        audio-frame (if serializer
                      (tp/serialize-frame serializer frame)
                      frame)
        audio-command {:command :write-audio
                       :data (:frame/data audio-frame)
                       :delay-until (::next-send-time updated-state)}]

    [updated-state {:out events
                    :audio-write [audio-command]}]))

(def realtime-speakers-out-describe
  {:ins {:in "Channel for audio output frames"
         :sys-in "Channel for system messages"}
   :outs {:out "Channel for bot speech status frames"}
   :params {:audio.out/sample-rate "Sample rate of the output audio"
            :audio.out/sample-size-bits "Size in bits for each sample"
            :audio.out/channels "Number of channels. 1 or 2 (mono or stereo audio)"
            :audio.out/duration-ms "Duration in ms of each chunk that will be streamed to output"}})

(defn realtime-speakers-out-init!
  [{:audio.out/keys [duration-ms sample-rate sample-size-bits channels]
    :or {sample-rate 16000
         channels 1
         sample-size-bits 16}}]
  (let [;; Configuration
        duration (or duration-ms 20)
        sending-interval (/ duration 2)
        silence-threshold (* 4 duration)

        ;; Audio line setup
        line (open-line! :source (sampled/audio-format sample-rate sample-size-bits channels))

        ;; Channels following activity monitor pattern
        timer-in-ch (a/chan 1024)
        timer-out-ch (a/chan 1024)
        audio-write-ch (a/chan 1024)]

    ;; Minimal timer process - just sends timing events (like activity monitor)
    (vthread-loop []
      (let [check-interval 1000] ; Check every second for speech timeout
        (a/<!! (a/timeout check-interval))
        (a/>!! timer-out-ch {:timer/tick true :timer/timestamp (u/mono-time)})
        (recur)))

    ;; Audio writer process - handles only audio I/O side effects
    (vthread-loop []
      (when-let [audio-command (a/<!! audio-write-ch)]
        (when (= (:command audio-command) :write-audio)
          (let [current-time (u/mono-time)
                delay-until (:delay-until audio-command 0)
                wait-time (max 0 (- delay-until current-time))]
            (when (pos? wait-time)
              (a/<!! (a/timeout wait-time)))
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

(defn realtime-speakers-out-transition
  [{::flow/keys [in-ports out-ports] :as state} transition]
  (when (= transition ::flow/stop)
    (when-let [line (::audio-line state)]
      (sound/stop! line)
      (sampled/flush! line)
      (close! line))
    (doseq [port (concat (vals in-ports) (vals out-ports))]
      (a/close! port)))
  state)

(defn realtime-speakers-out-transform
  [{:transport/keys [serializer] :as state} input-port frame]
  (let [current-time (u/mono-time)]
    (cond
      ;; Handle incoming audio frames - core business logic moved here
      (and (= input-port :in)
           (frame/audio-output-raw? frame))
      (process-realtime-out-audio-frame state frame serializer current-time)

      (and (= input-port :timer-out)
           (:timer/tick frame))
      (let [silence-duration (- (:timer/timestamp frame) (::last-audio-time state))
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
      (and (= input-port :in)
           (frame/system-config-change? frame))
      (if-let [new-serializer (:transport/serializer (:frame/data frame))]
        [(assoc state :transport/serializer new-serializer) {}]
        [state {}])

      ;; Handle system input passthrough
      (= input-port :sys-in)
      [state {:out [frame]}]

      ;; Default case
      :else [state {}])))


;; Backward compatibility
(defaliases
  {:src out/realtime-out-describe}
  {:src out/realtime-out-processor}
  {:src out/realtime-out-init!}
  {:src out/realtime-out-transition}
  {:src out/realtime-out-transform}
  {:src out/realtime-out-fn}

  {:src out/realtime-speakers-out-describe}
  {:src out/realtime-speakers-out-init!}
  {:alias realtime-speakers-out-transition :src out/realtime-out-transition}
  {:alias realtime-speakers-out-transform :src out/realtime-out-transform}
  {:src out/realtime-speakers-out-fn}
  {:src out/realtime-speakers-out-processor})
