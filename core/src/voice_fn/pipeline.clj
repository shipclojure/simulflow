(ns voice-fn.pipeline
  (:require
   [clojure.core.async :as a :refer [chan go-loop]]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.schema :as schema]
   [voice-fn.secrets :refer [secret]]))

(defmulti processor-schema
  "Returns the malli schema for a processor type's configuration"
  {:arglists '([processor-type])}
  (fn [processor-type] processor-type))

(defmethod processor-schema :default
  [_]
  :any)

(defmulti accepted-frames
  "Returns set of frame types this processor accepts. Each processor must
  implement this method so the pipeline can know what frames to subscribe it
  to."
  {:arglists '([processor-type])}
  (fn [processor-type] processor-type))

(defmethod accepted-frames :default
  [type]
  (throw (ex-info (str "Processor " type " must declare accepted frame types")
                  {:type type
                   :cause :processor.error/no-accepted-frames})))

(defmulti make-processor-config
  "Create the configuration for the processor based on the pipeline
  configuration. Used when the final configuration for a processor requires
  information from the global pipeline configuration. ex: audio-in encoding,
  pipeline language, etc.

  - type - the processor type

  - pipeline-config - the global config of the pipeline. It contains config such
  as the input audio-encoding, pipeline language, input and output channels and
  more. See `voice-fn.pipeline/PipelineConfigSchema`.

  - processor-config - the config of the processor as specified in the list of
  processors from the pipeline"
  {:arglists '([type pipeline-config processor-config])}
  (fn [type _pipeline-config _processor-config]
    type))

(defmethod make-processor-config :default
  [type _ processor-config]
  (m/decode (processor-schema type) processor-config mt/default-value-transformer))

(defmulti process-frame
  "Process a frame from the pipeline.
  - processor-type - type of processor: `:transport/local-audio` | `:transcription/deepgram`
  - pipeline - atom containing the state of the pipeline.
  - config - pipeline config
  - frame - the frame to be processed by the processor"
  {:arglists '([processor-type pipeline config frame])}
  (fn [processor-type _state _config _frame]
    processor-type))

(def PipelineConfigSchema
  [:map
   [:audio-in/sample-rate {:default 16000} schema/SampleRate]
   [:audio-in/channels {:default 1} schema/AudioChannels]
   [:audio-in/encoding {:default :pcm-signed} schema/AudioEncoding]
   [:audio-in/sample-size-bits {:default 16} schema/SampleSizeBits]
   [:audio-out/sample-rate {:default 16000} schema/SampleRate]
   [:audio-out/channels {:default 1} schema/AudioChannels]
   [:audio-out/encoding {:default :pcm-signed} schema/AudioEncoding]
   [:audio-out/sample-size-bits {:default 16} schema/SampleSizeBits]
   [:pipeline/language schema/Language]
   [:pipeline/supports-interrupt? {:default false} :boolean]
   [:llm/context schema/LLMContext]
   [:transport/in-ch schema/Channel]
   [:transport/out-ch schema/Channel]])

(defn supports-interrupt?
  [pipeline]
  (get-in pipeline [:pipeline/config :pipeline/supports-interrupt?]))

(defn validate-pipeline
  "Validates the pipeline configuration and all processor configs.
   Returns a map with :valid? boolean and :errors containing any validation errors.

   Example return for valid config:
   {:valid? true}

   Example return for invalid config:
   {:valid? false
    :errors {:pipeline {...}           ;; Pipeline config errors
             :processors [{:type :some/processor
                          :errors {...}}]}} ;; Processor specific errors"
  [{pipeline-config :pipeline/config
    processors :pipeline/processors}]
  (let [;; Validate main pipeline config
        pipeline-valid? (m/validate PipelineConfigSchema pipeline-config)
        pipeline-errors (when-not pipeline-valid?
                          (me/humanize (m/explain PipelineConfigSchema pipeline-config)))

        ;; Validate each processor's config
        processor-results
        (for [{:processor/keys [type config]} processors]
          (let [schema (processor-schema type)
                processor-config (make-processor-config type pipeline-config config)
                processor-valid? (m/validate schema processor-config)
                processor-errors (when-not processor-valid?
                                   (me/humanize (m/explain schema processor-config)))]
            {:type type
             :valid? processor-valid?
             :errors processor-errors}))

        ;; Check if any processors are invalid
        invalid-processors (filter (comp not :valid?) processor-results)

        ;; Combine all validation results
        all-valid? (and pipeline-valid?
                        (empty? invalid-processors))]

    (cond-> {:valid? all-valid?}

      ;; Add pipeline errors if any
      (not pipeline-valid?)
      (assoc-in [:errors :pipeline] pipeline-errors)

      ;; Add processor errors if any
      (seq invalid-processors)
      (assoc-in [:errors :processors]
                (keep #(when-not (:valid? %)
                         {:type (:type %)
                          :errors (:errors %)})
                      processor-results)))))

(comment
  (def in (a/chan 1))
  (def out (a/chan 1))
  (def test-pipeline-config {:pipeline/config {:audio-in/sample-rate 8000
                                               :audio-in/encoding :ulaw
                                               :audio-in/channels 1
                                               :audio-in/sample-size-bits 8
                                               :audio-out/sample-rate 8000
                                               :audio-out/encoding :ulaw
                                               :audio-out/sample-size-bits 8
                                               :audio-out/channels 1
                                               :pipeline/language :ro
                                               :llm/context [{:role "system" :content  "Ești un agent vocal care funcționează prin telefon. Răspunde doar în limba română și fii succint. Inputul pe care îl primești vine dintr-un sistem de speech to text (transcription) care nu este intotdeauna eficient și poate trimite text neclar. Cere clarificări când nu ești sigur pe ce a spus omul."}]
                                               :transport/in-ch in
                                               :transport/out-ch out}
                             :pipeline/processors
                             [{:processor/type :transport/twilio-input}
                              {:processor/type :transcription/deepgram
                               :processor/config {:transcription/api-key (secret [:deepgram :api-key])
                                                  :transcription/interim-results? true
                                                  :transcription/punctuate? false
                                                  :transcription/vad-events? true
                                                  :transcription/smart-format? true
                                                  :transcription/model :nova-2}}
                              {:processor/type :llm/context-aggregator}
                              {:processor/type :llm/openai
                               :processor/config {:llm/model "gpt-4o-mini"
                                                  :openai/api-key (secret [:openai :new-api-sk])}}
                              {:processor/type :log/text-input
                               :processor/config {}}
                              {:processor/type :llm/sentence-assembler
                               :processor/config {:sentence/end-matcher #"[.?!;:]"}}
                              {:processor/type :tts/elevenlabs
                               :processor/config {:elevenlabs/api-key (secret [:elevenlabs :api-key])
                                                  :elevenlabs/model-id "eleven_flash_v2_5"
                                                  :elevenlabs/voice-id "7sJPxFeMXAVWZloGIqg2"
                                                  :voice/stability 0.5
                                                  :voice/similarity-boost 0.8
                                                  :voice/use-speaker-boost? true}}

                              {:processor/type :transport/async-output
                               :generates/frames #{}}]})

  (validate-pipeline test-pipeline-config)

  (make-processor-config :transcription/deepgram {:audio-in/sample-rate 8000
                                                  :audio-in/encoding :ulaw
                                                  :audio-in/channels 1
                                                  :audio-in/sample-size-bits 8
                                                  :audio-out/sample-rate 8000
                                                  :audio-out/encoding :ulaw
                                                  :audio-out/sample-size-bits 8
                                                  :audio-out/channels 1
                                                  :pipeline/language :ro}
                         {:transcription/api-key (secret [:deepgram :api-key])
                          :transcription/interim-results? true
                          :transcription/punctuate? false
                          :transcription/vad-events? true
                          :transcription/smart-format? true
                          :transcription/model :nova-2})

  ,)

(defn enrich-processor
  [pipeline-config processor]
  (assoc-in processor [:processor/config] (make-processor-config (:processor/type processor) pipeline-config (:processor/config processor))))

(defn enrich-processors
  "Add pipeline configuration to each processor config based on `make-processor-config`"
  [pipeline]
  (merge pipeline
         {:pipeline/processors (mapv (partial enrich-processor (:pipeline/config pipeline)) (:pipeline/processors pipeline))}))

(defn send-frame!
  "Sends a frame to the appropriate channel based on its type"
  [pipeline frame]
  (if (frame/system-frame? frame)
    (a/put! (:pipeline/system-ch @pipeline) frame)
    (a/put! (:pipeline/main-ch @pipeline) frame)))

;; Pipeline creation logic here
(defn create-pipeline
  "Creates a new pipeline from the provided configuration.

   Throws ExceptionInfo with :type :invalid-pipeline-config when the configuration
   is invalid. The exception data will contain :errors with detailed validation
   information.

   Returns an atom containing the initialized pipeline state."
  [pipeline-config]
  (let [validation-result (validate-pipeline pipeline-config)]
    (if (:valid? validation-result)
      (let [main-ch (chan 1024)
            system-ch (chan 1024) ;; High priority channel for system frames
            main-pub (a/pub main-ch :frame/type)
            system-pub (a/pub system-ch :frame/type)
            pipeline (atom (merge {:pipeline/main-ch main-ch
                                   :pipeline/system-ch system-ch
                                   :pipeline/main-pub main-pub}
                                  (enrich-processors pipeline-config)))]
        ;; Start each processor
        (doseq [{:processor/keys [type]} (:pipeline/processors pipeline-config)]
          (let [afs (accepted-frames type)
                processor-ch (chan 1024)
                processor-system-ch (chan 1024)]
            ;; Tap into main channel, filtering for accepted frame types
            (doseq [frame-type afs]
              (a/sub main-pub frame-type processor-ch)
              ;; system frames that take prioriy over other frames
              (a/sub system-pub frame-type processor-system-ch))
            (swap! pipeline assoc-in [type] {:processor/in-ch processor-ch
                                             :processor/system-ch processor-system-ch})))
        pipeline)
      ;; Throw detailed validation error
      (throw (ex-info "Invalid pipeline configuration"
                      {:type :pipeline/invalid-configuration
                       :errors (:errors validation-result)})))))

(defn start-pipeline!
  [pipeline]
  ;; Start each processor
  (doseq [{:processor/keys [type] :as processor} (:pipeline/processors @pipeline)]
    (go-loop []
      ;; Read from both processor system channel and processor in
      ;; channel. system channel takes priority
      (when-let [[frame] (a/alts! [(get-in @pipeline [type :processor/system-ch])
                                   (get-in @pipeline [type :processor/in-ch])])]
        (when-let [result (process-frame type pipeline processor frame)]
          (when (frame/frame? result)
            (send-frame! pipeline result)))
        (recur))))
  ;; Send start frame
  (t/log! :debug "Starting pipeline")
  (send-frame! pipeline (frame/system-start true)))

;; TODO stop all pipeline channels
(defn stop-pipeline!
  [pipeline]
  (t/log! :debug "Stopping pipeline")
  (t/log! :debug ["Conversation so far" (get-in @pipeline [:pipeline/config :llm/context])])
  (send-frame! pipeline (frame/system-stop true)))

(defn close-processor!
  [pipeline type]
  (t/log! {:level :debug
           :id type} "Closing processor")
  (a/close! (get-in @pipeline [type :processor/in-ch])))
