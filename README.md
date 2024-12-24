# voice-fn - WIP

A Clojure framework for building real-time voice-enabled AI applications. voice-fn handles the orchestration of speech recognition, audio processing, and AI service integration with the elegance of functional programming.

```clojure
(ns my-app.core
  (:require [voice-fn.core :as v]))

;; Start a voice interaction pipeline with just a few lines
(-> (v/create-pipeline)
    (v/add-speech-recognition)
    (v/add-llm :model "your-model")
    (v/start!))
```

## Features

- Real-time speech recognition with configurable backends
- Clean functional interface for audio stream processing
- Seamless integration with popular AI models and services
- Built-in support for conversation state management
- Efficient audio processing with core.async channels
- Hot-reloadable pipeline components
- Extensible architecture with composable transforms

## Why voice-fn?

voice-fn brings Clojure's functional elegance to voice AI applications. Rather than wrestling with complex state management and imperative audio processing, voice-fn lets you compose transformations as pure functions. Audio streams become just another kind of data flow, managed with the same tools you already use for functional programming.

## Status

Early development - seeking contributors! While the core architecture is stable, we're actively expanding supported features and integrations.

## Installation

Add to your project.clj:

```clojure
[voice-fn "0.1.0"]
```

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
