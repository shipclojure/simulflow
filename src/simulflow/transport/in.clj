(ns simulflow.transport.in
  (:require
   [clojure.core.async :as a :refer [close!]]
   [clojure.core.async.flow :as flow]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [simulflow.transport.codecs :refer [make-twilio-serializer]]
   [simulflow.utils.audio :as audio]
   [simulflow.utils.core :as u]
   [simulflow.vad.core :as vad]
   [taoensso.telemere :as t]
   [uncomplicate.clojure-sound.core :as sound]
   [uncomplicate.clojure-sound.sampled :as sampled])
  (:import
   (java.util Arrays)))

;; Base logic for all input transport

(def base-input-params
  {:vad/analyser "An instance of simulflow.vad.core/VADAnalyser protocol to be used on new audio."
   :pipeline/supports-interrupt? "Whether the pipeline supports or not interruptions."})

(def base-transport-outs {:sys-out "Channel for system messages that have priority"
                          :out "Channel on which audio frames are put"})

(defn log-vad-state!
  [prev-vad-state vad-state]
  (when (and prev-vad-state vad-state (not= prev-vad-state vad-state))
    (t/log! {:level :debug
             :id :transport-in
             :msg "Changed vad state"
             :data {:vad/prev-state prev-vad-state
                    :vad/state vad-state}})))

(defn base-input-transport-transform
  "Base input transport logic that is used by most transport input processors.
  Assumes audio-input-raw frames that come in are 16kHz PCM mono. Conversion to
  this format should be done beforehand."
  [{:keys [pipeline/supports-interrupt?] :as state} _ msg]
  (t/log! {:id :transport-in
           :msg "Processing frame"
           :data (:frame/type msg)
           :level :debug
           :sample 0.01})
  (cond
    (frame/audio-input-raw? msg)
    (if-let [analyser (:vad/analyser state)]
      (let [vad-state (vad/analyze-audio analyser (:frame/data msg))
            prev-vad-state (:vad/state state :vad.state/quiet)
            new-state (assoc state :vad/state vad-state)]
        (log-vad-state! prev-vad-state vad-state)
        (if (and (not (vad/transition? vad-state))
                 (not= vad-state prev-vad-state))
          (if (= vad-state :vad.state/speaking)
            (let [system-frames (into [(frame/vad-user-speech-start true)
                                       (frame/user-speech-start true)]
                                      (remove nil? [(when supports-interrupt? (frame/control-interrupt-start true))]))
                  output {:out [msg]
                          :sys-out system-frames}]
              [new-state output])
            (let [system-frames (into [(frame/vad-user-speech-stop true)
                                       (frame/user-speech-stop true)]
                                      (remove nil? [(when supports-interrupt? (frame/control-interrupt-stop true))]))
                  output {:out [msg]
                          :sys-out system-frames}]
              [new-state output]))
          [new-state {:out [msg]}]))
      [state {:out [msg]}])

    (frame/bot-interrupt? msg)
    (if supports-interrupt?
      [state {:sys-out [(frame/control-interrupt-start true)]}]
      [state])

    :else
    [state]))

;; Twilio transport in

(defn twilio-transport-in-transform
  [{:twilio/keys [handle-event] :as state} in input]
  (if (= in ::twilio-in)
    (let [data (u/parse-if-json input)
          output (if (fn? handle-event)
                   (do
                     (t/log! {:level :warn
                              :id :twilio-transport-in}
                             "`:twilio/handle-event` is deprecated. Use core.async.flow/inject instead")
                     (handle-event data))
                   nil)
          out-frames (partial merge-with into output)]
      (condp = (:event data)
        "start" [state (if-let [stream-sid (:streamSid data)]
                         (out-frames {:sys-out [(frame/system-config-change
                                                  (u/without-nils {:twilio/stream-sid stream-sid
                                                                   :twilio/call-sid (get-in data [:start :callSid])
                                                                   :transport/serializer (make-twilio-serializer
                                                                                           stream-sid
                                                                                           :convert-audio? (:serializer/convert-audio? state false))}))]})
                         (out-frames {}))]
        "media"
        (let [audio-frame (frame/audio-input-raw (-> data
                                                     :media
                                                     :payload
                                                     u/decode-base64
                                                     audio/ulaw8k->pcm16k))]
          (base-input-transport-transform state in audio-frame))

        "close"
        [state (out-frames {:sys-out [(frame/system-stop true)]})]
        [state]))
    (base-input-transport-transform state in input)))

(defn twilio-transport-in-init!
  [{:transport/keys [in-ch] :twilio/keys [handle-event] :as state}]
  (into state
        {::flow/in-ports {::twilio-in in-ch}
         :twilio/handle-event handle-event}))

(def twilio-transport-in-describe
  {:outs base-transport-outs
   :params (into base-input-params
                 {:transport/in-ch "Channel from which input comes"
                  :twilio/handle-event "[DEPRECATED] Optional function to be called when a new twilio event is received. Return a map like {cid [frame1 frame2]} to put new frames on the pipeline"
                  :serializer/convert-audio? "If the serializer that is created should convert audio to 8kHz ULAW or not."})})

(defn twilio-transport-in-fn
  ([] twilio-transport-in-describe)
  ([params] (twilio-transport-in-init! params))
  ([state _] state)
  ([state in msg] (twilio-transport-in-transform state in msg)))

(def twilio-transport-in
  "Takes in twilio events and transforms them into audio-input-raw and config
  changes."
  (flow/process twilio-transport-in-fn))

;; Mic transport in

(defn process-mic-buffer
  "Process audio buffer read from microphone.
   Returns map with :audio-data and :timestamp, or nil if no valid data."
  [^bytes buffer ^long bytes-read]
  (when (pos? bytes-read)
    {:audio-data (Arrays/copyOfRange buffer 0 bytes-read)
     :timestamp (java.util.Date.)}))

(def mic-resource-config
  "Calculate audio resource configuration."
  {:buffer-size 1024
   :audio-format (sampled/audio-format 16000 16 1)
   :channel-size 1024})

(defn mic-transport-in-describe
  []
  {:outs base-transport-outs
   :params base-input-params})

(defn mic-transport-in-init!
  [state]
  (let [{:keys [buffer-size audio-format channel-size]} mic-resource-config
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
    (into state
          {::flow/in-ports {::mic-in mic-in-ch}
           ::close close})))

(defn mic-transport-in-transition
  [state transition]
  (when (= transition ::flow/stop)
    (when-let [close-fn (::close state)]
      (when (fn? close-fn)
        (t/log! {:level :info
                 :id :transport-in} "Closing input")
        (close-fn)))
    (when-let [analyser (:vad/analyser state)]
      (t/log! {:level :debug :id :transport-in :msg "Cleaning up vad analyser"})
      (vad/cleanup analyser)))

  state)

(defn mic-transport-in-transform
  [state in {:keys [audio-data timestamp]}]
  (base-input-transport-transform state in (frame/audio-input-raw audio-data {:timestamp timestamp})))

(defn mic-transport-in-fn
  "Records microphone and sends raw-audio-input frames down the pipeline."
  ([] (mic-transport-in-describe))
  ([params] (mic-transport-in-init! params))
  ([state transition]
   (mic-transport-in-transition state transition))
  ([state in msg]
   (mic-transport-in-transform state in msg)))

(def microphone-transport-in (flow/process mic-transport-in-fn))

;; Async transport in - Feed audio input frames through a core.async channel

(def async-transport-in-describe
  {:outs base-transport-outs
   :params (into base-input-params {:transport/in-ch "Channel from which input comes. Input should be byte array"})})

(defn async-transport-in-transition
  [{::flow/keys [in-ports out-ports] :as state} transition]
  (when (= transition ::flow/stop)
    (doseq [port (remove nil? (concat (vals in-ports) (vals out-ports)))]
      (a/close! port))
    (when-let [analyser (:vad/analyser state)]
      (t/log! {:level :debug :id :transport-in :msg "Cleaning up vad analyser"})
      (vad/cleanup analyser))
    state))

(defn async-transport-in-init!
  [{:transport/keys [in-ch] :as state}]
  (into state {::flow/in-ports {:in in-ch}}))

(defn async-transport-in-fn
  ([] async-transport-in-describe)
  ([state] (async-transport-in-init! state))
  ([state transition] (async-transport-in-transition state transition))
  ([state in msg] (base-input-transport-transform state in msg)))

(def async-transport-in-process (flow/process async-transport-in-fn))
