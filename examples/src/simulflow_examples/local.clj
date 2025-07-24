(ns simulflow-examples.local
  {:clj-reload/no-unload true}
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.processors.activity-monitor :as activity-monitor]
   [simulflow.processors.deepgram :as deepgram]
   [simulflow.processors.elevenlabs :as xi]
   [simulflow.processors.llm-context-aggregator :as context]
   [simulflow.processors.openai :as openai]
   [simulflow.secrets :refer [secret]]
   [simulflow.transport :as transport]
   [simulflow.utils.core :as u]
   [taoensso.telemere :as t]))



(defn make-local-flow
  "This example showcases a voice AI agent for the local computer.  Audio is
  usually encoded as PCM at 16kHz frequency (sample rate) and it is mono (1
  channel).

  :transport-in & :transport-out don't specify the audio configuration because
  these are the defaults. See each process for details
  "
  ([] (make-local-flow {}))
  ([{:keys [llm-context extra-procs extra-conns encoding debug?
            sample-rate language sample-size-bits channels chunk-duration-ms]
     :or {llm-context {:messages
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
                          :strict true}}]}

          encoding :pcm-signed
          sample-rate 16000
          sample-size-bits 16
          channels 1
          chunk-duration-ms 20
          language :en
          debug? false
          extra-procs {}
          extra-conns []}}]

   (flow/create-flow
     {:procs
      (u/deep-merge
        {;; Capture audio from microphone and send raw-audio-input frames further in the pipeline
         :transport-in {:proc transport/microphone-transport-in
                        :args {:audio-in/sample-rate sample-rate
                               :audio-in/channels channels
                               :audio-in/sample-size-bits sample-size-bits}}
         ;; raw-audio-input -> transcription frames
         :transcriptor {:proc deepgram/deepgram-processor
                        :args {:transcription/api-key (secret [:deepgram :api-key])
                               :transcription/interim-results? true
                               :transcription/punctuate? false
                               :transcription/vad-events? true
                               :transcription/smart-format? true
                               :transcription/model :nova-2
                               :transcription/utterance-end-ms 1000
                               :transcription/language language
                               :transcription/encoding encoding
                               :transcription/sample-rate sample-rate}}

         ;; user transcription & llm message frames -> llm-context frames
         ;; responsible for keeping the full conversation history
         :context-aggregator  {:proc context/context-aggregator
                               :args {:llm/context llm-context
                                      :aggregator/debug? debug?}}

         ;; Takes llm-context frames and produces new llm-text-chunk & llm-tool-call-chunk frames
         :llm {:proc openai/openai-llm-process
               :args {:openai/api-key (secret [:openai :new-api-sk])
                      :llm/model "gpt-4o-mini"}}

         ;; llm-text-chunk & llm-tool-call-chunk -> llm-context-messages-append frames
         :assistant-context-assembler {:proc context/assistant-context-assembler
                                       :args {:debug? debug?}}

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
                      :flow/language language
                      :audio.out/encoding encoding
                      :audio.out/sample-rate sample-rate}}

         ;; audio-output-raw -> smaller audio-output-raw frames (used for sending audio in realtime)
         :audio-splitter {:proc transport/audio-splitter
                          :args {:audio.out/sample-rate sample-rate
                                 :audio.out/sample-size-bits sample-size-bits
                                 :audio.out/channels channels
                                 :audio.out/duration-ms chunk-duration-ms}}

         ;; speakers out
         :transport-out {:proc transport/realtime-speakers-out-processor
                         :args {:audio.out/sample-rate sample-rate
                                :audio.out/sample-size-bits sample-size-bits
                                :audio.out/channels channels
                                :audio.out/duration-ms chunk-duration-ms}}
         :prn-sink {:proc (flow/process (fn
                                          ([] {:ins {:in "gimme stuff to print!"}})
                                          ([_] nil)
                                          ([_ _] nil)
                                          ([_ _ v] (t/log! {:id :prn-sink :data v}))))}
         :activity-monitor {:proc activity-monitor/process
                            :args {::activity-monitor/timeout-ms 5000}}}
        extra-procs)
      :conns (concat
               [[[:transport-in :out] [:transcriptor :in]]

                [[:transcriptor :out] [:context-aggregator :in]]
                [[:context-aggregator :out] [:llm :in]]

                ;; Aggregate full context
                [[:llm :out] [:assistant-context-assembler :in]]
                [[:assistant-context-assembler :out] [:context-aggregator :in]]

                ;; Assemble sentence by sentence for fast speech
                [[:llm :out] [:llm-sentence-assembler :in]]
                [[:llm-sentence-assembler :out] [:tts :in]]

                [[:tts :out] [:audio-splitter :in]]
                [[:audio-splitter :out] [:transport-out :in]]

                [[:transport-out :out] [:prn-sink :in]]

                ;; Activity detection
                [[:transport-out :out] [:activity-monitor :in]]
                [[:transcriptor :out] [:activity-monitor :in]]
                [[:activity-monitor :out] [:context-aggregator :in]]
                [[:activity-monitor :out] [:tts :in]]]
               extra-conns)})))

(comment

  (def local-ai (make-local-flow))

  (defonce flow-started? (atom false))

  ;; Start local ai flow - starts paused
  (let [{:keys [report-chan error-chan]} (flow/start local-ai)]
    (reset! flow-started? true)
    ;; Resume local ai -> you can now speak with the AI
    (flow/resume local-ai)
    (vthread-loop []
      (when @flow-started?
        (when-let [[msg c] (a/alts!! [report-chan error-chan])]
          (when (map? msg)
            (t/log! (cond-> {:level :debug :id (if (= c error-chan) :error :report)}
                      (= c error-chan) (assoc :error msg)) msg))
          (recur)))))

  ;; Stop the conversation
  (do
    (flow/stop local-ai)
    (reset! flow-started? false))

  ,)
