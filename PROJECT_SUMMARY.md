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
{:frame/type :simulflow.frame/audio-input-raw
 :data byte-array
 :timestamp 1234567890}
```

Key frame types include:
- `:simulflow.frame/audio-input-raw`, `:simulflow.frame/audio-output-raw` - Raw audio data
- `:simulflow.frame/transcription-result`, `:simulflow.frame/transcription-interim` - Speech-to-text results
- `:simulflow.frame/llm-text-chunk`, `:simulflow.frame/llm-tool-call-chunk` - LLM response chunks
- `:simulflow.frame/system-start`, `:simulflow.frame/system-stop` - Control signals

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

The transport layer supports multiple modalities with robust error handling:

- **Local** - Microphone and speakers using Java Sound API with virtual threads
- **Telephony** - Twilio WebSocket integration with custom serializers
- **WebSocket** - Generic WebSocket transport with pluggable serializers
- **Async** - Core.async channels for programmatic I/O

#### Virtual Thread Integration

Transport layers use virtual threads for better concurrency:

```clojure
(vthread-loop []
  (when @running?
    (try
      (let [bytes-read (read! line buffer 0 buffer-size)]
        (when-let [processed-data (and @running? (process-mic-buffer buffer bytes-read))]
          ;; Non-blocking channel operations prevent backpressure
          (when-not (a/offer! mic-in-ch processed-data)
            (t/log! :warn "Channel full, dropping frame"))))
      (catch Exception e
        (t/log! {:level :error :id :microphone-transport :error e} "Audio read error")
        ;; Thread.sleep is safe in virtual threads
        (Thread/sleep 100)))
    (recur)))
```

#### Key Benefits:
- **Non-blocking Channel Operations**: Uses `offer!` instead of blocking `>!!` to prevent system backpressure
- **Graceful Degradation**: Drops frames when system can't keep up rather than blocking
- **Accurate Timestamps**: Captures timestamps at data capture time, not processing time
- **Robust Error Handling**: Structured logging with automatic retry and backoff

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

Heavy use of `core.async` for concurrent processing with virtual threads:

```clojure
(vthread-loop []
  (when-let [frame (<! input-chan)]
    (when-let [result (process-frame frame)]
      (>! output-chan result))
    (recur)))
```

### Data Centric Async Processor Pattern

A key architectural pattern that moves business logic from `init!` background loops into pure `transform` functions that map state over time:

```clojure
;; Pure transform function handles all business logic by mapping state over time
(defn realtime-speakers-out-transform [state input-port frame]
  (cond
    ;; Handle audio frames with pure business logic - state transformation
    (and (= input-port :in) (frame/audio-output-raw? frame))
    (process-realtime-out-audio-frame state frame serializer (u/mono-time))

    ;; Handle timer ticks for speech monitoring - state mapping over time
    (and (= input-port :timer-out) (:timer/tick frame))
    (let [silence-duration (- (:timer/timestamp frame) (::last-audio-time state))
          should-emit-stop? (and (::speaking? state)
                                 (> silence-duration (::silence-threshold state)))]
      [(if should-emit-stop? (assoc state ::speaking? false) state)
       {:out (if should-emit-stop? [(frame/bot-speech-stop true)] [])}])))

;; Minimal init! - only resource setup, no business logic
(defn realtime-speakers-out-init! [params]
  (let [audio-line (open-line! :source (audio-format params))
        timer-ch (a/chan)]
    ;; Minimal timer process - just sends ticks for state mapping
    (vthread-loop []
      (a/<!! (a/timeout 1000))
      (a/>!! timer-ch {:timer/tick true :timer/timestamp (u/mono-time)})
      (recur))

    {::flow/in-ports {:timer-out timer-ch}
     ::speaking? false
     ::last-audio-time 0
     ::audio-line audio-line}))
```

**Core Principle:**
The transform function becomes a **state mapping function over time** - it takes the current state and input events, applies pure business logic, and returns the new state and outputs. This creates a clear, testable data flow where state transitions are explicit and traceable.

**Benefits:**
- Transform becomes pure and testable (state mapping is deterministic)
- Business logic centralized in data transformations
- Side effects isolated to minimal background processes
- State transitions are clear and debuggable
- Follows functional programming principles with explicit state over time

### Pure Function Extraction Pattern

Complex processors are refactored to separate pure data transformation from side effects:

```clojure
;; Pure function for data processing
(defn process-mic-buffer [buffer bytes-read]
  (when (pos? bytes-read)
    {:audio-data (Arrays/copyOfRange buffer 0 bytes-read)
     :timestamp (java.util.Date.)}))

;; Pure function for configuration
(defn mic-resource-config [{:keys [sample-rate channels buffer-size]}]
  {:buffer-size (or buffer-size (frame-buffer-size sample-rate))
   :audio-format (audio-format sample-rate channels)
   :channel-size 1024})

;; Pure function for audio processing
(defn process-realtime-out-audio-frame [state frame serializer current-time]
  (let [updated-state (-> state
                          (assoc ::speaking? true)
                          (assoc ::last-audio-time current-time)
                          (assoc ::next-send-time (+ current-time (::sending-interval state))))
        events (if (not (::speaking? state)) [(frame/bot-speech-start true)] [])
        audio-command {:command :write-audio
                       :data (:frame/data (if serializer (tp/serialize-frame serializer frame) frame))
                       :delay-until (::next-send-time updated-state)}]
    [updated-state {:out events :audio-write [audio-command]}]))

;; Used in processor with side effects
(vthread-loop []
  (when @running?
    (try
      (let [bytes-read (read! line buffer 0 buffer-size)]
        (when-let [processed-data (and @running? (process-mic-buffer buffer bytes-read))]
          (when-not (a/offer! mic-in-ch processed-data)
            (t/log! :warn "Channel full, dropping frame"))))
      (catch Exception e
        (t/log! :error "Audio read error" {:error e})
        (Thread/sleep 100)))
    (recur)))
```

### Multi-arity Function Pattern

Processors use multi-arity functions for better flow integration:

```clojure
(defn processor-fn
  ([] {:outs {:out "Description"} :params {...}})  ; 0-arity: describe
  ([config] {...})                                  ; 1-arity: init
  ([state transition] {...})                        ; 2-arity: transition
  ([state input-port data] [state {...}]))          ; 3-arity: transform
```

## Development Workflow

### REPL-Driven Development

1. Start REPL with `:dev` alias for schema checking
2. Load example flows in `(comment ...)` blocks
3. Use `flow/start`, `flow/resume`, `flow/stop` for lifecycle management
4. Hot-reload processor implementations
5. **Test pure functions interactively** - Extracted pure functions enable instant testing

#### Interactive Testing Examples:

```clojure
;; Test configuration with different sample rates
(mic-resource-config {:sample-rate 44100 :channels 2})
;; => {:buffer-size 882, :audio-format #<AudioFormat>, :channel-size 1024}

;; Test buffer processing with various inputs
(process-mic-buffer (byte-array [1 2 3 4 5]) 3)
;; => {:audio-data [1 2 3], :timestamp #inst "2025-07-02T..."}

(process-mic-buffer (byte-array [1 2 3]) 0)
;; => nil

;; Test multi-arity processor functions
(mic-transport-in-fn)  ; 0-arity: get description
(mic-transport-in-fn {} :in {:audio-data (byte-array [1 2]) :timestamp (Date.)})  ; 3-arity: transform
```

This approach enables rapid iteration and verification of component behavior without complex setup.

### Testing Strategy

- **Pure Function Testing** - Extracted pure functions are easily testable in isolation and REPL
- **Multi-arity Function Testing** - Test individual processor arities (describe, init, transform)
- **Property-based Testing** - Validate behavior across parameter ranges using `doseq`
- **Integration Tests** - Complete flow testing with mock data and realistic pipeline scenarios
- **Performance Testing** - Large buffer handling and memory isolation validation
- **Edge Case Testing** - Boundary conditions and error scenario validation
- **Schema Validation Testing** - Frame and configuration validation
- **Realistic Pipeline Testing** - End-to-end integration tests simulating LLM → audio splitter → speakers

#### Comprehensive Test Coverage Example

Transport layer testing demonstrates extensive coverage patterns:

```clojure
;; Pure function testing
(deftest test-process-mic-buffer
  (testing "processes valid audio data"
    (let [buffer (byte-array [1 2 3 4 5])
          result (process-mic-buffer buffer 3)]
      (is (= [1 2 3] (vec (:audio-data result))))
      (is (instance? Date (:timestamp result)))))

  (testing "returns nil for zero bytes"
    (is (nil? (process-mic-buffer (byte-array [1 2 3]) 0)))))

;; Property-based testing across parameter ranges
(testing "mic-resource-config invariants"
  (doseq [sample-rate [8000 16000 44100 48000]
          channels [1 2]]
    (let [config (mic-resource-config {:sample-rate sample-rate :channels channels})]
      (is (pos? (:buffer-size config)))
      (is (= 1024 (:channel-size config))))))

;; Realistic integration testing
(deftest test-realistic-llm-audio-pipeline-with-timing
  (testing "LLM generates large audio frame → audio splitter → realtime speakers"
    (let [;; Simulate 200ms of audio (10 chunks of 20ms each)
          large-audio-data (byte-array 6400 (byte 42))
          llm-audio-frame (frame/audio-output-raw large-audio-data)

          ;; Audio splitter processes into chunks
          splitter-config {:audio.out/sample-rate 16000
                          :audio.out/sample-size-bits 16
                          :audio.out/channels 1
                          :audio.out/duration-ms 20}
          splitter-state (sut/audio-splitter-fn splitter-config)
          [_ splitter-output] (sut/audio-splitter-fn splitter-state :in llm-audio-frame)
          audio-chunks (:out splitter-output)]

      ;; Verify proper chunking
      (is (= 10 (count audio-chunks)))
      (is (every? #(= 640 (count (:frame/data %))) audio-chunks))

      ;; Process through realtime speakers with timing simulation
      (let [speakers-state {...}
            results (process-chunks-with-timing audio-chunks speakers-state)]
        ;; Verify bot speech events, timing accuracy, data integrity
        (is (frame/bot-speech-start? (first-event results)))
        (is (accurate-timing? results))
        (is (data-integrity-preserved? results))))))

;; Data Centric Async Processor Pattern testing
(deftest test-realtime-speakers-out-timer-handling
  (testing "timer tick when speaking and silence threshold exceeded"
    (let [state {::sut/speaking? true
                 ::sut/last-audio-time 1000
                 ::sut/silence-threshold 200}
          timer-frame {:timer/tick true :timer/timestamp 1300}
          [new-state output] (sut/realtime-speakers-out-transform state :timer-out timer-frame)]

      (is (false? (::sut/speaking? new-state)))
      (is (= 1 (count (:out output))))
      (is (frame/bot-speech-stop? (first (:out output)))))))
```

#### Test Coverage Statistics

Current test coverage demonstrates comprehensive validation:
- **Transport Layer**: 24 tests, 590 assertions covering microphone transport, audio splitter, and realtime speakers
- **Frame System**: 2150+ assertions covering frame creation, validation, and type safety
- **Activity Monitor**: Complete pure function testing with mock data
- **Integration Tests**: Realistic pipeline scenarios with timing validation

#### Bot Speech Events Testing

Tests validate that `bot-speech-stop` events are properly generated by timer tick processing:

```clojure
(testing "bot-speech-stop generation"
  ;; Verify that speech stop events come from timer-based silence detection
  ;; using Data Centric Async Processor Pattern (state mapping over time)
  (let [silence-duration (- timer-timestamp last-audio-time)]
    (when (> silence-duration silence-threshold)
      (is (frame/bot-speech-stop? generated-event)))))
```

### Configuration Management

- API keys stored in `resources/secrets.edn`
- Environment-specific configs in aliases
- Schema-validated configurations

## Recent Architectural Improvements

### Data Centric Async Processor Pattern Implementation

The framework has adopted the **Data Centric Async Processor Pattern** as a key architectural improvement, demonstrated in the `realtime-speakers-out` processor:

**Before (Background Loops):**
```clojure
;; init! contained complex business logic in background threads
(defn init! [params]
  ;; ... setup ...
  (flow/futurize  ; Background loop with side effects
    #(loop []
       (let [silence-duration (- (u/mono-time) @last-audio-time)]
         (when (and @speaking? (> silence-duration threshold))
           (reset! speaking? false)
           (a/>!! events-ch (frame/bot-speech-stop true))))
       (recur))))
```

**After (Pure Transform State Mapping):**
```clojure
;; init! is minimal - only resource setup
(defn init! [params]
  (let [timer-ch (a/chan)]
    (vthread-loop []  ; Minimal timer - just sends ticks for state mapping
      (a/<!! (a/timeout 1000))
      (a/>!! timer-ch {:timer/tick true :timer/timestamp (u/mono-time)})
      (recur))
    {::flow/in-ports {:timer-out timer-ch}
     ::speaking? false}))

;; transform handles ALL business logic as state mapping over time
(defn transform [state input-port frame]
  (cond
    ;; Timer-based speech detection (pure state mapping)
    (and (= input-port :timer-out) (:timer/tick frame))
    (let [silence-duration (- (:timer/timestamp frame) (::last-audio-time state))
          should-stop? (and (::speaking? state) (> silence-duration (::silence-threshold state)))]
      [(if should-stop? (assoc state ::speaking? false) state)  ; State mapping
       {:out (if should-stop? [(frame/bot-speech-stop true)] [])}])))  ; Output generation
```

### Key Discoveries

#### Bot Speech Event Generation
`bot-speech-stop` events are generated by **timer tick processing** in the transform function when silence duration exceeds the threshold. This demonstrates the Data Centric Async Processor Pattern in action:

- **Timer Process**: Sends periodic tick events with timestamps (minimal side effect)
- **Transform Function**: Pure state mapping logic compares current time vs last audio time
- **Event Generation**: Emits `bot-speech-stop` when silence threshold exceeded (state-driven output)
- **State Mapping**: Each transform call maps `state(t) -> state(t+1)` based on input events

#### Timing Behavior in Testing
Realistic integration testing requires **simulated chunk arrival timing**, not instant processing:

```clojure
;; Simulate realistic timing between chunks
(reduce (fn [state [chunk-index chunk]]
          (when (> chunk-index 0)
            (Thread/sleep 5))  ; Simulate processing delay
          (let [[new-state output] (transform state :in chunk)]
            ;; ... verify timing behavior ...
            new-state))
        initial-state
        (map-indexed vector audio-chunks))
```

#### Serializer Behavior
Serializers transform the entire frame but only the `:frame/data` portion is used in audio write commands:

```clojure
(let [audio-frame (if serializer
                    (tp/serialize-frame serializer frame)  ; Transform entire frame
                    frame)
      audio-command {:command :write-audio
                     :data (:frame/data audio-frame)}]      ; Extract only data
  ...)
```

### V2 to Standard Migration

The framework underwent a significant migration where v2 implementations became the standard:
- `process-audio-frame-v2` → `process-realtime-out-audio-frame`
- v2 processors with pure transforms became the main implementations
- Demonstrates evolution toward functional programming principles

## Testing Excellence

### Comprehensive Coverage

The project maintains **high-quality, comprehensive test coverage**:

- **24 tests, 590 assertions** for transport layer alone
- **2150+ assertions** for frame system validation
- **Realistic integration tests** simulating complete LLM → audio processing pipelines
- **Pure function testing** enabling instant REPL validation
- **Edge case coverage** including boundary conditions and error scenarios

### Testing Philosophy

Testing follows functional programming principles:
- **Pure functions** are easily testable in isolation
- **Mock timing** for deterministic behavior
- **Property-based testing** across parameter ranges
- **Integration scenarios** with realistic data flows
- **Resource management** validation for cleanup

This comprehensive testing approach ensures reliability and maintainability while supporting the data-centric, functional architecture.

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
