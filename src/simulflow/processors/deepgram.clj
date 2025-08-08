(ns simulflow.processors.deepgram
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [hato.websocket :as ws]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema :refer [flex-enum]]
   [simulflow.utils.core :as u]
   [taoensso.telemere :as t])
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
  [{:transcription/keys [interim-results? punctuate? model utterance-end-ms language vad-events? smart-format?]
    :or {interim-results? false
         punctuate? false}}]
  (u/append-search-params deepgram-url
                          (u/without-nils {:encoding :linear16
                                           :language language
                                           :sample_rate 16000
                                           :model model
                                           :smart_format smart-format?
                                           :channels 1
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
  (let [trsc (transcript m)]
    (and (not (final-transcript? m))
         (string? trsc)
         (not= "" trsc))))

(defn speech-started-event?
  [m]
  (= (:type m) "SpeechStarted"))

(defn utterance-end-event?
  [m]
  (= (:type m) "UtteranceEnd"))

(def close-connection-payload (u/json-str {:type "CloseStream"}))

(def keep-alive-payload (u/json-str {:type "KeepAlive"}))

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

(def DeepgramConfigSchema
  [:map
   [:transcription/api-key :string]
   [:transcription/model {:default :nova-2-general}
    (flex-enum (into [:nova-2] (map #(str "nova-2-" %) #{"general" "meeting" "phonecall" "voicemail" "finance" "conversationalai" "video" "medical" "drivethru" "automotive" "atc"})))]
   [:transcription/interim-results? {:default false
                                     :optional true} :boolean]
   [:transcription/smart-format? {:default true
                                  :optional true} :boolean]
   [:transcription/profanity-filter? {:default true
                                      :optional true} :boolean]
   [:transcription/supports-interrupt? {:optional true
                                        :default false} :boolean]
   [:transcription/vad-events? {:default false
                                :optional true} :boolean]
   [:transcription/utterance-end-ms {:optional true} :int]
   [:transcription/language {:default :en} schema/Language]
   [:transcription/punctuate? {:default false} :boolean]])

(def DeepgramConfig
  [:and
   DeepgramConfigSchema
   ;; if smart-format is true, no need for punctuate
   [:fn {:error/message "When :transcription/utterance-end-ms is provided, :transcription/interim-results? must be true. More details here:
https://developers.deepgram.com/docs/understanding-end-of-speech-detection#using-utteranceend"}
    (fn [{:transcription/keys [utterance-end-ms interim-results?]}]
      (if (some? utterance-end-ms)
        interim-results?
        true))]
   [:fn {:error/message "When :transcription/smart-format? is true, :transcription/punctuate? must be false. More details here: https://developers.deepgram.com/docs/smart-format#enable-feature"}
    (fn [{:transcription/keys [smart-format? punctuate?]}]
      (not (and smart-format? punctuate?)))]])

(def describe
  {:ins {:sys-in "Channel for system messages that take priority"
         :in "Channel for audio input frames (from transport-in)"}
   :outs {:sys-out "Channel for system messages that have priority"
          :out "Channel on which transcription frames are put"}
   :params (schema/->describe-parameters DeepgramConfigSchema)
   :workload :io})

(defn init!
  [args]
  ;; Validate configuration
  (let [validated-args (schema/parse-with-defaults DeepgramConfig args)
        websocket-url (make-websocket-url validated-args)
        ws-read-chan (a/chan 1024)
        ws-write-chan (a/chan 1024)
        alive? (atom true)
        conn-config {:headers {"Authorization" (str "Token " (:transcription/api-key validated-args))}
                     :on-open (fn [_]
                                (t/log! {:level :info :id :deepgram} "Websocket connection open"))
                     :on-message (fn [_ws ^HeapCharBuffer data _last?]
                                   (a/put! ws-read-chan (str data)))
                     :on-error (fn [_ e]
                                 (t/log! {:level :error :id :deepgram} ["Websocket error" e]))
                     :on-close (fn [_ws code reason]
                                 (reset! alive? false)
                                 (t/log! {:level :info :id :deepgram} ["Websocket connection closed" "Code:" code "Reason:" reason]))}

        _ (t/log! {:level :info :id :deepgram} ["Connecting to websocket" websocket-url])
        ws-conn @(ws/websocket websocket-url conn-config)]

    ;; Audio message processing loop
    (vthread-loop []
      (when @alive?
        (when-let [msg (a/<!! ws-write-chan)]
          (when (and (frame/audio-input-raw? msg) @alive?)
            (ws/send! ws-conn (:frame/data msg))
            (recur)))))

    ;; Keep-alive loop
    (vthread-loop []
      (when @alive?
        (a/<!! (a/timeout 3000))
        (t/log! {:level :trace :id :deepgram} "Sending keep-alive message")
        (ws/send! ws-conn keep-alive-payload)
        (recur)))

    {:websocket/conn ws-conn
     :websocket/alive? alive?
     ::flow/in-ports {:ws-read ws-read-chan}
     ::flow/out-ports {:ws-write ws-write-chan}}))

(defn transition
  [{:websocket/keys [conn]
    ::flow/keys [in-ports out-ports] :as state} transition]
  (when (= transition ::flow/stop)
    (t/log! {:id :deepgram :level :info} "Closing websocket connection")
    (when (:websocket/alive? state)
      (reset! (:websocket/alive? state) false))
    (when conn
      (ws/send! conn close-connection-payload)
      (ws/close! conn))
    (doseq [port (concat (vals in-ports) (vals out-ports))]
      (when port
        (a/close! port))))
  state)

(defn transform
  [state in-name msg]
  (if (= in-name :ws-read)
    (let [m (u/parse-if-json msg)
          frames (deepgram-event->frames m)]
      [state {:out frames}])
    (cond
      (frame/audio-input-raw? msg) [state {:ws-write [msg]}]
      :else [state])))

(defn processor-fn
  ([] describe)
  ([params] (init! params))
  ([state trs] (transition state trs))
  ([state in-name msg] (transform state in-name msg)))

(def deepgram-processor (flow/process processor-fn))
