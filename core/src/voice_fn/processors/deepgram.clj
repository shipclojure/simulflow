(ns voice-fn.processors.deepgram
  (:require
   [clojure.core.async :as a]
   [hato.websocket :as ws]
   [malli.core :as m]
   [malli.transform :as mt]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :refer [accepted-frames close-processor! make-processor-config process-frame processor-schema send-frame! supports-interrupt?]]
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

(defn make-deepgram-url
  [{:transcription/keys [interim-results? punctuate? model sample-rate utterance-end-ms language  vad-events? smart-format? encoding channels]
    :or {interim-results? false
         punctuate? false}}]
  (u/append-search-params deepgram-url {:encoding encoding
                                        :language language
                                        :sample_rate sample-rate
                                        :model model
                                        :smart_format smart-format?
                                        :channels channels
                                        :vad_events vad-events?
                                        :utterance_end_ms utterance-end-ms
                                        :interim_results interim-results?
                                        :punctuate punctuate?}))

(defn final-transcript?
  [m]
  (or (:speech_final m) ;; end of speech through endpointing
      (:is_final m)))

(defn- transcript
  [m]
  (-> m :channel :alternatives first :transcript))

(defn speech-started-event?
  [m]
  (= (:type m) "SpeechStarted"))

(defn utterance-end-event?
  [m]
  (= (:type m) "UtteranceEnd"))

(def close-connection-payload (u/json-str {:type "CloseStream"}))

(declare create-connection-config)

(def max-reconnect-attempts 5)

(defn connect-websocket!
  [type pipeline processor-config]
  (let [current-count (get-in @pipeline [type :websocket/reconnect-count] 0)]
    (if (>= current-count max-reconnect-attempts)
      (t/log! :warn "Maximum reconnection attempts reached for Deepgram")
      (do

        (swap! pipeline update-in [type :websocket/reconnect-count] (fnil inc 0))
        (let [websocket-url (make-deepgram-url processor-config)
              conn-config (create-connection-config
                            type
                            pipeline
                            processor-config)
              _ (t/log! :info (str "Attempting to connect to Deepgram (attempt " (inc current-count) "/" max-reconnect-attempts ")" "url: " websocket-url))
              new-conn @(ws/websocket
                          websocket-url
                          conn-config)]
          (swap! pipeline assoc-in [type :websocket/conn] new-conn))))))

(def keep-alive-payload (u/json-str {:type "KeepAlive"}))

(defn create-connection-config
  [type pipeline processor-config]
  {:headers {"Authorization" (str "Token " (:transcription/api-key processor-config))}
   :on-open (fn [ws]
              ;; Send a keepalive message every 3 seconds to maintain websocket connection
              (a/go-loop []
                (a/<! (a/timeout 3000))
                (when (get-in @pipeline [type :websocket/conn])
                  (t/log! {:level :debug :id type} "Sending keep-alive message")
                  (ws/send! ws keep-alive-payload)
                  (recur)))
              (t/log! :info "Deepgram websocket connection open"))
   :on-message (fn [_ws ^HeapCharBuffer data _last?]
                 (let [m (u/parse-if-json (str data))
                       trsc (transcript m)]

                   (cond
                     (final-transcript? m) (do
                                             #_(t/log! {:id type :level :debug} ["Final transcription" trsc])
                                             (send-frame! pipeline (frame/transcription-complete trsc)))
                     (and trsc (not= "" trsc)) (do
                                                 #_(t/log! {:id type :level :debug} "Sending interim result")
                                                 (send-frame! pipeline (send-frame! pipeline (frame/transcription-interim trsc))))
                     (speech-started-event? m) (do
                                                 #_(t/log! {:id type :level :debug} "Sending speech start frame")
                                                 (send-frame! pipeline (frame/user-speech-start true))
                                                 (when (supports-interrupt? @pipeline)
                                                   (send-frame! pipeline (frame/control-interrupt-start true))))
                     (utterance-end-event? m) (do
                                                #_(t/log! {:id type :level :debug} "Sending speech stop frame")
                                                (send-frame! pipeline (frame/user-speech-stop true))
                                                (when (supports-interrupt? @pipeline)
                                                  (send-frame! pipeline (frame/control-interrupt-stop true)))))))
   :on-error (fn [_ e]
               (t/log! {:level :error :id type} ["Error" e]))
   :on-close (fn [_ws code reason]
               (t/log! {:level :info :id type} ["Deepgram websocket connection closed" "Code:" code "Reason:" reason])
               (if (= code 1011) ;; timeout
                 (connect-websocket! type pipeline processor-config)
                 (swap! pipeline update-in [type] dissoc :websocket/conn)))})

(defn- close-websocket-connection!
  [type pipeline]
  (when-let [conn (get-in @pipeline [type :websocket/conn])]
    (ws/send! conn close-connection-payload)
    (ws/close! conn))
  (swap! pipeline update-in [:transcription/deepgram] dissoc :websocket/conn))

(def code-reason
  {1011 :timeout})

(def DeepgramConfig
  [:and
   [:map
    [:transcription/api-key :string]
    [:transcription/model {:default :nova-2-general}
     (flex-enum (into [:nova-2] (map #(str "nova-2-" %) #{"general" "meeting" "phonecall" "voicemail" "finance" "conversationalai" "video" "medical" "drivethru" "automotive" "atc"})))]
    [:transcription/interim-results? {:default true} :boolean]
    [:transcription/channels {:default 1} [:enum 1 2]]
    [:transcription/smart-format? {:default true} :boolean]
    [:transcription/profanity-filter? {:default true} :boolean]
    [:transcription/supports-interrupt? {:optional true
                                         :default false} :boolean]
    [:transcription/vad-events? {:default false} :boolean]
    [:transcription/utterance-end-ms {:default 1000} :int]
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

(defn pipeline->deepgram-config
  [p]
  (cond-> {}
    ;; Map sample rate directly
    (:audio-in/sample-rate p)
    (assoc :transcription/sample-rate (:audio-in/sample-rate p))

    ;; Map encoding with conversion
    (:audio-in/encoding p)
    (assoc :transcription/encoding
           (get deepgram-encoding (:audio-in/encoding p)))

    (:pipeline/language p) (assoc :transcription/language (:pipeline/language p))
    (:pipeline/supports-interrupt? p)
    (assoc :transcription/supports-interrupt? (:pipeline/supports-interrupt? p))))

(defmethod processor-schema :transcription/deepgram
  [_]
  DeepgramConfig)

(defmethod make-processor-config :transcription/deepgram
  [_ pipeline-config processor-config]
  (m/decode DeepgramConfig
            (merge processor-config
                   (pipeline->deepgram-config pipeline-config))
            (mt/default-value-transformer {::mt/add-optional-keys true})))

(defmethod accepted-frames :transcription/deepgram
  [_]
  #{:frame.system/start :frame.system/stop :frame.audio/input-raw})

(defmethod process-frame :transcription/deepgram
  [type pipeline processor frame]
  (let [on-close! (fn []
                    (t/log! :debug "Stopping transcription engine")
                    (close-websocket-connection! type pipeline)
                    (close-processor! pipeline type))]
    (cond
      (frame/system-start? frame)
      (do (t/log! :debug "Starting transcription engine")
          (connect-websocket! type pipeline (:processor/config processor)))
      (frame/system-stop? frame) (on-close!)

      (frame/audio-input-raw? frame)
      (when-let [conn (get-in @pipeline [type :websocket/conn])]
        (ws/send! conn (:data frame))))))
