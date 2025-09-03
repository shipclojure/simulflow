(ns simulflow-examples.local-with-mute-filter
  "This example demonstrates muting user input handling​ When the LLM is responding to
  the user, if the user says something while the bot is speaking it will not be processed."
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.filters.mute :as mute-filter]
   [simulflow.processors.deepgram :as deepgram]
   [simulflow.processors.elevenlabs :as xi]
   [simulflow.processors.llm-context-aggregator :as context]
   [simulflow.processors.openai :as openai]
   [simulflow.processors.system-frame-router :as system-router]
   [simulflow.secrets :refer [secret]]
   [simulflow.transport :as transport]
   [simulflow.transport.in :as transport-in]
   [simulflow.transport.out :as transport-out]
   [taoensso.telemere :as t]))

(def llm-context
  {:messages
   [{:role "system"
     :content "You are a voice agent operating via phone. Be
                       concise in your answers. The input you receive comes from a
                       speech-to-text (transcription) system that isn't always
                       efficient and may send unclear text. Ask for
                       clarification when you're unsure what the person said."}]
   :tools
   [{:type :function
     :function
     {:name "get_weather"
      :handler (fn [{:keys [town]}] (str "The weather in " town " is 17 degrees celsius"))
      :description "Get the current weather of a location"
      :parameters {:type :object
                   :required [:town]
                   :properties {:town {:type :string
                                       :description "Town for which to retrieve the current weather"}}
                   :additionalProperties false}
      :strict true}}]})

(def chunk-duration 20)

(def flow-processors
  {;; Capture audio from microphone and send raw-audio-input frames further in the pipeline
   :transport-in {:proc transport-in/microphone-transport-in
                  :args {:vad/analyser :vad.analyser/silero
                         :pipeline/supports-interrupt? false}}
   ;; raw-audio-input -> transcription frames
   :transcriptor {:proc deepgram/deepgram-processor
                  :args {:transcription/api-key (secret [:deepgram :api-key])
                         :transcription/interim-results? true
                         :transcription/punctuate? false
                         ;; We use silero for computing VAD
                         :transcription/vad-events? false
                         :transcription/smart-format? true
                         :transcription/model :nova-2
                         :transcription/utterance-end-ms 1000
                         :transcription/language :en}}

   ;; user transcription & llm message frames -> llm-context frames
   ;; responsible for keeping the full conversation history
   :context-aggregator  {:proc context/context-aggregator
                         :args {:llm/context llm-context}}

   ;; Takes llm-context frames and produces new llm-text-chunk & llm-tool-call-chunk frames
   :llm {:proc openai/openai-llm-process
         :args {:openai/api-key (secret [:openai :new-api-sk])
                :llm/model :gpt-4.1-mini}}

   ;; llm-text-chunk & llm-tool-call-chunk -> llm-context-messages-append frames
   :assistant-context-assembler {:proc context/assistant-context-assembler
                                 :args {}}

   ;; llm-text-chunk -> sentence speak frames (faster for text to speech)
   :llm-sentence-assembler {:proc context/llm-sentence-assembler}

   ;; speak-frames -> audio-output-raw frames
   :tts {:proc xi/elevenlabs-tts-process
         :args {:elevenlabs/api-key (secret [:elevenlabs :api-key])
                :elevenlabs/model-id "eleven_flash_v2_5"
                :elevenlabs/voice-id (secret [:elevenlabs :voice-id])
                :voice/stability 0.5
                :voice/similarity-boost 0.8
                :voice/use-speaker-boost? true
                :pipeline/language :en}}

   ;; audio-output-raw -> smaller audio-output-raw frames (used for sending audio in realtime)
   :audio-splitter {:proc transport/audio-splitter
                    :args {:audio.out/duration-ms chunk-duration}}

   ;; speakers out
   :transport-out {:proc transport-out/realtime-speakers-out-processor
                   :args {:audio.out/sending-interval chunk-duration
                          :audio.out/duration-ms chunk-duration}}

   :mute-filter {:proc mute-filter/process
                 :args {:mute/strategies #{:mute.strategy/bot-speech}}}
   ;; Fan out of system frames to processors that need it
   :system-router {:proc system-router/system-frame-router}})

(def flow-conns (into (system-router/generate-system-router-connections flow-processors)
                      [[[:transport-in :out] [:transcriptor :in]]

                       [[:transcriptor :out] [:context-aggregator :in]]
                       [[:context-aggregator :out] [:llm :in]]

                       ;; Aggregate full context
                       [[:llm :out] [:assistant-context-assembler :in]]
                       [[:assistant-context-assembler :out] [:context-aggregator :in]]

                       ;; Assemble sentence by sentence for fast speech
                       [[:llm :out] [:llm-sentence-assembler :in]]

                       [[:tts :out] [:audio-splitter :in]]
                       [[:audio-splitter :out] [:transport-out :in]]]))

(def flow-config
  {:procs flow-processors
   :conns flow-conns})

(def g (flow/create-flow flow-config))

(defonce flow-started? (atom false))

(comment

  ;; Start local ai flow - starts paused
  (let [{:keys [report-chan error-chan]} (flow/start g)]
    (reset! flow-started? true)
    ;; Resume local ai -> you can now speak with the AI
    (flow/resume g)
    (vthread-loop []
      (when @flow-started?
        (when-let [[msg c] (a/alts!! [report-chan error-chan])]
          (when (map? msg)
            (t/log! (cond-> {:level :debug :id (if (= c error-chan) :error :report)}
                      (= c error-chan) (assoc :error msg)) msg))
          (recur)))))

  ;; Stop the conversation
  (do
    (flow/stop g)
    (reset! flow-started? false))

  ,)
