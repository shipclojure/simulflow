(ns voice-fn.core
  (:require
   [clojure.core.async :as a]
   [ring.websocket.protocols :as wsp]
   [taoensso.telemere :as t]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.processors.deepgram]
   [voice-fn.processors.openai]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.transport.async]
   [voice-fn.transport.local.audio]))

(def local-transcription-log-pipeline
  {:pipeline/config {:audio-in/sample-rate 16000
                     :audio-in/encoding :pcm-signed
                     :audio-in/channels 1
                     :audio-in/file-path "test-voice.wav"
                     :audio-in/sample-size-bits 16 ;; 2 bytes
                     :audio-out/sample-rate 24000
                     :audio-out/bitrate 96000
                     :audio-out/sample-size-bits 16
                     :audio-out/channels 1
                     :pipeline/language :ro}
   :pipeline/processors [{:processor/type :transport/local-audio
                          :processor/accepted-frames #{:system/start :system/stop}
                          :processor/generates-frames #{:audio/raw-input}}
                         {:processor/type :transcription/deepgram
                          :processor/accepted-frames #{:system/start :system/stop :audio/raw-input}
                          :processor/generates-frames #{:text/input}
                          :processor/config {:transcription/api-key (secret [:deepgram :api-key])
                                             :transcription/interim-results? false
                                             :transcription/punctuate? false
                                             :transcription/model :nova-2}}
                         {:processor/type :log/text-input
                          :processor/accepted-frames #{:text/input}
                          :processor/config {}}]})

(def ws-client (a/chan 10))
(def ws-server (a/chan 10))

(def mock-socket (reify
                   wsp/Socket
                   (-send [_ message]
                     (a/put! ws-client [:send message]))
                   (-close [_ code reason]
                     (a/>!! ws-client [:close code reason]))
                   wsp/AsyncSocket
                   (-send-async [_ mesg succeed _]
                     (a/>!! ws-client [:send mesg])
                     (succeed))))

(def async-echo-pipeline
  {:pipeline/config {:audio-in/sample-rate 8000
                     :audio-in/encoding :ulaw
                     :audio-in/channels 1
                     :audio-in/sample-size-bits 8
                     :audio-out/sample-rate 8000
                     :audio-out/encoding :ulaw
                     :audio-out/bitrate 64000
                     :audio-out/sample-size-bits 8
                     :audio-out/channels 1
                     :pipeline/language :ro}
   :pipeline/processors [;; Transport in
                         {:processor/type :transport/async-input
                          :processor/accepted-frames #{:system/start :system/stop}
                          :processor/generates-frames #{:audio/raw-input}}
                         ;; Transcription
                         {:processor/type :transcription/deepgram
                          :processor/accepted-frames #{:system/start :system/stop :audio/raw-input}
                          :processor/generates-frames #{:text/input}
                          :processor/config {:transcription/api-key (secret [:deepgram :api-key])
                                             :transcription/interim-results? false
                                             :transcription/punctuate? false
                                             :transcription/model :nova-2}}
                         ;; LLM
                         {:processor/type :llm/openai
                          :processor/accepted-frames #{:system/stop :text/input}
                          :processor/generates-frames #{:llm/output-text-chunk}
                          :processor/config {:llm/model "gpt-4o-mini"
                                             :llm/messages [{:role "system" :content "You are a helpful assistant"}]
                                             :openai/api-key (secret [:openai :new-api-key])}}

                         ;; Sentence assembler
                         {:processor/type :llm/sentence-assembler
                          :processor/accepted-frames #{:system/stop :system/start :llm/output-text-chunk}
                          :processor/generates-frames #{:llm/output-text-sentence}
                          :processor/config {:sentence/end-matcher #"[.?!]"}}
                         ;; TTS
                         {:processor/type :tts/elevenlabs
                          :processor/accepted-frames #{:system/stop :system/start :llm/output-text-sentence}
                          :processor/generates-frames #{:audio/output}
                          :processor/config {:elevenlabs/api-key (secret [:elevenlabs :api-key])
                                             :elevenlabs/model-id "eleven_flash_v2_5"
                                             :elevenlabs/voice-id "7sJPxFeMXAVWZloGIqg2"
                                             :voice/stability 0.5
                                             :voice/similarity-boost 0.8
                                             :voice/use-speaker-boost? true}}
                         ;; Logger
                         {:processorvoi/type :log/text-input
                          :processor/accepted-frames #{:text/input}
                          :processor/generates-frames #{}
                          :processor/config {}}
                         ;; Transport out
                         {:processor/type :transport/async-output
                          :processor/accepted-frames #{:audio/output :system/stop}
                          :generates/frames #{}}]})

(defmethod pipeline/process-frame :log/text-input
  [_ _ _ frame]
  (t/log! {:level :info
           :id :log/text-input} ["Frame" (:frame/data frame)]))

(t/set-min-level! :debug)

(comment
  (def p (pipeline/create-pipeline async-echo-pipeline))

  @p

  (pipeline/start-pipeline! p)
  (pipeline/stop-pipeline! p)

  ,)
