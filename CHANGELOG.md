# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [0.1.3-alpha]
### Added
- Scenario Manager for handling complex conversation flows
- Silence detection with automatic inactivity prompts
- Bot speaking events tracking
- End call prompts when no activity is detected

## [0.1.2] - 2024-01-30
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

[0.1.3-alpha]: https://github.com/ovistoica/voice-fn/compare/0.1.2...HEAD
[Unreleased]: https://github.com/ovistoica/voice-fn/compare/0.1.3-alpha...HEAD
[0.1.1]: https://github.com/ovistoica/voice-fn/compare/0.1.0...0.1.1
