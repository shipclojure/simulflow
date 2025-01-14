(ns voice-fn.protocol)

(defprotocol Processor
  "Protocol defining the core functionality of a pipeline processor"
  (processor-id [this]
    "Returns the id of the processor")

  (processor-schema [this]
    "Returns the malli schema for a processor type's configuration")

  (accepted-frames [this]
    "Returns set of frame types this processor accepts. Each processor must
  implement this method so the pipeline can know what frames to subscribe it
  to.")

  (make-processor-config [this pipeline-config processor-config]
    "Create the configuration for the processor based on the pipeline
  configuration or include defaults.

  Used when the final configuration for a processor requires
  information from the global pipeline configuration. ex: audio-in encoding,
  pipeline language, etc.

  - pipeline-config - the global config of the pipeline. It contains config such
  as the input audio-encoding, pipeline language, input and output channels and
  more. See `voice-fn.pipeline/PipelineConfigSchema`.

  - processor-config - the config of the processor as specified in the list of
  processors from the pipeline")

  (process-frame [this pipeline config frame]
    "Process a frame from the pipeline.
  - pipeline - atom containing the state of the pipeline.
  - config - pipeline config
  - frame - the frame to be processed by the processor"))
