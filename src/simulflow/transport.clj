(ns simulflow.transport
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [simulflow.transport.codecs :refer [make-twilio-codec]]
   [simulflow.transport.in :as in]
   [simulflow.transport.out :as out]
   [simulflow.utils.audio :as audio]
   [simulflow.utils.core :as u :refer [defaliases]]
   [simulflow.vad.core :as vad]
   [taoensso.telemere :as t]
   [uncomplicate.clojure-sound.core :as sound]
   [uncomplicate.clojure-sound.sampled :as sampled]
   [uncomplicate.commons.core :refer [close!]])
  (:import
   (java.util Arrays)))

(defn async-in-transform
  [state _ input]
  (if (bytes? input)
    [state {:out [(frame/audio-input-raw input)]}]
    [state]))

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
             (audio/audio-chunk-size {:sample-rate sample-rate
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

                     :init (fn [{:transport/keys [in-ch]}]
                             {::flow/in-ports {:in in-ch}})

                     :transform async-in-transform})))

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
        line (audio/open-line! :target audio-format)
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

;; Backward compatibility
(defaliases
  {:src out/realtime-out-describe}
  {:src out/realtime-out-init!}
  {:src out/realtime-out-transition}
  {:src out/realtime-out-transform}
  {:src out/realtime-out-fn}
  {:src out/realtime-out-processor}
  {:src out/realtime-speakers-out-describe}
  {:src out/realtime-speakers-out-init!}
  {:alias realtime-speakers-out-transition :src out/realtime-out-transition}
  {:alias realtime-speakers-out-transform :src out/realtime-out-transform}
  {:src out/realtime-speakers-out-fn}
  {:src out/realtime-speakers-out-processor}
  {:src in/twilio-transport-in}
  {:src in/twilio-transport-in-describe}
  {:src in/twilio-transport-in-init!}
  {:src in/twilio-transport-in-transform}
  {:src in/twilio-transport-in-fn})
