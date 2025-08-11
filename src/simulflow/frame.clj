(ns simulflow.frame
  "Defines the core frame concept and frame creation functions for the simulflow pipeline.
   A frame represents a discrete unit of data or control flow in the pipeline."
  (:refer-clojure :exclude [send])
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [simulflow.schema :as schema]))

(def check-frame-schema?
  "Whether the schema of frames should be checked on schema creation. Use
  alias :dev to make this true. Default false"
  (try
    (Boolean/parseBoolean (System/getProperty "simulflow.frame.schema-checking" "false"))
    (catch Exception _e
      false)))

(defn frame? [frame]
  (and (= (:type (meta frame)) ::frame)
       (map? frame)))

(defn normalize-timestamp
  "Convert a timestamp to milliseconds since epoch.
   Accepts integers (milliseconds) and java.util.Date objects."
  [timestamp]
  (cond
    (int? timestamp) timestamp
    (instance? java.util.Date timestamp) (.getTime timestamp)
    :else (throw (ex-info "Invalid timestamp type" {:timestamp timestamp :type (type timestamp)}))))

(defn timestamp->date
  "Convert a timestamp to a java.util.Date.
   Accepts integers (milliseconds) and java.util.Date objects."
  [timestamp]
  (cond
    (int? timestamp) (java.util.Date. timestamp)
    (instance? java.util.Date timestamp) timestamp
    :else (throw (ex-info "Invalid timestamp type" {:timestamp timestamp :type (type timestamp)}))))

(defn create-frame
  "Create a frame with the given type and data.

  Args:
   - type: The frame type keyword
   - data: The frame data
   - opts: Optional map with:
     - :timestamp - Explicit timestamp (defaults to current time)
                   Can be an integer (milliseconds) or java.util.Date (#inst)

   Examples:
   (create-frame :test/frame \"data\")
   (create-frame :test/frame \"data\" {:timestamp 1696492800000})
   (create-frame :test/frame \"data\" {:timestamp #inst \"2024-10-05T05:00:00.000Z\"})"
  ([type data]
   (create-frame type data {}))
  ([type data {:keys [timestamp]}]
   (with-meta {:frame/type type
               :frame/data data
               :frame/ts (timestamp->date (or timestamp (java.util.Date.)))} {:type ::frame})))

(defmacro defframe
  "Define a frame creator function and its predicate with schema validation.
   Usage: (defframe audio-input
                    \"Doc string\"
                    {:type :frame.audio/input-raw
                     :schema [:map [:data AudioData]]})

   The generated function supports both:
   (frame-name data) - Uses current timestamp
   (frame-name data {:timestamp ts}) - Uses explicit timestamp"
  [name docstring {:keys [type schema] :or {schema :any}}]
  (let [frame-schema [:map
                      [:frame/type [:= type]]
                      [:frame/data schema]
                      [:frame/ts schema/Timestamp]]
        frame-schema-name (symbol (str name "-schema"))
        pred-name (symbol (str name "?"))]
    `(do
       ;; Define the frame schema
       (def ~frame-schema-name ~frame-schema)

       ;; Define the frame creator function with schema validation
       (def ~name
         ~docstring
         (fn
           ([data#]
            (~name data# {}))
           ([data# opts#]
            (let [frame# (create-frame ~type data# opts#)]
              (when check-frame-schema?
                (when-let [err# (me/humanize (m/explain ~frame-schema frame#))]
                  (throw (ex-info "Invalid frame data"
                                  {:error err#
                                   :frame frame#}))))
              frame#))))

       ;; Define the predicate function
       (def ~pred-name
         (fn [frame#]
           (and (frame? frame#)
                (m/validate ~frame-schema-name frame#)))))))

;;
;; System Frames
;; These frames control core pipeline functionality
;;

(defframe system-start
  "Frame sent when the pipeline begins"
  {:type ::system-start
   :schema :boolean})

(def FramePredicate
  [:fn {:error/message "Must be a function that takes a frame and returns boolean"
        :gen/fmap (fn [_] system-start?)} ; Example generator
   (fn [f]
     (and (fn? f)
          (try
            (boolean? (f (create-frame :test/frame {})))
            (catch Exception _
              false))))])

(def FrameCreator
  [:fn
   {:error/message "Must be a function that takes type and data and returns a valid frame"
    :gen/fmap (fn [_] system-start)} ; Example generator
   (fn [f]
     (and (fn? f)
          (try
            (let [result (f {:test "data"})]
              (frame? result))
            (catch Exception _
              false))))])

(defframe system-stop
  "Frame sent when the pipeline stops"
  {:type ::system-stop
   :schema :boolean})

(defframe system-error
  "General error frame"
  {:type ::system-error})

(defframe system-config-change
  "Frame with configuration changes for the running pipeline"
  {:type ::system-config-change
   :schema schema/PartialConfigSchema})

;;
;; Audio Frames
;; Frames for handling raw audio data
;;

(defframe audio-input-raw
  "Raw audio input frame from input transport"
  {:type ::audio-input-raw
   :schema schema/ByteArray})

(defframe audio-output-raw
  "Raw audio output frame for playback through output transport"
  {:type ::audio-output-raw
   :schema [:map
            [:audio schema/ByteArray]
            [:sample-rate schema/SampleRate]]})

(defframe audio-tts-raw
  "Raw audio frame generated by TTS service"
  {:type ::audio-tts-raw})

;;
;; Transcription Frames
;; Frames for speech-to-text processing
;;

(defframe transcription
  "Transcription result. NOTE: This doesn't mean it is a full transcription, but
  a transcription chunk that the transcriptor has full confidence in."
  {:type ::transcription-result
   :schema :string})

(defframe transcription-interim
  "Interim transcription result"
  {:type ::transcription-interim})

;;
;; Context Frames
;; Frames for managing conversation context
;;

(defframe llm-context
  "Frame containing LLM context"
  {:type ::llm-context
   :schema schema/LLMContext})

(defframe llm-context-messages-append
  "Frame containing messages that should be appended to the current
  context."
  {:type ::llm-context-messages-append
   :schema [:map
            [:messages schema/LLMContextMessages]
            [:properties {:optional true}
             [:map {:closed true}
              [:run-llm? {:optional true
                          :description "Whether to send the new context further (for LLM query)"} :boolean]
              [:tool-call? {:optional true
                            :description "Is the last message a tool call request?"} :boolean]
              [:on-update {:optional true
                           :description "Callback called after tool result is added to context"} [:maybe [:=> [:cat] :any]]]]]]})

(defframe llm-tools-replace
  "Frame containing new tools that should replace existing ones. Used by
  scenario manager when transitioning to a new node"
  {:type ::llm-tools-replace
   :schema schema/LLMFunctionToolDefinition})

;;
;; Scenario frames
;; Frames used predefined scenarios
;;

(defframe scenario-context-update
  "Frame containing messages to append to the llm context and the new tools to
  replace the old ones in order to create future transitions from the current node."
  {:type ::scenario-context-update
   :schema schema/ScenarioUpdateContext})

;;
;; LLM Output Frames
;; Frames for language model outputs
;;

(defframe llm-text-chunk
  "Chunk of text from streaming LLM output"
  {:type ::llm-text-chunk})

(defframe llm-tool-call-chunk
  "Chunk of tool call request. Needs to be assembled before use."
  {:type ::llm-tool-call-chunk})

(defframe llm-tool-call-request
  "Frame containing a tool call request"
  {:type ::llm-tool-call-request
   :schema schema/LLMAssistantMessage})

(defframe llm-tool-call-result
  "Frame containing the result of invoking a tool for the LLM."
  {:type ::llm-tool-call-result
   :schema [:map
            [:request schema/LLMAssistantMessage]
            [:result schema/LLMToolMessage]
            [:properties {:optional true}
             [:map {:closed true}
              [:run-llm? {:optional true
                          :description "Wether to send the new context further (for LLM query)"} :boolean]
              [:on-update {:optional true
                           :description "Callback called after tool result is added to context"} [:maybe [:=> [:cat] :any]]]]]]})

(defframe llm-text-sentence
  "Complete sentence from LLM output"
  {:type ::llm-text-sentence})

(defframe llm-full-response-start
  "Indicates the start of an LLM response"
  {:type ::llm-response-start})

(defframe llm-full-response-end
  "Indicates the end of an LLM response"
  {:type ::llm-response-end})

;;
;; Vendor specific frames
;; Frames specific to certain vendors
;;

(def XiAlignment [:map
                  [:charStartTimesMs [:vector :int]]
                  [:chars [:vector :string]]
                  [:charDurationsMs [:vector :int]]])

(defframe xi-audio-out
  "Frame containing the full output from elevenlabs including chars & timings for chars"
  {:type ::xi-audio-out
   :schema [:map
            [:alignment [:maybe XiAlignment]]
            [:normalizedAlignment {:optional true} [:maybe XiAlignment]]
            [:audio :string] ;; base64 audio
            [:isFinal [:maybe :boolean]]]})

;;
;; User Interaction Frames
;; Frames for handling user speech events
;;

(defframe user-speech-start
  "Frame denoting that User started speaking"
  {:type ::user-speech-start
   :schema :boolean})

(defframe user-speech-stop
  "Frame denoting that User stopped speaking"
  {:type ::user-speech-stop
   :schema :boolean})

(defframe vad-user-speech-start
  "Frame denoting that a VAD model/system User started speaking. This is
  different than user-speech-start/stop because the vad frames may not trigger
  aggregation or pipeline interruption the same as the user-speech-start/stop
  frames because there are scenarios where VAD conclusion can be overturned by a
  more advanced turn detection model that still considers the user is still
  speaking (for example). These frames are emitted for observability."
  {:type ::vad-user-speech-start
   :schema :boolean})

(defframe vad-user-speech-stop
  "Frame denoting that a VAD model/system User stopped speaking. This is
  different than user-speech-start/stop because the vad frames may not trigger
  aggregation or pipeline interruption the same as the user-speech-start/stop
  frames because there are scenarios where VAD conclusion can be overturned by a
  more advanced turn detection model that still considers the user is still
  speaking (for example). These frames are emitted for observability."
  {:type ::vad-user-speech-stop
   :schema :boolean})

;;
;; Bot Interaction Frmaes
;; Frames for handling bot speech events
;;

(defframe bot-speech-start
  "Bot started speaking"
  {:type ::bot-speech-start
   :schema :boolean})

(defframe bot-speech-stop
  "Bot stopped speaking"
  {:type ::bot-speech-stop
   :schema :boolean})

;;
;; Control Frames
;; Frames for pipeline flow control
;;

(defframe control-interrupt-start
  "Start pipeline interruption"
  {:type ::control-interrupt-start
   :schema :boolean})

(defframe control-interrupt-stop
  "Stop pipeline interruption"
  {:type ::control-interrupt-stop
   :schema :boolean})

(defframe bot-interrupt
  "Frame that signals the pipeline should be interrupted. Usually the pipeline
  interruption is the responsability of the transport input. The `bot-interrupt`
  frame is emitted when we have interruptions based on strategies which usually
  require access to transcriptions that are not available in the transport
  input.

  This frame is emitted usually by the user-context-aggregator to signal to the transport-in that it should emit a control-interrupt-start frame.

  The flow works this way because the user-context-aggregator has access to the user transcriptions"
  {:type ::bot-interrupt
   :schema :boolean})

;;
;; Input/Output Text Frames
;; Frames for text processing
;;

(defframe speak-frame
  "Text frame meant for TTS processors to generate speech from the input"
  {:type ::speak-frame
   :schema :string})

(defframe text-input
  "Text input frame for LLM processing"
  {:type ::text-input})

(def system-frames #{::system-start
                     ::system-stop
                     ::system-config-change
                     ::user-speech-start
                     ::user-speech-stop
                     ::bot-speech-stop
                     ::bot-speech-start
                     ::vad-user-speech-stop
                     ::vad-user-speech-start
                     ::bot-interrupt
                     ::control-interrupt-start
                     ::control-interrupt-stop})

(defn system-frame?
  "Returns true if the frame is a system frame that should be processed immediately"
  [frame]
  (and
    (frame? frame)
    (contains? system-frames (:frame/type frame))))

(defn- out-channel
  [frame]
  (if (system-frame? frame)
    :sys-out
    :out))

(defn send
  "Returns a core.async.flow compliant output map with frames being directed on
  their respective output channel based on frame types.

  system-frames (see `system-frames` set) go onto `:sys-out` and normal frames
  go on `:out` channel."
  [& frames]
  (group-by out-channel frames))

(comment
  (send (user-speech-start true) (user-speech-stop true) (audio-output-raw {:sample-rate 16000 :audio (byte-array (range 200))}))
  ;; => {:sys-out
  ;;     [#:frame{:type :simulflow.frame/user-speech-start,
  ;;              :data true,
  ;;              :ts #inst "2025-08-11T07:49:15.477-00:00"}
  ;;      #:frame{:type :simulflow.frame/user-speech-stop,
  ;;              :data true,
  ;;              :ts #inst "2025-08-11T07:49:15.477-00:00"}],
  ;;     :out
  ;;     [#:frame{:type :simulflow.frame/audio-output-raw,
  ;;              :data
  ;;              {:sample-rate 16000,
  ;;               :audio
  ;;               [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
  ;;                19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,
  ;;                36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52,
  ;;                53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69,
  ;;                70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86,
  ;;                87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102,
  ;;                103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115,
  ;;                116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, -128,
  ;;                -127, -126, -125, -124, -123, -122, -121, -120, -119, -118, -117,
  ;;                -116, -115, -114, -113, -112, -111, -110, -109, -108, -107, -106,
  ;;                -105, -104, -103, -102, -101, -100, -99, -98, -97, -96, -95, -94,
  ;;                -93, -92, -91, -90, -89, -88, -87, -86, -85, -84, -83, -82, -81,
  ;;                -80, -79, -78, -77, -76, -75, -74, -73, -72, -71, -70, -69, -68,
  ;;                -67, -66, -65, -64, -63, -62, -61, -60, -59, -58, -57]},
  ;;              :ts #inst "2025-08-11T07:49:15.477-00:00"}]}
  ,)
