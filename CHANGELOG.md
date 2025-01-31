# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [0.1.2] - 2024-01-30
### Added
- Support for tool use. See [llm-context-aggregator.clj](./src/voice_fn/processors/llm_context_aggregator.clj)
- Twilio transport in support. See `twilio-transport-in` [transport.clj](./src/voice_fn/transport.clj)
- More tests for context aggregation


## [0.1.0] - 2025-01-27
### Changed
- Underlying pipeline implementation to use [core.async.flow](https://clojure.github.io/core.async/clojure.core.async.flow.html)` (currently unreleased)

### Removed
- `pipeline.clj` - Removed in favor of `core.async.flow`

[Unreleased]: https://github.com/ovistoica/voice-fn/compare/0.1.1...HEAD
[0.1.1]: https://github.com/ovistoica/voice-fn/compare/0.1.0...0.1.1
