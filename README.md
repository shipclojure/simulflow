# voice-fn - Real-time Voice AI Pipeline Framework

`voice-fn` is a Clojure framework for building real-time voice AI applications using a data-driven, functional approach. It provides a composable pipeline architecture for processing audio, text, and AI interactions.

## Core Features

- **Data-First Design:** Define AI pipelines as data structures for easy configuration and modification
- **Streaming Architecture:** Built on core.async for efficient real-time audio and text processing
- **Extensible Processors:** Simple protocol-based system for adding new processing components
- **Flexible Frame System:** Type-safe message passing between pipeline components
- **Built-in Services:** Ready-to-use integrations with:
  - Speech Recognition (Deepgram)
  - Language Models (OpenAI, Groq)
  - Text-to-Speech (ElevenLabs)
- **Transport Options:** Support for WebSocket, HTTP, and local audio I/O

## Quick Start

``` clojure
(require '[voice-fn.pipeline :as pipeline]
         '[clojure.core.async :as a]
         '[voice-fn.secrets :refer [secret]])

;; Define pipeline configuration
(def config
  {:pipeline/config
   {:audio-in/sample-rate 8000
    :audio-in/encoding :ulaw
    :audio-in/channels 1
    :audio-in/sample-size-bits 8
    :audio-out/sample-rate 8000
    :audio-out/encoding :ulaw
    :audio-out/sample-size-bits 8
    :audio-out/channels 1
    :pipeline/supports-interrupt? true
    :pipeline/language :en
    :llm/context [{:role "system"
                   :content "You are a voice assistant. Keep responses concise. Ask for clarification if user input is unclear."}]
    :transport/in-ch (a/chan 1024) ;; input channel
    :transport/out-ch (a/chan 1024)} ;; output channel

   :pipeline/processors
   [;; Input handling
    {:processor/id :processor.transport/twilio-input}

    ;; Speech recognition
    {:processor/id :processor.transcription/deepgram
     :processor/config {:transcription/api-key (secret [:deepgram :api-key])
                       :transcription/interim-results? true
                       :transcription/punctuate? false
                       :transcription/vad-events? true
                       :transcription/smart-format? true
                       :transcription/model :nova-2
                       :transcription/utterance-end-ms 1000}}

    ;; User message handling
    {:processor/id :context.aggregator/user
     :processor/config {:aggregator/debug? true}}

    ;; LLM processing
    {:processor/id :processor.llm/openai
     :processor/config {:llm/model "gpt-4"
                       :openai/api-key (secret [:openai :api-key])}}

    ;; AI response handling
    {:processor/id :context.aggregator/assistant}

    ;; Text-to-speech
    {:processor/id :processor.speech/elevenlabs
     :processor/config {:elevenlabs/api-key (secret [:elevenlabs :api-key])
                       :elevenlabs/model-id "eleven_flash_v2_5"
                       :elevenlabs/voice-id "7sJPxFeMXAVWZloGIqg2"
                       :voice/stability 0.5
                       :voice/similarity-boost 0.8
                       :voice/use-speaker-boost? true}}

    ;; Output handling
    {:processor/id :processor.transport/async-output}]})

;; Create and run pipeline
(def p (pipeline/create-pipeline config))
(pipeline/start-pipeline! p)
(pipeline/stop-pipeline! p)
```

## Key Concepts

### Frames
The basic unit of data flow, representing typed messages like:
- `:audio/raw-input` - Raw audio data
- `:text/input` - Transcribed text
- `:llm/text-chunk` - LLM response chunks
- `:system/start`, `:system/stop` - Control signals

### Processors
Components that transform frames:
- Implement the `Processor` protocol
- Define accepted and generated frame types
- Can maintain state in the pipeline atom
- Use core.async for async processing

### Pipeline
Coordinates the flow of frames between processors:
- Validates configuration using Malli schemas
- Manages processor lifecycle
- Handles frame routing
- Maintains system state

## Adding Custom Processors

```clojure
(defmethod pipeline/create-processor :my/processor
  [id]
  (reify p/Processor
    (processor-id [_] id)
    (processor-schema [_] [:map [:my/config :string]])
    (accepted-frames [_] #{:text/input})
    (make-processor-config [_ pipeline-config processor-config]
      processor-config)
    (process-frame [_ pipeline config frame]
      ;; Process frame and return new frame(s)
      )))
```

## Built With

- [core.async](https://github.com/clojure/core.async) - Concurrent processing
- [Malli](https://github.com/metosin/malli) - Schema validation
- [Hato](https://github.com/gnarroway/hato) - WebSocket support

## License

MIT
