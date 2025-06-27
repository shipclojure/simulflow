(ns simulflow.processors.elevenlabs
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [hato.websocket :as ws]
            [simulflow.async :refer [vthread-loop]]
            [simulflow.frame :as frame]
            [simulflow.schema :as schema]
            [simulflow.secrets :as secrets]
            [simulflow.utils.core :as u]
            [taoensso.telemere :as t])
  (:import (java.nio HeapCharBuffer)))

(def ^:private xi-tts-websocket-url "wss://api.elevenlabs.io/v1/text-to-speech/%s/stream-input")

(def elevenlabs-encoding
  "Mapping from clojure sound encoding to elevenlabs format"
  {:ulaw :ulaw
   :mp3 :mp3
   :pcm-signed :pcm
   :pcm-unsigned :pcm
   :pcm-float :pcm})

(defn encoding->elevenlabs
  [format sample-rate]
  (keyword (str (name (elevenlabs-encoding format)) "_" sample-rate)))

(defn make-elevenlabs-ws-url
  [args]
  (let [{:audio.out/keys [encoding sample-rate]
         :flow/keys [language]
         :elevenlabs/keys [model-id voice-id]
         :or {model-id "eleven_flash_v2_5"}}
        args]
    (assert voice-id "Voice ID is required")
    (u/append-search-params (format xi-tts-websocket-url voice-id)
                            {:model_id model-id
                             :language_code language
                             :output_format (encoding->elevenlabs encoding sample-rate)})))

(comment
  (make-elevenlabs-ws-url
    {:elevenlabs/api-key (secrets/secret [:elevenlabs :api-key])
     :elevenlabs/model-id "eleven_flash_v2_5"
     :elevenlabs/voice-id (secrets/secret [:elevenlabs :voice-id])
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

(defn tts-transform [{::keys [accumulator] :as state} in-name msg]
  (if (= in-name ::ws-read)
    ;; xi sends one json response in multiple events so it needs
    ;; to be concattenated until the final json can be parsed
    (let [attempt (u/parse-if-json (str accumulator msg))]
      (if (map? attempt)
        [(assoc state ::accumulator "")
         (when-let [audio (:audio attempt)]
           {:out [(frame/audio-output-raw (u/decode-base64 audio) {:timestamp (:now state)})
                  (frame/xi-audio-out attempt {:timestamp (:now state)})]})]

        ;; continue concatenating
        [(assoc state ::accumulator attempt)]))
    (cond
      (frame/speak-frame? msg)
      [state {::ws-write [(text-message (:frame/data msg))]}]
      :else [state])))

(def elevenlabs-tts-process
  (flow/process
    (flow/map->step
      {:describe (fn [] {:ins {:sys-in "Channel for system messages that take priority"
                               :in "Channel for audio input frames (from transport-in) "}
                         :outs {:sys-out "Channel for system messages that have priority"
                                :out "Channel on which transcription frames are put"}
                         :params {:elevenlabs/api-key "Api key required for 11labs connection"
                                  :elevenlabs/model-id "Model used for voice generation"
                                  :elevenlabs/voice-id "Voice id"
                                  :voice/stability "Optional voice stability factor (0.0 to 1.0)"
                                  :voice/similarity-boost "Optional voice similarity boost factor (0.0 to 1.0)"
                                  :voice/use-speaker-boost? "Wether to enable speaker boost enchancement"
                                  :flow/language "Language to use"
                                  :audio.out/encoding "Encoding for the audio generated"
                                  :audio.out/sample-rate "Sample rate for the audio generated"}
                         :workload :io})
       :init (fn [args]
               (let [url (make-elevenlabs-ws-url args)
                     ws-read (a/chan 100)
                     ws-write (a/chan 100)
                     configuration-msg (begin-stream-message args)
                     alive? (atom true)
                     conf {:on-open (fn [ws]
                                      (t/log! :debug ["Elevenlabs websocket connection open. Sending configuration message" configuration-msg])
                                      (ws/send! ws configuration-msg))
                           :on-message (fn [_ws ^HeapCharBuffer data _last?]
                                         (a/put! ws-read (str data)))
                           :on-error (fn [_ e]
                                       (t/log! :error ["Elevenlabs websocket error" (ex-message e)]))
                           :on-close (fn [_ws code reason]
                                       (reset! alive? false)
                                       (t/log! :debug ["Elevenlabs websocket connection closed" "Code:" code "Reason:" reason]))}
                     _ (t/log! {:level :debug :id :elevenlabs} "Connecting to transcription websocket")
                     ws-conn @(ws/websocket url conf)]
                 (vthread-loop []
                   (when @alive?
                     (when-let [msg (a/<!! ws-write)]
                       (when @alive?
                         (ws/send! ws-conn msg))
                       (recur))))
                 (vthread-loop []
                   (when @alive?
                     (a/<!! (a/timeout 3000))
                     (t/log! {:level :trace :id :elevenlabs} "Sending keep-alive message")
                     (ws/send! ws-conn keep-alive-message)
                     (recur)))
                 {:websocket/conn ws-conn
                  :websocket/alive? alive?
                  ::flow/in-ports {::ws-read ws-read}
                  ::flow/out-ports {::ws-write ws-write}}))
       :transition (fn [{:websocket/keys [conn]
                         ::flow/keys [in-ports out-ports]
                         :as state} transition]
                     (when (= transition ::flow/stop)
                       (t/log! {:id :elevenlabs :level :info} "Closing tts websocket connection")
                       (reset! (:websocket/alive? state) false)
                       (when conn
                         (ws/send! conn close-stream-message)
                         (ws/close! conn))
                       (doseq [port (concat (vals in-ports) (vals out-ports))]
                         (a/close! port)))
                     state)

       :transform tts-transform})))
