(ns voice-fn.processors.deepgram
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [hato.websocket :as ws]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.schema :as schema :refer [flex-enum]]
   [voice-fn.utils.core :as u])
  (:import
   (java.nio HeapCharBuffer)))

(def ^:private deepgram-url "wss://api.deepgram.com/v1/listen")

(def deepgram-encoding
  "Mapping from clojure sound encoding to deepgram notation"
  {:pcm-signed :linear16
   :pcm-unsigned :linear16
   :pcm-float :linear16
   :ulaw :mulaw
   :alaw :alaw})

(defn make-websocket-url
  [{:transcription/keys [interim-results? punctuate? model sample-rate utterance-end-ms language  vad-events? smart-format? encoding channels]
    :or {interim-results? false
         punctuate? false
         channels 1}}]
  (u/append-search-params deepgram-url (u/without-nils {:encoding (deepgram-encoding encoding encoding)
                                                        :language language
                                                        :sample_rate sample-rate
                                                        :model model
                                                        :smart_format smart-format?
                                                        :channels channels
                                                        :vad_events vad-events?
                                                        :utterance_end_ms utterance-end-ms
                                                        :interim_results interim-results?
                                                        :punctuate punctuate?})))

(defn transcript
  [m]
  (-> m :channel :alternatives first :transcript))

(defn final-transcript?
  [m]
  (:is_final m))

(defn interim-transcript?
  [m]
  (and (not (final-transcript? m))
       (not= "" (transcript m))))

(defn speech-started-event?
  [m]
  (= (:type m) "SpeechStarted"))

(defn utterance-end-event?
  [m]
  (= (:type m) "UtteranceEnd"))

(def close-connection-payload (u/json-str {:type "CloseStream"}))

(def keep-alive-payload (u/json-str {:type "KeepAlive"}))

(def code-reason
  {1011 :timeout})

(defn deepgram-event->frames
  [event & {:keys [send-interrupt? supports-interrupt?]}]
  (let [trsc (transcript event)]
    (cond
      (speech-started-event? event)
      [(frame/user-speech-start true)]

      (utterance-end-event? event)
      (if supports-interrupt?
        [(frame/user-speech-stop true) (frame/control-interrupt-stop true)]
        [(frame/user-speech-stop true)])

      (final-transcript? event)
      [(frame/transcription trsc)]

      (interim-transcript? event)
      (if (and supports-interrupt? send-interrupt?)
        ;; Deepgram sends a lot of speech start events, many of which are false
        ;; positives.  They have a disrupting effect on the pipeline, so instead
        ;; of sending interrupt-start frames on speech-start events, we send it
        ;; on the first interim transcription event AFTER a speech start - this
        ;; tends to be a better indicator of speech start
        [(frame/transcription-interim trsc) (frame/control-interrupt-start true)]
        [(frame/transcription-interim trsc)]))))

(def DeepgramConfig
  [:and
   [:map
    [:transcription/api-key :string]
    [:transcription/model {:default :nova-2-general}
     (flex-enum (into [:nova-2] (map #(str "nova-2-" %) #{"general" "meeting" "phonecall" "voicemail" "finance" "conversationalai" "video" "medical" "drivethru" "automotive" "atc"})))]
    [:transcription/interim-results? {:default false
                                      :optional true} :boolean]
    [:transcription/channels {:default 1} [:enum 1 2]]
    [:transcription/smart-format? {:default true
                                   :optional true} :boolean]
    [:transcription/profanity-filter? {:default true
                                       :optional true} :boolean]
    [:transcription/supports-interrupt? {:optional true
                                         :default false} :boolean]
    [:transcription/vad-events? {:default false
                                 :optional true} :boolean]
    [:transcription/utterance-end-ms {:default 1000
                                      :optional true} :int]
    [:transcription/sample-rate schema/SampleRate]
    [:transcription/encoding {:default :linear16} (flex-enum [:linear16 :mulaw :alaw :mp3 :opus :flac :aac])]
    [:transcription/language {:default :en} schema/Language]
    [:transcription/punctuate? {:default false} :boolean]]
   ;; if smart-format is true, no need for punctuate
   [:fn {:error/message "When :transcription/utterance-end-ms is provided, :transcription/interim-results? must be true. More details here:
https://developers.deepgram.com/docs/understanding-end-of-speech-detection#using-utteranceend"}
    (fn [{:transcription/keys [utterance-end-ms interim-results?]}]
      (and (int? utterance-end-ms) interim-results?))]
   [:fn {:error/message "When :transcription/smart-format? is true, :transcription/punctuate? must be false. More details here: https://developers.deepgram.com/docs/smart-format#enable-feature"}
    (fn [{:transcription/keys [smart-format? punctuate?]}]
      (not (and smart-format? punctuate?)))]])

(def deepgram-processor
  (flow/process
    {:describe (fn [] {:ins {:sys-in "Channel for system messages that take priority"
                             :in "Channel for audio input frames (from transport-in) "}
                       :outs {:sys-out "Channel for system messages that have priority"
                              :out "Channel on which transcription frames are put"}
                       :params {:transcription/api-key "Api key required for deepgram connection"
                                :transcription/interim-results? "Wether deepgram should send interim transcriptions back"
                                :transcription/punctuate? "If transcriptions are punctuated or not. Not required if transcription/smart-format is true"
                                :transcription/vad-events? "Enable this for deepgram to send speech-start/utterance end events"
                                :transcription/smart-format? "Enable smart format"
                                :transcription/model "Model used for transcription"
                                :transcription/utterance-end-ms "silence time after speech in ms until utterance is considered ended"
                                :transcription/language "Language for speech"
                                :transcription/channels "Number of channels for audio (1 or 2)"
                                :transcription/encoding "Audio encoding of the input audio"
                                :transcription/sample-rate "Sample rate of the input audio"}
                       :workload :io})
     :init (fn [args]
             (let [websocket-url (make-websocket-url args)
                   ws-read-chan (a/chan 1024)
                   ws-write-chan (a/chan 1024)
                   alive? (atom true)
                   conn-config {:headers {"Authorization" (str "Token " (:transcription/api-key args))}
                                :on-open (fn [_]
                                           (t/log! :info "Deepgram websocket connection open"))
                                :on-message (fn [_ws ^HeapCharBuffer data _last?]
                                              (a/put! ws-read-chan (str data)))
                                :on-error (fn [_ e]
                                            (t/log! {:level :error :id :deepgram-transcriptor} ["Error" e]))
                                :on-close (fn [_ws code reason]
                                            (reset! alive? false)
                                            (t/log! {:level :info :id :deepgram-transcriptor} ["Deepgram websocket connection closed" "Code:" code "Reason:" reason]))}

                   _ (t/log! {:level :info :id :deepgram-transcriptor} ["Connecting to transcription websocket" websocket-url])
                   ws-conn @(ws/websocket
                              websocket-url
                              conn-config)

                   write-to-ws #(loop []
                                  (when @alive?
                                    (when-let [msg (a/<!! ws-write-chan)]
                                      (when (and (frame/audio-input-raw? msg) @alive?)
                                        (do
                                          (ws/send! ws-conn (:frame/data msg))
                                          (recur))))))
                   keep-alive #(loop []
                                 (when @alive?
                                   (a/<!! (a/timeout 3000))
                                   (t/log! {:level :trace :id :deepgram} "Sending keep-alive message")
                                   (ws/send! ws-conn keep-alive-payload)
                                   (recur)))]
               ((flow/futurize write-to-ws :exec :io))
               ((flow/futurize keep-alive :exec :io))

               {:websocket/conn ws-conn
                :websocket/alive? alive?
                ::flow/in-ports {:ws-read ws-read-chan}
                ::flow/out-ports {:ws-write ws-write-chan}}))

     ;; Close ws when pipeline stops
     :transition (fn [{:websocket/keys [conn]
                       ::flow/keys [in-ports out-ports] :as state} transition]
                   (when (= transition ::flow/stop)
                     (t/log! {:id :deepgram-transcriptor :level :info} "Closing transcription websocket connection")
                     (reset! (:websocket/alive? state) false)
                     (when conn
                       (ws/send! conn close-connection-payload)
                       (ws/close! conn))
                     (doseq [port (concat (vals in-ports) (vals out-ports))]
                       (a/close! port))
                     state)
                   state)

     :transform (fn [state in-name msg]
                  (if (= in-name :ws-read)
                    (let [m (u/parse-if-json msg)
                          frames (deepgram-event->frames m)]
                      [state {:out frames}])
                    (cond
                      (frame/audio-input-raw? msg) [state {:ws-write [msg]}]
                      :else [state])))}))
