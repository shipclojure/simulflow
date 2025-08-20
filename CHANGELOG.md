# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## Unreleased

### Added
- Support for Google Gemini llm. See [gemini example flow](./examples/src/simulflow_examples/gemini.clj)
- Support for text based agents. See [text chat example](./examples/src/simulflow_examples/text_chat.clj)
- Support for different sample rates for the audio splitter processor. See [twilio-websocket example](./examples/src/simulflow_examples/twilio_websocket.clj) for demonstration

### Changed
- Moved most of the llm logic from [openai processor](./src/simulflow/processors/openai.clj) to an utils folder to be used by multiple processors like [gemini](./src/simulflow/processors/google.clj)

### Fixed
- **Scenario Manager** Fixed a bug where normal utility tool functions were treated as transition functions that returned a nil transition node
- **Activity Monitor** Fixed a bug where the ping count was not reset when the user said something.

## [0.1.7-alpha] - 2025-08-13

### Added
- Voice Activity Detection protocols that the transport-in processors can use. [See protocol here](./src/simulflow/vad/core.clj)
- [Silero Voice Activity Detection model](./src/simulflow/vad/silero.clj) running through Onnx Runtime
- parameter `:vad/analyser` to all transport-in processor params to pass a VAD analyser like Silero to be ran on each new audio chunk. This is useful for logic that handles AI interruptions and improves turn taking.
- Added malli schema for `audio-output-raw` frames
- Added `simulflow.frame/send` helper function that outputs frames based on their appropriate direction - used now in most processors
- **Twilio Integration**: Twilio serializer with `make-twilio-serializer` for WebSocket communication
- **Audio Resampler Processor**: New processor for real-time sample rate conversion
- **System Frame Router**: New `system-frame-router` processor for handling system message routing in complex flows
- **Interruption Support**: New frame types for bot interruption handling: `control-interrupt-start`, `control-interrupt-stop`, `bot-interrupt`

### Changed
- Made **16kHz signed PCM mono** audio be the default audio that runs through the pipeline. All `audio-input-raw` frames that come through pe pipeline are expected to be this way.
- **POTENTIAL BREAKING** `frame/audio-output-raw` are now expected to contain the sample-rate of the audio. The sample rate will be used for chunking and final output conversion. If you have custom Text to speech generators, you need to update them to use it.
- Changed examples to use silero vad model for VAD instead of relying on Deepgram
- **BREAKING** Processors that outputted `system-frames` (see [frame/system-frames](./src/simulflow/frame.clj) set for a list of system frames) will output them exclusively on `:sys-out` channel and normal frames on `:out` channel.
- **Audio Processing**: Enhanced audio conversion with improved µ-law/PCM support and step-by-step conversion planning

### Fixed
- **Audio Conversion**: Fixed critical bug in PCM 16kHz to Twilio µ-law 8kHz conversion where downsampling must occur before encoding conversion
- **Transport**: Removed duplicate system frame passthrough that was causing duplicate frames

## [0.1.5-alpha] - 2025-07-24

### Added
- Command system to express IO commands from transform function for easier testing - still alfa
- **Realtime out transport**: Configurable buffering duration between audio chunks: `audio.out/sending-interval` (defaults to half of chunk duration)
- **Realtime out transport**: Configurable silence detection threshold: `activity-detection/silence-threshold-ms` (defaults to 4 x chunk duration)

### Fixed
- **Schema Description**: Fixed `schema/->describe-parameters` function to properly handle `:or` clauses, now displays human-readable descriptions like "Type: (set of string or vector of string)" instead of unhelpful "Type: :or"
- **Schema Description**: Added recursive schema type description with `describe-schema-type` helper function that properly handles complex nested types including `:or`, `:set`, `:vector`, and basic types
- **Activity Monitor**: Fixed a bug where the transition function wasn't returning correct state
- **Realtime out transport**: Fixed buffering for realtime transport out

## [0.1.5-alpha] - 2025-07-03

### Added
- Explicit timestamp support for frames with `java.util.Date` (#inst reader macro) and millisecond integers
- Frame creation functions now support optional `opts` parameter for timestamp control
- Utility functions `normalize-timestamp` and `timestamp->date` for timestamp conversion
- **Microphone Transport**: Added pure functions `process-mic-buffer` and `mic-resource-config` for better testability and REPL-driven development
- **Transport Testing**: Comprehensive test suite for transport layer covering microphone transport, audio splitter, and realtime speakers
- **Realtime Speakers Out**: Added comprehensive test coverage including realistic LLM → audio splitter → speakers pipeline integration test simulating end-to-end audio processing flow
- **Realtime Speakers Out**: Added extensive unit tests for describe, init, transition, transform, timer handling, system config, serializer integration, and timing accuracy validation

### Changed
- **BREAKING**: Frame types now use proper `simulflow.frame` namespace (e.g., `:simulflow.frame/user-speech-start`)
- Fixed schema typos in `user-speech-stop` and `bot-speech-stop` frame definitions
- **Microphone Transport**: Refactored `microphone-transport-in` to use multi-arity function pattern (`mic-transport-in-fn`) for better flow integration
- **Audio Splitter**: Refactored `audio-splitter` to use multi-arity function pattern (`audio-splitter-fn`) for better flow integration and consistency with other transport processors

### Improved
- Better developer experience with static analysis support for frame functions
- Enhanced frame validation and error messages
- More idiomatic Clojure code with proper namespaced keywords
- **Activity Monitor**: Refactored core logic into pure `transform` function, making it fully testable and following data-centric functional patterns
- **ElevenLabs TTS**: Extracted transform logic into pure `elevenlabs-tts-transform` function, improving testability and separation of concerns from WebSocket lifecycle management
- **ElevenLabs TTS**: Migrated from classic threads (`flow/futurize`) to virtual threads (`vthread-loop`) for better performance and resource efficiency
- **ElevenLabs TTS**: Extracted WebSocket configuration logic into pure `create-websocket-config` function
- **Microphone Transport**: Enhanced error handling with structured logging and non-blocking channel operations using `offer!` instead of blocking `>!!`
- **Microphone Transport**: Migrated to virtual threads (`vthread-loop`) for better concurrency performance and resource utilization
- **Microphone Transport**: Improved timestamp accuracy by capturing timestamps at audio capture time rather than processing time
- **Microphone Transport**: Added graceful frame dropping when channel is full to prevent system backpressure in real-time audio scenarios
- **Audio Splitter**: Extracted pure functions for audio byte array splitting and chunk size calculation, improving testability and following data-centric design principles
- **Audio Splitter**: Enhanced with comprehensive edge case handling (nil audio, zero chunk size, exact division) and data integrity verification
- **Transport Architecture**: Extracted pure functions for audio buffer processing, resource configuration, and audio splitting, improving testability and following data-centric design principles
- **Realtime Speakers Out**: Following Activity Monitor Pattern, moved business logic from init! background loops to pure transform function for better testability and functional programming alignment
- **Realtime Speakers Out**: Enhanced with timer-based speech detection in transform function, replacing background monitoring loops with explicit timer tick event handling
- **Realtime Speakers Out**: Improved state management with explicit data structures and pure function transformations for speech start/stop detection and audio timing calculations
- **Test Quality**: Added realistic integration testing simulating LLM audio generation → audio splitting → realtime speakers with proper timing validation and data integrity verification


## [0.1.4-alpha] - 2025-04-13

### Changed
- Updated dependencies to latest

### Removed
- Unused dependencies: onnx-runtime + java.data

## [0.1.3-alpha] - 2025-04-13
### Added
- Change frame format from records to maps with specific meta for easier debugging
- Functionality to describe a process parameters with malli schema only
- [Google llm](./src/simulflow/processors/google.clj) support. Example usage: [gemini.clj](./examples/src/simulflow_examples/gemini.clj)
- [Scenario Manager](./src/simulflow/scenario_manager.clj) for handling complex conversation flows
- [Activity Monitor](./src/simulflow/processors/activity_monitor.clj) to ping user or end call when no activity is detected for specific period
- Bot speaking events tracking


## [0.1.2] - 2025-01-30
### Added
- Support for tool use. See [llm-context-aggregator.clj](./src/voice_fn/processors/llm_context_aggregator.clj)
- Twilio transport in support. See `twilio-transport-in` [transport.clj](./src/voice_fn/transport.clj)
- More tests for context aggregation
- Support for dynamic context change
Usecase:
We have an initial prompt and tools to use. We want to change it based on the custom parameters that are inputted throught the twilio websocket.
Example: On the twilio websocket, we can give custom parameters like script-name, overrides like user name, etc.

We can use the config-change frame to do this. And every processor takes what it cares about from it. However, you add very specific functionality to the twilio-in transport. So, what you need to do is add a custom-params->config argument.
``` clojure
:transport-in {:proc transport/twilio-transport-in
               :args {:transport/in-ch in
                      :twilio/handle-event (fn [event]
                                             {:out {:llm/context ".."
                                                    :llm/registered-tools [...]}})}
```



## [0.1.0] - 2025-01-27
### Changed
- Underlying pipeline implementation to use [core.async.flow](https://clojure.github.io/core.async/clojure.core.async.flow.html)` (currently unreleased)

### Removed
- `pipeline.clj` - Removed in favor of `core.async.flow`

[0.1.3-alpha]: https://github.com/ovistoica/simulflow/compare/0.1.2...HEAD
[Unreleased]: https://github.com/ovistoica/simulflow/compare/0.1.3-alpha...HEAD
[0.1.1]: https://github.com/ovistoica/simulflow/compare/0.1.0...0.1.1
