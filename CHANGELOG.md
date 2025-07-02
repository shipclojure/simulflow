# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## Unreleased
### Added
- Explicit timestamp support for frames with `java.util.Date` (#inst reader macro) and millisecond integers
- Frame creation functions now support optional `opts` parameter for timestamp control
- Utility functions `normalize-timestamp` and `timestamp->date` for timestamp conversion
- Comprehensive test suite for `simulflow.frame` namespace using `clojure.test` (2150+ assertions)
- Comprehensive test suite for `simulflow.processors.activity-monitor` with pure function testing
- **Microphone Transport**: Added pure functions `process-mic-buffer` and `mic-resource-config` for better testability and REPL-driven development
- **Transport Testing**: Comprehensive test suite for microphone transport (149 assertions) covering pure functions, multi-arity functions, property-based testing, performance, and edge cases

### Changed
- **BREAKING**: Frame types now use proper `simulflow.frame` namespace (e.g., `:simulflow.frame/user-speech-start`)
- Frame system is now completely pure when timestamps are specified explicitly
- Updated clj-kondo hook for `defframe` to support multi-arity functions with timestamp options
- Fixed schema typos in `user-speech-stop` and `bot-speech-stop` frame definitions
- **Microphone Transport**: Refactored `microphone-transport-in` to use multi-arity function pattern (`mic-transport-in-fn`) for better flow integration

### Improved
- Better developer experience with static analysis support for frame functions
- Enhanced frame validation and error messages
- More idiomatic Clojure code with proper namespaced keywords
- **Activity Monitor**: Refactored core logic into pure `transform` function, making it fully testable and following data-centric functional patterns
- Activity monitor now uses separate pure functions (`speaking?`, `transform`) that can be easily unit tested without async complexity
- **ElevenLabs TTS**: Extracted transform logic into pure `tts-transform` function, improving testability and separation of concerns from WebSocket lifecycle management
- **ElevenLabs TTS**: Migrated from classic threads (`flow/futurize`) to virtual threads (`vthread-loop`) for better performance and resource efficiency
- **Microphone Transport**: Enhanced error handling with structured logging and non-blocking channel operations using `offer!` instead of blocking `>!!`
- **Microphone Transport**: Migrated to virtual threads (`vthread-loop`) for better concurrency performance and resource utilization
- **Microphone Transport**: Improved timestamp accuracy by capturing timestamps at audio capture time rather than processing time
- **Microphone Transport**: Added graceful frame dropping when channel is full to prevent system backpressure in real-time audio scenarios
- **Transport Architecture**: Extracted pure functions for audio buffer processing and resource configuration, improving testability and following data-centric design principles

- Updated dependencies to latest

## [0.1.4-alpha] - 2025-04-13
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
