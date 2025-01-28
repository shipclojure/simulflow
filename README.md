# voice-fn - Real-time Voice AI Pipeline Framework

## Table of Contents


1.  [Core Features](#org9f6c898)
2.  [Quick Start: Twilio WebSocket Example](#org71c1ebd)
3.  [Supported Providers](#orga92dd94)
    1.  [Text-to-Speech (TTS)](#orgb60103e)
    2.  [Speech-to-Text (STT)](#orgf138736)
    3.  [Large Language Models (LLM)](#org532c7b9)
4.  [Key Concepts](#org28a4b3f)
    1.  [Flows](#org40af940)
    2.  [Frames](#org1759685)
    3.  [Processes](#orgbd1d5f8)
5.  [Adding Custom Processes](#orgf87c620)
6.  [Built With](#orgaf805b6)
7.  [License](#org5082853)



`voice-fn` is a Clojure framework for building real-time voice AI applications using a data-driven, functional approach. Built on top of `clojure.core.async.flow`, it provides a composable pipeline architecture for processing audio, text, and AI interactions with built-in support for major AI providers.


<a id="org9f6c898"></a>

## Core Features

-   **Flow-Based Architecture:** Built on `core.async.flow` for robust concurrent processing
-   **Data-First Design:** Define AI pipelines as data structures for easy configuration and modification
-   **Streaming Architecture:** Efficient real-time audio and text processing
-   **Extensible Processors:** Simple protocol-based system for adding new processing components
-   **Flexible Frame System:** Type-safe message passing between pipeline components
-   **Built-in Services:** Ready-to-use integrations with major AI providers


<a id="org71c1ebd"></a>

## Quick Start: Twilio WebSocket Example
```clojure
    (ns example
      (:require [clojure.core.async :as a]
                [clojure.core.async.flow :as flow]
                [voice-fn.processors.deepgram :as asr]
                [voice-fn.processors.openai :as llm]
                [voice-fn.processors.elevenlabs :as tts]
                [voice-fn.transport :as transport]))

    (defn make-twilio-flow [in out]
      {:procs
       {:transport-in {:proc transport/twilio-transport-in
                       :args {:transport/in-ch in}}
        :deepgram {:proc asr/deepgram-processor
                   :args {:transcription/api-key "DEEPGRAM_KEY"
                          :transcription/interim-results? true
                          :transcription/model :nova-2}}
        :llm {:proc llm/openai-llm-process
              :args {:openai/api-key "OPENAI_KEY"
                     :llm/model "gpt-4"}}
        :tts {:proc tts/elevenlabs-tts-process
              :args {:elevenlabs/api-key "ELEVENLABS_KEY"
                     :elevenlabs/voice-id "VOICE_ID"}}
        :transport-out {:proc transport/realtime-transport-out-processor
                        :args {:transport/out-chan out}}}

       :conns
       [[[:transport-in :out] [:deepgram :in]]
        [[:deepgram :out] [:llm :in]]
        [[:llm :out] [:tts :in]]
        [[:tts :out] [:transport-out :in]]]})

    (defn start-flow []
      (let [in (a/chan 1024)
            out (a/chan 1024)
            flow (flow/create-flow (make-twilio-flow in out))]
        (flow/start flow)
        {:in in :out out :flow flow}))

    (defn stop-flow [{:keys [flow in out]}]
      (flow/stop flow)
      (a/close! in)
      (a/close! out))
```


<a id="orga92dd94"></a>

## Supported Providers


<a id="orgb60103e"></a>

### Text-to-Speech (TTS)

-   **ElevenLabs**
    -   Models: `eleven_multilingual_v2`, `eleven_turbo_v2`, `eleven_flash_v2` and more.
    -   Features: Real-time streaming, multiple voices, multilingual support


<a id="orgf138736"></a>

### Speech-to-Text (STT)

-   **Deepgram**
    -   Models: `nova-2`, `nova-2-general`, `nova-2-meeting` and more.
    -   Features: Real-time transcription, punctuation, smart formatting


<a id="org532c7b9"></a>

### Large Language Models (LLM)

-   **OpenAI**
    -   Models: `gpt-4o-mini`(fastest, cheapest),  `gpt-4`, `gpt-3.5-turbo` and more
    -   Features: Function calling, streaming responses


<a id="org28a4b3f"></a>

## Key Concepts


<a id="org40af940"></a>

### Flows

The core building block of voice-fn pipelines:

-   Composed of processes connected by channels
-   Processes can be:
    -   Input/output handlers
    -   AI service integrations
    -   Data transformers
-   Managed by `core.async.flow` for lifecycle control


<a id="org1759685"></a>

### Frames

The basic unit of data flow, representing typed messages like:

-   `:audio/input-raw` - Raw audio data
-   `:transcription/result` - Transcribed text
-   `:llm/text-chunk` - LLM response chunks
-   `:system/start`, `:system/stop` - Control signals

See [frame.clj](./core/src/voice_fn/frame.clj) for all possible frames.


<a id="orgbd1d5f8"></a>

### Processes

Components that transform frames:

-   Define input/output requirements
-   Can maintain state
-   Use core.async for async processing
-   Implement the `flow/process` protocol


<a id="orgf87c620"></a>

## Adding Custom Processes

    (defn custom-processor []
      (flow/process
        {:describe (fn [] {:ins {:in "Input channel"}
                           :outs {:out "Output channel"}})
         :init (fn [args] {:state args})
         :transform (fn [state in msg]
                      [state {:out [(process-message msg)]}])}))


<a id="orgaf805b6"></a>

## Built With

-   [core.async](<https://github.com/clojure/core.async>) - Concurrent processing
-   [core.async.flow](<https://clojure.github.io/core.async/#clojure.core.async.flow>) - Flow control
-   [Hato](<https://github.com/gnarroway/hato>) - WebSocket support
-   [Malli](<https://github.com/metosin/malli>) - Schema validation


<a id="org5082853"></a>

## License

MIT
