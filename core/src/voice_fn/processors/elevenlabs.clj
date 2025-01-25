(ns voice-fn.processors.elevenlabs
  (:require
   [clojure.core.async :as a]
   [hato.websocket :as ws]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.protocol :as p]
   [voice-fn.schema :as schema]
   [voice-fn.secrets :as secrets]
   [voice-fn.utils.core :as u])
  (:import
   (java.nio HeapCharBuffer)))

(def ^:private xi-tts-websocket-url "wss://api.elevenlabs.io/v1/text-to-speech/%s/stream-input")

(def elevenlabs-encoding
  "Mapping from clojure sound encoding to elevenlabs format"
  {:ulaw :ulaw_8000
   :mp3 :mp3_44100})

(defn encoding->elevenlabs
  ([format]
   (elevenlabs-encoding format))
  ([format sample-rate]
   (keyword (str (name format) "_" sample-rate))))

(defn make-elevenlabs-ws-url
  [args]
  (let [{:audio.out/keys [encoding sample-rate]
         :flow/keys [language]
         :elevenlabs/keys [model-id voice-id]
         :or {model-id "eleven_flash_v2_5"
              voice-id "cjVigY5qzO86Huf0OWal"}}
        args]
    (u/append-search-params (format xi-tts-websocket-url voice-id)
                            {:model_id model-id
                             :language_code language
                             :output_format (encoding->elevenlabs encoding sample-rate)})))

(comment
  (make-elevenlabs-ws-url
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
         use-speaker-boost? true}}]
  (u/json-str {:text " "
               :voice_settings {:stability stability
                                :similarity_boost similarity-boost
                                :use_speaker_boost use-speaker-boost?}
               :xi_api_key api-key}))

(def close-stream-message
  (u/json-str {:text ""}))

(def keep-alive-message
  "Sent to keep the connection alive"
  (u/json-str {:text " "}))

(defn text-message
  [text]
  (u/json-str {:text (str text " ")
               :flush true}))

(defn create-connection-config
  [type pipeline processor-config]
  {:on-open (fn [ws]
              (let [configuration (begin-stream-message processor-config)]
                (t/log! :info ["Elevenlabs websocket connection open. Sending configuration message" configuration])
                (ws/send! ws configuration)
                ;; Send a keepalive message every 3 seconds to maintain websocket connection
                (a/go-loop []
                  (a/<! (a/timeout 3000))
                  (when (get-in @pipeline [type :websocket/conn])
                    (t/log! {:level :debug :id type} "Sending keep-alive message")
                    (ws/send! ws keep-alive-message)
                    (recur)))))
   :on-message (fn [_ws ^HeapCharBuffer data _last?]
                 (let [acc (get-in @pipeline [type :audio/accumulator] "")
                       attempt (u/parse-if-json (str acc data))]
                   (if (map? attempt)
                     (when-let [audio (:audio attempt)]
                       (swap! pipeline assoc-in [type :audio/accumulator] "")
                       (when-not (pipeline/interrupted? @pipeline)
                         (pipeline/send-frame! pipeline (frame/audio-output-raw (u/decode-base64 audio)))))
                     (swap! pipeline assoc-in [type :audio/accumulator] attempt))))
   :on-error (fn [_ e]
               (swap! pipeline update-in [type :websocket/conn] dissoc)
               (t/log! :error ["Elevenlabs websocket error" (ex-message e)]))
   :on-close (fn [_ws code reason]
               (swap! pipeline update-in [type :websocket/conn] dissoc)
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
              new-conn @(ws/websocket (make-elevenlabs-ws-url processor-config)
                                      conn-config)]
          (swap! pipeline assoc-in [type :websocket/conn] new-conn)
          (t/log! :debug "Elevenlabs connection ready"))))))

(defn- close-websocket-connection!
  [type pipeline]
  (t/log! :info "Closing elevenlabs websocket connection")
  (when-let  [conn (get-in @pipeline [type :websocket/conn])]
    (ws/send! conn close-stream-message)
    (ws/close! conn))
  (swap! pipeline update-in [type] dissoc :websocket/conn))

(def ElevenLabsTTSConfig
  "Configuration for Elevenlabs TextToSpeech service"
  [:map
   {:closed true ;; No additional fields allowed
    :description "ElevenLabs TTS configuration"}
   [:elevenlabs/api-key
    [:string
     {:min 32      ;; ElevenLabs API keys are typically long
      :secret true ;; Marks this as sensitive data
      :description "ElevenLabs API key"}]]
   [:elevenlabs/model-id
    (schema/flex-enum
      {:default "eleven_flash_v2_5"
       :description "ElevenLabs model identifier"}
      ["eleven_multilingual_v2" "eleven_turbo_v2_5" "eleven_turbo_v2" "eleven_monolingual_v1" "eleven_multilingual_v1" "eleven_multilingual_sts_v2" "eleven_flash_v2" "eleven_flash_v2_5" "eleven_english_sts_v2"])]

   [:elevenlabs/voice-id
    [:string
     {:min 20 ;; ElevenLabs voice IDs are fixed length
      :max 20
      :description "ElevenLabs voice identifier"}]]
   [:voice/stability
    [:double
     {:min 0.0
      :max 1.0
      :default 0.5
      :description "Voice stability factor (0.0 to 1.0)"}]]
   [:voice/similarity-boost
    [:double
     {:min 0.0
      :max 1.0
      :default 0.8
      :description "Voice similarity boost factor (0.0 to 1.0)"}]]
   [:voice/use-speaker-boost?
    [:boolean
     {:default true
      :description "Whether to enable speaker beoost enhancement"}]]])

(defmethod pipeline/create-processor :processor.speech/elevenlabs
  [id]
  (reify p/Processor
    (processor-id [_] id)

    (processor-schema [_] ElevenLabsTTSConfig)

    (accepted-frames [_] #{:frame.system/start
                           :frame.system/stop
                           :frame.llm/text-chunk
                           :frame.llm/response-start
                           :frame.control/interrupt-start})

    (make-processor-config [_ _ processor-config]
      processor-config)

    (process-frame [this pipeline processor-config frame]
      (let [id (p/processor-id this)]
        (cond
          (frame/system-start? frame)
          (do (t/log! {:level :debug
                       :id id} "Starting text to speech engine")
              (connect-websocket! id pipeline processor-config))
          (frame/system-stop? frame) (close-websocket-connection! id pipeline)

          (and (frame/llm-text-chunk? frame)
               (not (pipeline/interrupted? @pipeline)))
          (let [conn (get-in @pipeline [id :websocket/conn])
                acc (get-in @pipeline [id :sentence/accumulator] "")
                {:keys [sentence accumulator]} (u/assemble-sentence acc (:frame/data frame))]
            (t/log! :debug ["sentence" sentence "accumulator" accumulator])
            ;; add new accumulator first so next call of this processor doesn't race
            ;; condition
            (swap! pipeline assoc-in [id :sentence/accumulator] accumulator)
            (when sentence
              (let [xi-message (text-message sentence)]
                (t/log! {:level :debug
                         :id id} ["Sending websocket payload" xi-message])
                (ws/send! conn xi-message))))

          (frame/llm-full-response-start? frame)
          (swap! pipeline assoc-in [id :sentence/accumulator] "")

          ;; reset accumulator when pipeline gets interrupted
          (frame/control-interrupt-start? frame)
          (swap! pipeline update-in [id] merge {:sentence/accumulator ""}))))))
