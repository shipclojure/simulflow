(ns voice-fn.experiments.flow
  (:require
   [clojure.core.async.flow :as flow]
   [hato.websocket :as ws]
   [voice-fn.frame :as frame]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.transport.serializers :refer [make-twilio-serializer]]
   [voice-fn.utils.core :as u]))

(def ^:private deepgram-url "wss://api.deepgram.com/v1/listen")

(def deepgram-encoding
  "Mapping from clojure sound encoding to deepgram notation"
  {:pcm-signed :linear16
   :pcm-unsigned :linear16
   :pcm-float :linear16
   :ulaw :mulaw
   :alaw :alaw})

(defn make-deepgram-url
  [{:transcription/keys [interim-results? punctuate? model sample-rate utterance-end-ms language  vad-events? smart-format? encoding channels]
    :or {interim-results? false
         punctuate? false}}]
  (u/append-search-params deepgram-url (u/without-nils {:encoding encoding
                                                        :language language
                                                        :sample_rate sample-rate
                                                        :model model
                                                        :smart_format smart-format?
                                                        :channels channels
                                                        :vad_events vad-events?
                                                        :utterance_end_ms utterance-end-ms
                                                        :interim_results interim-results?
                                                        :punctuate punctuate?})))

(def real-gdef
  {:procs
   {:transport-in {:proc (flow/process {:describe (fn [] {:ins {:in "Channel for audio input "}
                                                          :outs {:system "Channel for system messages that have priority"
                                                                 :out "Channel on which audio frames are put"}})

                                        :transform (fn [state _ input]
                                                     (let [data (u/parse-if-json input)]
                                                       (case (:event data)
                                                         "start" (when-let [stream-sid (:streamSid data)]
                                                                   [state {:system [(frame/system-config-change {:twilio/stream-sid stream-sid
                                                                                                                 :transport/serializer (make-twilio-serializer stream-sid)})]}])
                                                         "media"
                                                         [state {:out [(frame/audio-input-raw
                                                                         (u/decode-base64 (get-in data [:media :payload])))]}]

                                                         "close"
                                                         [state {:system [(frame/system-stop true)]}]
                                                         nil)))})}
    :deepgram-transcriptor {:proc (flow/process {:describe (fn [] {:ins {:system "Channel for system messages that take priority"
                                                                         :in "Channel for audio input frames (from transport-in) "}
                                                                   :outs {:system "Channel for system messages that have priority"
                                                                          :out "Channel on which transcription frames are put"}
                                                                   :params {:deepgram/api-key "Api key for deepgram"
                                                                            :processor/supports-interrupt? "Wether this processor should send interrupt start/stop events on the pipeline"}
                                                                   :workload :io})
                                                 :init (fn [args]
                                                         (let [websocket-url (make-deepgram-url {:transcription/api-key (:deepgram/api-key args)
                                                                                                 :transcription/interim-results? true
                                                                                                 :transcription/punctuate? false
                                                                                                 :transcription/vad-events? true
                                                                                                 :transcription/smart-format? true
                                                                                                 :transcription/model :nova-2
                                                                                                 :transcription/utterance-end-ms 1000
                                                                                                 :transcription/language :en
                                                                                                 :transcription/encoding :mulaw
                                                                                                 :transcription/sample-rate 8000})]))
                                                 :transform (fn [{:websocket/keys [conn]} in-name frame]
                                                              (cond
                                                                (frame/audio-input-raw? frame)
                                                                (when conn (ws/send! (:frame/data frame)))))})}}
   :args {:deepgram/api-key (secret [:deepgram :api-key])}})
