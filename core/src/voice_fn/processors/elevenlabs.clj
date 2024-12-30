(ns voice-fn.processors.elevenlabs
  (:require
   [clojure.core.async :as a]
   [clojure.pprint :as pprint]
   [hato.websocket :as ws]
   [taoensso.telemere :as t]
   [voice-fn.frames :as f]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.secrets :as secrets]
   [voice-fn.utils.core :as u])
  (:import
   (java.nio HeapCharBuffer)))

(def ^:private xi-tts-websocket-url "wss://api.elevenlabs.io/v1/text-to-speech/%s/stream-input")

(def models #{"eleven_multilingual_v2" "eleven_turbo_v2_5" "eleven_turbo_v2" "eleven_monolingual_v1" "eleven_multilingual_v1" "eleven_multilingual_sts_v2" "eleven_flash_v2" "eleven_flash_v2_5" "eleven_english_sts_v2"})

(def elevenlabs-encoding
  "Mapping from clojure sound encoding to elevenlabs format"
  {:ulaw :ulaw_8000
   :mp3 :mp3_44100})

(defn encoding->elevenlabs
  ([format]
   (elevenlabs-encoding format))
  ([format sample-rate]
   (keyword (str (name format) "_" sample-rate))))

(defn make-elevenlabs-url
  [pipeline-config processor-config]
  (let [{:audio-out/keys [encoding sample-rate]
         :pipeline/keys [language]} pipeline-config
        {:elevenlabs/keys [model-id voice-id]
         :or {model-id "eleven_flash_v2_5"
              voice-id "cjVigY5qzO86Huf0OWal"}}
        processor-config]
    (u/append-search-params (format xi-tts-websocket-url voice-id)
                            {:model_id model-id
                             :language_code language
                             :output-format (encoding->elevenlabs encoding sample-rate)})))

(comment
  (make-elevenlabs-url {:audio-in/sample-rate 8000
                        :audio-in/encoding :ulaw
                        :audio-in/channels 1
                        :audio-in/sample-size-bits 8
                        :audio-out/sample-rate 8000
                        :audio-out/encoding :ulaw
                        :audio-out/bitrate 64000
                        :audio-out/sample-size-bits 8
                        :audio-out/channels 1
                        :pipeline/language :ro}
                       {:elevenlabs/api-key (secrets/secret [:elevenlabs :api-key])
                        :elevenlabs/model-id "eleven_flash_v2_5"
                        :elevenlabs/voice-id "7sJPxFeMXAVWZloGIqg2"
                        :voice/stability 0.5
                        :voice/similarity-boost 0.8
                        :voice/use-speaker-boost? true}))

(defn begin-stream-message
  [{:voice/keys [stability similarity-boost use-speaker-boost?]
    :elevenlabs/keys [api-key]
    :or {stability 0.5
         similarity-boost 0.8
         use-speaker-boost? true}
    :as config}]

  (u/json-str {:text " "
               :voice_settings {:stability stability
                                :similarity_boost similarity-boost
                                :use_speaker_boost use-speaker-boost?}
               :xi_api_key api-key}))

(def close-stream-message
  {:text ""})

(defn text-message
  [text]
  (u/json-str {:text text
               :generation_config {:flush true}}))

(defn create-connection-config
  [type pipeline processor-config]
  {:on-open (fn [ws]
              (t/log! :info "Elevenlabs websocket connection open")
              (ws/send! ws (begin-stream-message processor-config)))
   :on-message (fn [_ws ^HeapCharBuffer data _last?]
                 (let [m (u/parse-if-json (str data))]
                   (t/log! :debug ["Elevenlabs result", (class m)])
                   (if (string? m)
                     (do
                       (t/log! :debug "Putting audio chunk on pipeline")
                       (a/put! (:pipeline/main-ch @pipeline)
                               (f/elevenlabs-audio-chunk-frame m)))
                     (when (:audio m)
                       (t/log! :debug "Putting full audio on pipeline")
                       (a/put! (:pipeline/main-ch @pipeline)
                               (f/audio-output-frame (:audio m)))))))
   :on-error (fn [_ e]
               (t/log! :error ["Error" e]))
   :on-close (fn [_ws code reason]
               (t/log! :info ["Elevenlabs websocket connection closed" "Code:" code "Reason:" reason]))})

(def max-reconnect-attempts 5)

(defn connect-websocket!
  [type pipeline processor-config]
  (let [current-count (get-in @pipeline [type :websocket/reconnect-count] 0)]
    (if (>= current-count max-reconnect-attempts)
      (t/log! :warn "Maximum reconnection attempts reached for Elevenlabs")
      (do
        (t/log! :info (str "Attempting to connect to Elevenlabs (attempt " (inc current-count) "/" max-reconnect-attempts ")"))
        (swap! pipeline update-in [type :websocket/reconnect-count] (fnil inc 0))
        (let [conn-config (create-connection-config
                            type
                            pipeline
                            processor-config)
              new-conn @(ws/websocket (make-elevenlabs-url (:pipeline/config @pipeline) processor-config)
                                      conn-config)]
          (swap! pipeline assoc-in [type :websocket/conn] new-conn))))))

(defn close-elevenlabs-websocket!
  [conn]
  (when conn
    (ws/send! conn (u/json-str close-stream-message))
    (ws/close! conn)))

(defn- close-websocket-connection!
  [pipeline]
  (close-elevenlabs-websocket! (get-in [:tts/elevenlabs :websocket/conn] @pipeline))
  (swap! pipeline update-in [:tts/elevenlabs] dissoc :websocket/conn))

(defmethod pipeline/process-frame :tts/elevenlabs
  [type pipeline processor frame]
  (case (:frame/type frame)
    :system/start
    (do (t/log! :debug "Starting text to speech engine")
        (connect-websocket! type pipeline (:processor/config processor)))
    :system/stop (close-websocket-connection! pipeline)

    :llm/output-text-sentence
    (when-let [conn (get-in @pipeline [type :websocket/conn])]
      (ws/send! conn (text-message (:frame/data frame))))))

(defmethod pipeline/process-frame :elevenlabs/audio-assembler
  [type pipeline _ frame]
  (t/log! :debug ["Audio Chunk" frame])
  (let [acc (get-in @pipeline [type :audio-accumulator] "")]
    (case type
      :elevenlabs/audio-chunk
      (let [attempt (u/parse-if-json (str acc (:frame/data frame)))]
        (if-let [audio (:audio attempt)]
          (do
            (swap! pipeline assoc-in [type :audio-accumulator] "")
            (a/put! (:pipeline/main-ch @pipeline)
                    (f/audio-output-frame audio)))
          (swap! pipeline assoc-in [type :audio-accumulator] attempt)))
      nil)))
