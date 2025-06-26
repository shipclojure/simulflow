# Simulflow Project Summary

## Overview

**Simulflow** is a Clojure framework for building real-time multimodal AI applications using a data-driven, functional approach. It provides a composable pipeline architecture for processing audio, text, and AI interactions with built-in support for major AI providers. The framework is built on top of `clojure.core.async.flow` and follows the principle of "data first, not methods first."

The project takes heavy inspiration from [pipecat](https://github.com/pipecat-ai/pipecat) but reimagines the architecture for the Clojure ecosystem, using graphs instead of bidirectional queues and emphasizing pure functions with data-centric configuration.

## Core Architecture

### Data-Driven Design

Simulflow is built around three core concepts:

1. **Frames** - Typed data structures representing discrete units of information flowing through the pipeline
2. **Processors** - Pure functions that transform frames following `core.async.flow` patterns  
3. **Flows** - Composable pipelines defined as data structures with processors connected by channels

### Frame System

Frames are the fundamental unit of data flow, implemented as maps with type information and optional schemas:

```clojure
{:frame/type :audio/raw-input
 :data byte-array
 :timestamp 1234567890}
```

Key frame types include:
- `:audio/input-raw`, `:audio/output-raw` - Raw audio data
- `:transcription`, `:transcription-interim` - Speech-to-text results
- `:llm/text-chunk`, `:llm/tool-call-chunk` - LLM response chunks
- `:system/start`, `:system/stop` - Control signals

### Flow-Based Processing

Processors are connected in graphs using `core.async.flow`, allowing for:
- Concurrent processing of audio/text streams
- Declarative pipeline configuration
- Robust lifecycle management
- Composable and reusable components

## Key File Paths

### Core Framework

- `src/simulflow/frame.clj` - Frame definitions, constructors, and schemas
- `src/simulflow/schema.clj` - Malli schemas for validation and type safety
- `src/simulflow/transport.clj` - Transport layer for audio I/O and serialization
- `src/simulflow/async.clj` - Core.async utilities and virtual thread loops

### Processors

- `src/simulflow/processors/deepgram.clj` - Deepgram speech-to-text integration
- `src/simulflow/processors/elevenlabs.clj` - ElevenLabs text-to-speech integration  
- `src/simulflow/processors/openai.clj` - OpenAI LLM integration
- `src/simulflow/processors/google.clj` - Google Gemini LLM integration
- `src/simulflow/processors/groq.clj` - Groq LLM integration
- `src/simulflow/processors/llm_context_aggregator.clj` - Conversation context management
- `src/simulflow/processors/activity_monitor.clj` - Audio activity detection

### Transport Layer

- `src/simulflow/transport/protocols.clj` - Transport protocols and abstractions
- `src/simulflow/transport/serializers.clj` - Frame serialization for different providers

### Configuration & Utilities

- `src/simulflow/secrets.clj` - API key and secrets management
- `src/simulflow/utils/core.clj` - Core utility functions
- `src/simulflow/utils/audio.clj` - Audio processing utilities
- `src/simulflow/scenario_manager.clj` - Scenario and state management

### Examples & Development

- `examples/src/simulflow_examples/local.clj` - Local microphone/speakers example
- `examples/src/simulflow_examples/twilio_websocket.clj` - Telephony integration example
- `dev/` - Development utilities and REPL helpers

## Dependencies & Architecture

### Core Dependencies

- **Clojure 1.12.0** - Base language
- **core.async 1.9.808-alpha1** - Concurrent processing and flow control
- **Malli 0.19.1** - Schema validation and transformation
- **Hato 1.1.0-SNAPSHOT** - HTTP client and WebSocket support
- **Jsonista 0.3.13** - JSON processing
- **Telemere 1.0.1** - Structured logging
- **clojure-sound 0.3.0** - Audio capture and playback

### Supported AI Providers

**Speech-to-Text (STT):**
- Deepgram (nova-2, nova-2-general, nova-2-meeting models)
- Real-time transcription with punctuation and smart formatting

**Text-to-Speech (TTS):**  
- ElevenLabs (eleven_multilingual_v2, eleven_turbo_v2, eleven_flash_v2)
- Streaming audio generation with voice customization

**Large Language Models (LLM):**
- OpenAI (gpt-4o-mini, gpt-4, gpt-3.5-turbo)
- Google Gemini (gemini-2.0-flash, gemini-2.5-flash)  
- Groq (llama-3.2-3b-preview, llama-3.1-8b-instant, llama-3.3-70b-versatile)
- Function calling and streaming responses supported

## API Usage Patterns

### Basic Flow Creation

```clojure
(ns my-app
  (:require [clojure.core.async.flow :as flow]
            [simulflow.processors.deepgram :as asr]
            [simulflow.processors.openai :as llm]
            [simulflow.transport :as transport]))

(def my-flow
  (flow/create-flow
    {:procs
     {:transport-in {:proc transport/microphone-transport-in
                    :args {:audio-in/sample-rate 16000}}
      :transcriptor {:proc asr/deepgram-processor
                    :args {:transcription/api-key "key"}}
      :llm {:proc llm/openai-llm-process
           :args {:openai/api-key "key"}}}
     :conns
     [[[:transport-in :out] [:transcriptor :in]]
      [[:transcriptor :out] [:llm :in]]]}))

;; Start the flow
(flow/start my-flow)
(flow/resume my-flow)
```

### Transport System

The transport layer supports multiple modalities:

- **Local** - Microphone and speakers using Java Sound API
- **Telephony** - Twilio WebSocket integration with custom serializers
- **WebSocket** - Generic WebSocket transport with pluggable serializers
- **Async** - Core.async channels for programmatic I/O

### Configuration as Data

Flows are configured entirely through data structures:

```clojure
{:procs {:processor-name {:proc processor-fn
                         :args config-map}}
 :conns [[:source :dest] ...]}
```

This enables:
- Hot-reloadable configurations
- Easy testing and debugging
- Runtime pipeline modification
- Declarative composition

## Implementation Patterns

### Pure Function Processors

All processors follow the `core.async.flow` transform pattern:

```clojure
(defn my-processor []
  (flow/process
    {:describe (fn [] {:ins {:in "Input channel"}
                      :outs {:out "Output channel"}})
     :init identity
     :transform (fn [state in msg]
                  [state {:out [(process-message msg)]}])}))
```

### Frame-Based Communication

Inter-processor communication uses typed frames:

```clojure
(defframe audio-input-raw
  "Raw audio input frame"
  {:type :audio/raw-input
   :schema [:map
           [:data [:fn bytes?]]
           [:timestamp :int]]})
```

### Schema Validation

All frames and configurations use Malli schemas for validation:

```clojure
(def AudioEncoding
  (flex-enum #{:pcm-signed :pcm-unsigned :pcm-float :ulaw :alaw}))

(def LLMMessage
  [:map
   [:role [:enum :system :user :assistant :tool]]
   [:content :string]])
```

### Async Patterns

Heavy use of `core.async` for concurrent processing:

```clojure
(vthread-loop []
  (when-let [frame (<! input-chan)]
    (when-let [result (process-frame frame)]
      (>! output-chan result))
    (recur)))
```

## Development Workflow

### REPL-Driven Development

1. Start REPL with `:dev` alias for schema checking
2. Load example flows in `(comment ...)` blocks
3. Use `flow/start`, `flow/resume`, `flow/stop` for lifecycle management
4. Hot-reload processor implementations

### Testing Strategy

- Unit tests for individual processors
- Integration tests for complete flows
- Mock data for AI service testing
- Schema validation testing

### Configuration Management

- API keys stored in `resources/secrets.edn`
- Environment-specific configs in aliases
- Schema-validated configurations

## Extension Points

### Adding New Processors

1. Implement processor function following `flow/process` pattern
2. Define input/output frame schemas
3. Add to processor namespace and require in consumer
4. Configure in flow definition

### Transport Extensions

1. Implement transport protocols in `simulflow.transport.protocols`
2. Create serializers for provider-specific formats
3. Add processor for transport lifecycle management

### AI Provider Integration

1. Follow existing processor patterns in `simulflow.processors.*`
2. Implement provider-specific WebSocket/HTTP clients
3. Map provider responses to standard frame types
4. Handle streaming and error cases

### Frame Type Extensions

1. Define new frame types in `simulflow.frame`
2. Add Malli schemas for validation
3. Update processor transform functions to handle new types

## Future Development

The framework is designed for extension in several areas:

- **Additional AI Providers** - Easy integration through processor pattern
- **New Transport Modes** - WebRTC, SIP, custom protocols
- **Advanced Audio Processing** - VAD, noise reduction, audio effects
- **Multi-modal Support** - Video processing, image analysis
- **Distributed Processing** - Remote processor execution
- **Visual Pipeline Builder** - GUI for flow configuration

The data-driven, functional approach ensures that new features can be added without breaking existing functionality, maintaining the principle of "data first, not methods first" throughout the codebase.
