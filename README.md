# voice-fn - WIP

A Clojure library for building real-time voice-enabled AI applications. voice-fn handles the orchestration of speech recognition, audio processing, and AI service integration with the elegance of functional programming.

## Features

- Real-time speech recognition with configurable backends
- Clean functional interface for audio stream processing
- Seamless integration with popular AI models and services
- Built-in support for conversation state management
- Efficient audio processing with core.async channels
- Hot-reloadable pipeline components
- Extensible architecture with composable transforms

## Why voice-fn?

voice-fn brings Clojure's functional elegance to voice AI applications. Rather than wrestling with complex state management and imperative audio processing, voice-fn lets you create AI pipelines in a declarative way. 

```clojure
(ns example
  (:require
   [voice-fn.pipeline :as pipeline]
   [voice-fn.secrets :refer [secret]]))

(def async-echo-pipeline
  {:pipeline/config {:audio-in/sample-rate 8000
                     :audio-in/encoding :ulaw
                     :audio-in/channels 1
                     :audio-in/sample-size-bits 8
                     :audio-out/sample-rate 8000
                     :audio-out/bitrate 64000
                     :audio-out/sample-size-bits 8
                     :audio-out/channels 1
                     :pipeline/language :ro}
   :pipeline/processors [;;transport in
                         {:processor/type :transport/async-input
                          :processor/accepted-frames #{:system/start :system/stop}
                          :processor/generates-frames #{:audio/raw-input}}
                         ;; transcription
                         {:processor/type :transcription/deepgram
                          :processor/accepted-frames #{:system/start :system/stop :audio/raw-input}
                          :processor/generates-frames #{:text/input}
                          :processor/config {:transcription/api-key (secret [:deepgram :api-key])
                                             :transcription/interim-results? false
                                             :transcription/punctuate? false
                                             :transcription/model :nova-2}}
                        ;; text to speech
                        {:processor/type :tts/openai
                         :processor/accepted-frames #{:text/input}
                         :processor/generates-frames #{:audio/output}
                         :processor/config {:openai/api-key (secret [:openai :api-key])}}
                        ;; transport out
                         {:processor/type :transport/async-output
                          :processor/accepted-frames #{:audio/output :system/stop}
                          :generates/frames #{}}]})

(def p (pipeline/create-pipeline async-echo-pipeline))
(pipeline/start-pipeline! p) ;;starting pipeline
(pipeline/stop-pipeline! p) ;;stopping
```

## Status

WIP

## Documentation

See [docs/](docs/) for full documentation, including:
- Getting Started Guide
- Architecture Overview
- API Reference
- Examples
- Contributing Guidelines

## License

MIT

---

voice-fn - Functional voice AI, powered by Clojure.
