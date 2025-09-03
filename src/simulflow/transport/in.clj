(ns simulflow.transport.in
  (:require
   [clojure.core.async :as a :refer [close!]]
   [clojure.core.async.flow :as flow]
   [malli.util :as mu]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]
   [simulflow.transport.codecs :refer [make-twilio-serializer]]
   [simulflow.utils.audio :as audio]
   [simulflow.utils.core :as u]
   [simulflow.vad.core :as vad]
   [simulflow.vad.factory :as vad-factory]
   [taoensso.telemere :as t]
   [uncomplicate.clojure-sound.core :as sound]
   [uncomplicate.clojure-sound.sampled :as sampled])
  (:import
   (java.util Arrays)))

;; Base logic for all input transport

(def CommonTransportInputConfig
  [:map
   [:vad/analyser {:description "An instance of simulflow.vad.core/VADAnalyser protocol or one of the standard simulflow supported VAD processors to be used on new audio."
                   :optional true}
    [:or schema/VADAnalyserProtocol (into [:enum] (keys vad-factory/factory))]]
   [:vad/args {:description "If `:vad/analyser` is a standard simulflow vad (like silero), these args are used as args to the vad factory"
               :optional true} [:map]]
   [:pipeline/supports-interrupt? {:description "Whether the pipeline supports or not interruptions."
                                   :default false
                                   :optional true} :boolean]])

(def BaseTransportInputConfig
  (mu/merge
    CommonTransportInputConfig
    [:map
     [:transport/in-ch
      {:description "Core async channel to take audio data from. The data is raw byte array or serialzed if a deserializer is provided"}
      schema/CoreAsyncChannel]]))

(def base-input-params
  (schema/->describe-parameters CommonTransportInputConfig))

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

(defn init-vad!
  [{:vad/keys [analyser] :as params}]
  (when analyser
    (cond
      (satisfies? vad/VADAnalyzer analyser)
      analyser

      (keyword? analyser)
      (if-let [make-vad (get vad-factory/factory analyser)]
        (if (contains? params :vad/args)
          (make-vad (:vad/args params))
          (make-vad))
        (throw (ex-info "Something went wrong initiating :vad/analyser for transport in"
                        {:params params
                         :cause ::unknown-vad})))

      :else
      (throw (ex-info "Something went wrong initiating :vad/analyser for transport in"
                      {:params params
                       :cause ::unknown-vad})))))

(defn base-transport-in-init!
  [schema params]
  (let [{:transport/keys [in-ch] :as parsed-params} (schema/parse-with-defaults schema params)
        vad-analyser (init-vad! parsed-params)]
    (into parsed-params {::flow/in-ports {::in in-ch}
                         :vad/analyser vad-analyser})))

(defn base-transport-in-transition!
  [{::flow/keys [in-ports out-ports] :as state} transition]
  (when (= transition ::flow/stop)
    (doseq [port (remove nil? (concat (vals in-ports) (vals out-ports)))]
      (a/close! port))
    (when-let [close-fn (::close state)]
      (when (fn? close-fn)
        (t/log! {:level :info
                 :id :transport-in} "Closing input")
        (close-fn)))
    (when-let [analyser (:vad/analyser state)]
      (t/log! {:level :debug :id :transport-in :msg "Cleaning up vad analyser"})
      (vad/cleanup analyser)))
  state)

(defn base-input-transport-transform
  "Base input transport logic that is used by most transport input processors.
  Assumes audio-input-raw frames that come in are 16kHz PCM mono. Conversion to
  this format should be done beforehand."
  [{:keys [pipeline/supports-interrupt? ::muted?] :as state} _ msg]
  (t/log! {:id :transport-in
           :msg "Processing frame"
           :data (:frame/type msg)
           :level :debug
           :sample 0.01})
  (cond
    (frame/mute-input-start? msg)
    [(assoc state ::muted? true)]

    (frame/mute-input-stop? msg)
    [(assoc state ::muted? false)]

    (and (frame/audio-input-raw? msg)
         (not muted?))
    (if-let [analyser (:vad/analyser state)]
      (let [vad-state (vad/analyze-audio analyser (:frame/data msg))
            prev-vad-state (:vad/state state :vad.state/quiet)
            new-state (assoc state :vad/state vad-state)]
        (log-vad-state! prev-vad-state vad-state)
        (if (and (not (vad/transition? vad-state))
                 (not= vad-state prev-vad-state))
          (if (= vad-state :vad.state/speaking)
            [new-state (frame/send msg (frame/vad-user-speech-start true)
                                   (frame/user-speech-start true)
                                   (when supports-interrupt? (frame/control-interrupt-start true)))]
            [new-state (frame/send msg (frame/vad-user-speech-stop true)
                                   (frame/user-speech-stop true)
                                   (when supports-interrupt? (frame/control-interrupt-stop true)))])
          [new-state (frame/send msg)]))
      [state (frame/send msg)])

    (frame/bot-interrupt? msg)
    (if supports-interrupt?
      [state (frame/send (frame/control-interrupt-start true))]
      [state])

    :else
    [state]))

;; Twilio transport in

(def TwilioTransportInConfig
  (mu/merge
    BaseTransportInputConfig
    [:map
     [:twilio/handle-event
      {:description "[DEPRECATED] Optional function to be called when a new twilio event is received. Return a map like {cid [frame1 frame2]} to put new frames on the pipeline"
       :optional true}
      [:=> [:cat :map] :map]]
     [:serializer/convert-audio?
      {:description "If the serializer that is created should convert audio to 8kHz ULAW or not."
       :optional true
       :default false} :boolean]
     [:transport/send-twilio-serializer?
      {:description "Whether to send a `::frame/system-config-change` with a `twilio-frame-serializer` when a twilio start frame is received. Default true"
       :optional true
       :default true} :boolean]]))

(def twilio-transport-in-describe
  {:outs base-transport-outs
   :params (schema/->describe-parameters TwilioTransportInConfig)})

(def twilio-transport-in-init! (partial base-transport-in-init! TwilioTransportInConfig))

(defn twilio-transport-in-transform
  [{:twilio/keys [handle-event]
    :transport/keys [send-twilio-serializer?]
    :or {send-twilio-serializer? true}
    :as state} in input]
  (if (= in ::in)
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
                         (out-frames (frame/send (frame/system-config-change
                                                   (u/without-nils {:twilio/stream-sid stream-sid
                                                                    :twilio/call-sid (get-in data [:start :callSid])
                                                                    :transport/serializer (when send-twilio-serializer?
                                                                                            (make-twilio-serializer
                                                                                              stream-sid
                                                                                              :convert-audio? (:serializer/convert-audio? state false)))}))))
                         (out-frames {}))]
        "media"
        (let [audio-frame (frame/audio-input-raw (-> data
                                                     :media
                                                     :payload
                                                     u/decode-base64
                                                     audio/ulaw8k->pcm16k))]
          (base-input-transport-transform state in audio-frame))

        "close"
        [state (out-frames (frame/send (frame/system-stop true)))]
        [state]))
    (base-input-transport-transform state in input)))

(defn twilio-transport-in-fn
  ([] twilio-transport-in-describe)
  ([params] (twilio-transport-in-init! params))
  ([state trs] (base-transport-in-transition! state trs))
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

(def mic-transport-in-describe
  {:outs base-transport-outs
   :params base-input-params})

(defn mic-transport-in-init!
  [params]
  (let [parsed-params (schema/parse-with-defaults CommonTransportInputConfig params)
        vad-analyser (init-vad! parsed-params)
        {:keys [buffer-size audio-format channel-size]} mic-resource-config
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
    (into parsed-params
          {::flow/in-ports {::in mic-in-ch}
           :vad/analyser vad-analyser
           ::close close})))

(defn mic-transport-in-transform
  [state in {:keys [audio-data timestamp]}]
  (base-input-transport-transform state in (frame/audio-input-raw audio-data {:timestamp timestamp})))

(defn mic-transport-in-fn
  "Records microphone and sends raw-audio-input frames down the pipeline."
  ([] mic-transport-in-describe)
  ([params] (mic-transport-in-init! params))
  ([state transition]
   (base-transport-in-transition! state transition))
  ([state in msg]
   (mic-transport-in-transform state in msg)))

(def microphone-transport-in (flow/process mic-transport-in-fn))

;; Async transport in - Feed audio input frames through a core.async channel

(def async-transport-in-describe
  {:outs base-transport-outs
   :params (into base-input-params {:transport/in-ch "Channel from which input comes. Input should be byte array"})})

(def async-transport-in-init! (partial base-transport-in-init! BaseTransportInputConfig))

(defn async-transport-in-fn
  ([] async-transport-in-describe)
  ([state] (async-transport-in-init! state))
  ([state transition] (base-transport-in-transition! state transition))
  ([state in msg] (base-input-transport-transform state in msg)))

(def async-transport-in-process (flow/process async-transport-in-fn))
