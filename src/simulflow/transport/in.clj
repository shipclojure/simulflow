(ns simulflow.transport.in
  (:require
   [clojure.core.async.flow :as flow]
   [simulflow.frame :as frame]
   [simulflow.transport.codecs :refer [make-twilio-codec]]
   [simulflow.utils.audio :as audio]
   [simulflow.utils.core :as u]
   [simulflow.vad.core :as vad]
   [taoensso.telemere :as t]))

(def base-input-params
  {:vad/analyser "An instance of simulflow.vad.core/VADAnalyser protocol to be used on new audio."
   :pipeline/supports-interrupt? "Whether the pipeline supports or not interruptions."})

(defn base-input-transport-transform
  "Base input transport logic that is used by most transport input processors.
  Assumes audio-input-raw frames that come in are 16kHz PCM mono. Conversion to
  this format should be done beforehand."
  [{:keys [pipeline/supports-interrupt?] :as state} _ msg]
  (cond
    (frame/audio-input-raw? msg)
    (if-let [analyser (:vad/analyser state)]
      (let [vad-state (vad/analyze-audio analyser (:frame/data msg))
            prev-vad-state (:vad/state state :vad.state/quiet)
            new-state (assoc state :vad/state vad-state)]
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
                              :id  :twilio-transport-in}
                             "`:twilio/handle-event` is deprecated. Use core.async.flow/inject instead")
                     (handle-event data))
                   nil)
          out-frames (partial merge-with into output)]
      (condp = (:event data)
        "start" [state (if-let [stream-sid (:streamSid data)]
                         (out-frames {:sys-out [(frame/system-config-change
                                                  (u/without-nils {:twilio/stream-sid stream-sid
                                                                   :twilio/call-sid (get-in data [:start :callSid])
                                                                   :transport/serializer (make-twilio-codec stream-sid)}))]})
                         (out-frames {}))]
        "media"
        (let [audio-frame (frame/audio-input-raw (-> data
                                                     :media
                                                     :payload
                                                     u/decode-base64
                                                     audio/ulaw->pcm16k))]
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
  {:outs {:sys-out "Channel for system messages that have priority"
          :out "Channel on which audio frames are put"}
   :params (into base-input-params
                 {:transport/in-ch "Channel from which input comes"
                  :twilio/handle-event "[DEPRECATED] Optional function to be called when a new twilio event is received. Return a map like {cid [frame1 frame2]} to put new frames on the pipeline"})})

(defn twilio-transport-in-fn
  ([] twilio-transport-in-describe)
  ([params] (twilio-transport-in-init! params))
  ([state _] state)
  ([state in msg] (twilio-transport-in-transform state in msg)))

(def twilio-transport-in
  "Takes in twilio events and transforms them into audio-input-raw and config
  changes."
  (flow/process twilio-transport-in-fn))
