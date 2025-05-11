# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

simulflow is a Clojure framework for building real-time voice AI applications using a data-driven, functional approach. It provides a composable pipeline architecture for processing audio, text, and AI interactions with built-in support for major AI providers.

The framework uses a frame-based architecture built on top of `clojure.core.async.flow`, allowing different processors to communicate via a typed message system.

## Build and Development Commands

### Development Environment

```bash
# Start a REPL
clj -M:dev

# Run with examples
clj -M:with-examples
```

### Testing

```bash
# Run all tests
bb test

# Run with Kaocha directly
clojure -M:test -m kaocha.runner :unit
```

### Build and Deploy

```bash
# Build JAR
clojure -T:build ci

# Install locally
clojure -T:build install

# Deploy to Clojars
clojure -T:build deploy
```

### Code Quality

```bash
# Run clj-kondo linter (ALWAYS run this after editing code)
clj -M:clj-kondo --lint src/
```

## Core Architecture

The core architecture of simulflow is based on:

1. **Frames**: Data structures representing typed messages that flow through the pipeline
   - Each frame has a type (e.g., `:frame.audio/input-raw`, `:frame.transcription/result`)
   - Frames are created using the `defframe` macro which handles validation

2. **Processors**: Components that transform frames
   - Implement the `flow/process` protocol from `core.async.flow`
   - Consume frames of specific types and produce new frames
   - Each processor defines its inputs, outputs, and transformation logic

3. **Flow**: A complete pipeline connecting processors via channels
   - Created with `flow/create-flow` which takes a map of processors and connections
   - The flow can be started, paused, resumed, and stopped
   - Frames flow through the channels between processors

## Important Namespaces

- `simulflow.frame`: Defines the frame concept and creation functions
- `simulflow.transport`: Handles audio I/O (microphone, speakers, network)
- `simulflow.processors.*`: Different processing components:
  - `deepgram.clj`: Speech-to-text transcription
  - `elevenlabs.clj`: Text-to-speech synthesis
  - `openai.clj`: LLM integration with OpenAI
  - `groq.clj`: LLM integration with Groq
  - `llm_context_aggregator.clj`: Manages conversation context
  - `silence_monitor.clj`: Detects inactivity
  - `silero_vad.clj`: Voice activity detection

## Environment Setup

The project uses a `resources/secrets.edn` file for API keys (not checked into git):

```edn
{:deepgram {:api-key "YOUR_DEEPGRAM_API_KEY"}
 :elevenlabs {:api-key "YOUR_ELEVENLABS_API_KEY"
              :voice-id "YOUR_VOICE_ID"}
 :groq {:api-key "YOUR_GROQ_API_KEY"}
 :openai {:new-api-sk "YOUR_OPENAI_API_KEY"}}
```

## Example Usage

A basic flow can be created and started like this:

```clojure
(def local-ai (make-local-flow))

;; Start the flow (paused initially)
(let [{:keys [report-chan error-chan]} (flow/start local-ai)]
  (a/go-loop []
    (when-let [[msg c] (a/alts! [report-chan error-chan])]
      (prn (if (= c error-chan) :error :report) msg)
      (recur))))

;; Resume the flow to begin processing
(flow/resume local-ai)

;; Stop the flow when done
(flow/stop local-ai)
```

See the `examples/src/voice_fn_examples/` directory for complete examples.

## Custom Processors

To create a custom processor:

```clojure
(defn my-custom-processor []
  (flow/process
    (flow/map->step
      {:describe (fn [] {:ins {:in "Input channel description"}
                         :outs {:out "Output channel description"}
                         :params {:param1 "Parameter description"}})
       :init (fn [{:keys [param1]}]
               ;; Initialize processor state
               {:my-state (atom {})})
       :transform (fn [state _ frame]
                    ;; Process frame and produce new frames
                    (if (frame/specific-frame-type? frame)
                      [state {:out [(process-and-create-new-frame frame)]}]
                      [state]))})))
```

## Working with Scenarios

For more complex applications, simulflow provides a scenario manager for creating structured conversational flows:

```clojure
(def scenario
  {:nodes {:start {:system-msg "You are a helpful assistant..."
                  :transitions [{:condition (fn [ctx] (ctx-needs-help? ctx))
                                :target :help}]}
           :help {:system-msg "I'll help you with your problem..."
                 :transitions [{:condition (fn [ctx] true)
                               :target :start}]}}
   :initial-node :start})

(def scenario-manager (scenario-manager/make-scenario-manager scenario))
```

## Debugging Tips

1. Set logging level with `(t/set-min-level! :debug)` for detailed logs
2. Use the debug flag in processors: `{:aggregator/debug? true}`
3. Use the `prn-sink` processor to print frames at specific points in the pipeline
4. For deeper inspection, use the flowstorm debugger with the `:storm` alias
