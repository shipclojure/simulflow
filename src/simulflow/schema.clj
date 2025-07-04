(ns simulflow.schema
  (:require
   [clojure.core.async.impl.protocols :as impl]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [malli.util :as mu]
   [clojure.core.async.impl.protocols :as async-protocols]))

(defn parse-schema
  [s]
  (cond
    (keyword? s) {:type s
                  :data {}
                  :children []}
    :else
    (let [[type & [maybe-arg :as args]] s
          [data childs] (if (or (vector? maybe-arg)
                                (and (sequential? maybe-arg)
                                     (sequential? (first maybe-arg)))
                                (nil? maybe-arg))
                          [{} args]
                          [maybe-arg (rest args)])]
      {:type type
       :data data
       :children (if (= 1 (count childs)) (first childs) (vec childs))})))

(defn ->flow-describe-parameters
  "Take a malli schema and transform to core.async.flow processor :describe key format"
  [s]
  (let [{:keys [children type]} (parse-schema s)]
    (assert (= type :map) "Can only transform :map schemas to :describe parameter format ")
    (reduce
     (fn [acc child]
       (let [{key-name :type
              {:keys [description optional]} :data
              children :children} (parse-schema child)
             {:keys [type data]} (parse-schema children)
             description (or description (:description data))]
         (assoc acc key-name (apply str (remove nil? ["Type: " type (when optional "; Optional ") (when description (str "; Description: " description))])))))
     {}
     children)))

(defn get-required-fields
  "Extract required field keys from a Malli schema.
   A field is required if it doesn't have :optional true or :default."
  [schema]
  (letfn [(extract-from-map-schema [s]
            (when (= :map (m/type s))
              (->> (m/children s)
                   (keep (fn [child]
                           (let [[field-key props _field-schema] child
                                 has-default? (contains? props :default)
                                 is-optional? (:optional props)]
                             (when-not (or has-default? is-optional?)
                               field-key))))
                   set)))]
    (or (extract-from-map-schema schema)
        ;; Handle :and schemas (like DeepgramConfig)
        (when (= :and (m/type schema))
          (->> (m/children schema)
               (mapcat extract-from-map-schema)
               set))
        #{})))

(defn parse-with-defaults
  "Parse and validate parameters against schema, applying defaults only after
   ensuring all required (non-default, non-optional) parameters are present.

   Applies defaults to:
   - Fields with :default (regardless of :optional status)

   Throws if:
   - Required parameters are missing (those without :default or :optional true)
   - Parameters fail validation after defaults are applied

   Returns:
   - Map with defaults applied for valid input"
  [schema params]
  (let [;; First validate that required parameters are present
        required-fields (get-required-fields schema)
        missing-required (vec (remove #(contains? params %) required-fields))]

    (when (seq missing-required)
      (throw (ex-info "Missing required parameters"
                      {:missing-required missing-required
                       :required-fields required-fields
                       :provided-keys (vec (keys params))
                       :schema schema})))

    ;; Apply defaults including optional fields with defaults
    (let [transformer (mt/default-value-transformer {::mt/add-optional-keys true})
          with-defaults (m/decode schema params transformer)]
      (if (m/validate schema with-defaults)
        with-defaults
        (throw (ex-info "Parameters invalid after applying defaults"
                        {:errors (-> schema (m/explain with-defaults) me/humanize)
                         :params with-defaults
                         :schema schema}))))))

(defn flex-enum
  "Creates a flexible enum that accepts both keywords and their string versions.
   Input: coll of keywords or string
   Output: Malli enum schema that accepts both forms

   Example:
   (flex-enum [:nova-2 \"nova-2-general\" :gpt-4 \"gpt-4-turbo\"])"
  ([vals]
   (flex-enum {} vals))
  ([meta vals]
   (into [:enum meta] (distinct (mapcat (fn [v]
                                          [(name v)
                                           (keyword v)]) vals)))))

(def ByteArray [:fn #(instance? (Class/forName "[B") %)])

(def Timestamp
  "Schema for frame timestamps - supports milliseconds and java.util.Date"
  [:or
   :int ; milliseconds since epoch
   [:fn #(instance? java.util.Date %)]])

(defn regex?
  [input]
  (instance? java.util.regex.Pattern input))

(defn safe-coerce
  "Coerce to value without throwing error but instead "
  [schema value transformer]
  (m/coerce schema value transformer identity (partial (comp me/humanize :explain))))

(def SampleRate
  [:enum
   {:description "Audio sample rate in Hz"
    :error/message "Invalid sample rate"}
   8000 ; Low quality telephony
   11025 ; Quarter of CD quality
   12000 ; Some voice recording
   16000 ; Common for speech recognition
   22050 ; Half CD quality
   24000 ; Some digital audio
   32000 ; Digital broadcasting
   44100 ; CD quality
   48000 ; Professional audio/DAT
   88200 ; High-res audio (2× 44.1)
   96000 ; High-res audio/video
   176400 ; Highest-res audio (4× 44.1)
   192000])

(def AudioEncoding
  [:enum
   :alaw
   :ulaw
   :pcm-signed
   :pcm-unsigned
   :pcm-float])

(def AudioChannels
  [:enum
   {:default 1
    :description "Type of audio (mono/stereo)"
    :error/message "Invalid audio channels"}
   1 ;; mono
   2]) ;; stereo

(def SampleSizeBits
  [:enum {:description "Audio sample size in bits"
          :title "Sample Size"
          :json-schema/example 16}
   8 16 24 32])

(def Language
  (flex-enum
   {:description "Language codes including regional variants"}
   [;; Afrikaans
    "af" "af-ZA"

     ;; Amharic
    "am" "am-ET"

     ;; Arabic
    "ar" "ar-AE" "ar-BH" "ar-DZ" "ar-EG" "ar-IQ" "ar-JO" "ar-KW"
    "ar-LB" "ar-LY" "ar-MA" "ar-OM" "ar-QA" "ar-SA" "ar-SY" "ar-TN" "ar-YE"

     ;; Assamese
    "as" "as-IN"

     ;; Azerbaijani
    "az" "az-AZ"

     ;; Bulgarian
    "bg" "bg-BG"

     ;; Bengali
    "bn" "bn-BD" "bn-IN"

     ;; Bosnian
    "bs" "bs-BA"

     ;; Catalan
    "ca" "ca-ES"

     ;; Czech
    "cs" "cs-CZ"

     ;; Welsh
    "cy" "cy-GB"

     ;; Danish
    "da" "da-DK"

     ;; German
    "de" "de-AT" "de-CH" "de-DE"

     ;; Greek
    "el" "el-GR"

     ;; English
    "en" "en-AU" "en-CA" "en-GB" "en-HK" "en-IE" "en-IN" "en-KE"
    "en-NG" "en-NZ" "en-PH" "en-SG" "en-TZ" "en-US" "en-ZA"

     ;; Spanish
    "es" "es-AR" "es-BO" "es-CL" "es-CO" "es-CR" "es-CU" "es-DO"
    "es-EC" "es-ES" "es-GQ" "es-GT" "es-HN" "es-MX" "es-NI" "es-PA"
    "es-PE" "es-PR" "es-PY" "es-SV" "es-US" "es-UY" "es-VE"

     ;; Estonian
    "et" "et-EE"

     ;; Basque
    "eu" "eu-ES"

     ;; Persian
    "fa" "fa-IR"

     ;; Finnish
    "fi" "fi-FI"

     ;; Filipino
    "fil" "fil-PH"

     ;; French
    "fr" "fr-BE" "fr-CA" "fr-CH" "fr-FR"

     ;; Irish
    "ga" "ga-IE"

     ;; Galician
    "gl" "gl-ES"

     ;; Gujarati
    "gu" "gu-IN"

     ;; Hebrew
    "he" "he-IL"

     ;; Hindi
    "hi" "hi-IN"

     ;; Croatian
    "hr" "hr-HR"

     ;; Hungarian
    "hu" "hu-HU"

     ;; Armenian
    "hy" "hy-AM"

     ;; Indonesian
    "id" "id-ID"

     ;; Icelandic
    "is" "is-IS"

     ;; Italian
    "it" "it-IT"

     ;; Inuktitut
    "iu-Cans" "iu-Cans-CA" "iu-Latn" "iu-Latn-CA"

     ;; Japanese
    "ja" "ja-JP"

     ; Javanese
    "jv" "jv-ID"

     ;; Georgian
    "ka" "ka-GE"

     ;; Kazakh
    "kk" "kk-KZ"

     ;; Khmer
    "km" "km-KH"

     ;; Kannada
    "kn" "kn-IN"

     ;; Korean
    "ko" "ko-KR"

     ;; Lao
    "lo" "lo-LA"

     ;; Lithuanian
    "lt" "lt-LT"

     ;; Latvian
    "lv" "lv-LV"

     ;; Macedonian
    "mk" "mk-MK"

     ;; Malayalam
    "ml" "ml-IN"

     ;; Mongolian
    "mn" "mn-MN"

     ;; Marathi
    "mr" "mr-IN"

     ;; Malay
    "ms" "ms-MY"

     ;; Maltese
    "mt" "mt-MT"

     ;; Burmese
    "my" "my-MM"

     ;; Norwegian
    "nb" "nb-NO" "no"

     ;; Nepali
    "ne" "ne-NP"

     ;; Dutch
    "nl" "nl-BE" "nl-NL"

     ;; Odia
    "or" "or-IN"

     ;; Punjabi
    "pa" "pa-IN"

     ;; Polish
    "pl" "pl-PL"

     ;; Pashto
    "ps" "ps-AF"

     ;; Portuguese
    "pt" "pt-BR" "pt-PT"

     ;; Romanian
    "ro" "ro-RO"

     ;; Russian
    "ru" "ru-RU"

     ;; Sinhala
    "si" "si-LK"

     ;; Slovak
    "sk" "sk-SK"

     ;; Slovenian
    "sl" "sl-SI"

     ;; Somali
    "so" "so-SO"

     ;; Albanian
    "sq" "sq-AL"

     ;; Serbian
    "sr" "sr-RS" "sr-Latn" "sr-Latn-RS"

     ;; Sundanese
    "su" "su-ID"

     ;; Swedish
    "sv" "sv-SE"

     ;; Swahili
    "sw" "sw-KE" "sw-TZ"

     ;; Tagalog
    "tl"

     ;; Tamil
    "ta" "ta-IN" "ta-LK" "ta-MY" "ta-SG"

     ;; Telugu
    "te" "te-IN"

     ;; Thai
    "th" "th-TH"

     ;; Turkish
    "tr" "tr-TR"

     ;; Ukrainian
    "uk" "uk-UA"

     ;; Urdu
    "ur" "ur-IN" "ur-PK"

     ;; Uzbek
    "uz" "uz-UZ"

     ;; Vietnamese
    "vi" "vi-VN"

     ;; Wu Chinese
    "wuu" "wuu-CN"

     ;; Yue Chinese
    "yue" "yue-CN"

     ;; Chinese
    "zh" "zh-CN" "zh-CN-guangxi" "zh-CN-henan" "zh-CN-liaoning"
    "zh-CN-shaanxi" "zh-CN-shandong" "zh-CN-sichuan" "zh-HK" "zh-TW"

     ;; Xhosa
    "xh"

     ;; Zulu
    "zu" "zu-ZA"]))

;;; LLM Chat messages
(def LLMMessageContentType
  (flex-enum ["text"]))

(def LLMContextMessageRole (flex-enum [:user :assistant :tool :developer :systemb]))

(def LLMTextMessage
  "This is the modern openai text message format
  Example: the :content from this map
  {:role \"user\"
   :content [{:type \"text\"
              :text \"Hello World\"}]}"
  [:vector {:min 1 :max 1} [:map
                            {:closed true}
                            [:type LLMMessageContentType]
                            [:text :string]]])

(def LLMToolCallMessage
  [:vector {:min 1 :max 1}
   [:map
    {:closed true}
    [:id :string]
    [:type (flex-enum ["function"])]
    [:function
     [:map
      {:closed true}
      [:name :string]
      [:arguments :any]]]]])

(def LLMDeveloperMessage
  "Developer messages are the new system messages for the newer openai models like
  o1"
  [:map
   {:closed true
    :description "Developer-provided instructions that the model should follow, regardless of messages sent by the user"}
   [:role (flex-enum ["developer"])]
   [:content [:or :string LLMTextMessage]]])

(def LLMSystemMessage
  "System messages tell the model how to behave"
  [:map
   {:closed true
    :description "Developer-provided instructions that the model should follow. Deprecated with o1 models - use developer messages instead"}
   [:role (flex-enum ["system"])]
   [:content [:or :string LLMTextMessage]]])

(def LLMUserMessage
  "Messages sent to the llm by the end user"
  [:map
   {:closed true}
   [:role (flex-enum ["user"])]
   [:content [:or :string LLMTextMessage]]])

(def LLMAssistantMessage
  "Messages sent by the model in response to user messages"
  [:map
   {:closed true
    :description "Messages sent by the model in response to user messages"}
   [:role (flex-enum ["assistant"])]
   [:content {:optional true} [:or :string LLMTextMessage]]
   [:refusal {:optional true} [:maybe :string]]
   [:audio {:optional true} [:maybe :map]]
   [:tool_calls {:optional true} LLMToolCallMessage]
   [:function_call {:optional true}
    [:map
     {:closed true}
     [:name :string]
     [:arguments :string]]]])

(def LLMToolMessage
  "Messages that contain results to function calls for the model to use."
  [:map
   {:closed true}
   [:role (flex-enum ["tool"])]
   [:content [:or :string LLMTextMessage]]
   [:tool_call_id :string]])

(def LLMMessage
  [:or
   LLMDeveloperMessage
   LLMSystemMessage
   LLMUserMessage
   LLMAssistantMessage
   LLMToolMessage])

(def LLMContextMessages
  [:vector
   {:min 1
    :description "Vector of context messages"}
   LLMMessage])

;;; Tool function declaration

(def LLMFunctionCallParameterStringProperty
  [:map
   [:type (flex-enum [:string])]
   [:description :string]
   [:enum {:optional true} [:vector :string]]])

(def LLMFunctionCallParameterNumberProperty
  [:map
   [:type (flex-enum [:number])]
   [:description :string]
   [:enum {:optional true} [:vector :int]]])

(def LLMFunctionCallParameterIntegerProperty
  [:map
   [:type (flex-enum [:integer])]
   [:description :string]
   [:enum {:optional true} [:vector :int]]])

(def LLMFunctionCallParameterBooleanProperty
  [:map
   [:type (flex-enum [:boolean])]
   [:description :string]])

(def LLMFunctionCallParameterArrayProperty
  [:map
   [:type (flex-enum [:array])]
   [:description :string]
   [:items [:or LLMFunctionCallParameterStringProperty
            LLMFunctionCallParameterNumberProperty
            LLMFunctionCallParameterBooleanProperty]]])

(def LLMFunctionCallParameterProperty
  [:or
   LLMFunctionCallParameterStringProperty
   LLMFunctionCallParameterNumberProperty
   LLMFunctionCallParameterIntegerProperty
   LLMFunctionCallParameterBooleanProperty
   LLMFunctionCallParameterArrayProperty])

(defn- check-missing-params [params]
  (let [available-properties (set (map name (keys (:properties params))))
        required-properties (map name (:required params))]
    (every? #(contains? available-properties %) required-properties)))

(def LLMFunctionCallParameters
  [:and
   [:map
    [:type (flex-enum [:object])]
    [:required {:optional true} [:vector [:or :string :keyword]]]
    [:properties [:map-of :keyword LLMFunctionCallParameterProperty]]
    [:additionalProperties {:optional true} :boolean]]
   [:fn {:error/message "Required parameters are not defined"}
    check-missing-params]])

(def ToolChoiceEnum
  (flex-enum [:none :auto :required]))

(def ToolChoice
  [:or
   ToolChoiceEnum
   [:map
    {:closed true}
    [:type (flex-enum [:function])]
    [:function
     [:map
      {:closed true}
      [:name :string]]]]])

(def LLMFunctionToolDefinition
  [:map {:closed true}
   [:type (flex-enum [:function])]
   [:function [:map
               [:name :string]
               [:description :string]
               [:parameters LLMFunctionCallParameters]
               [:strict {:optional true} :boolean]]]])

(def LLMFunctionToolDefinitionWithHandling
  (mu/merge
   LLMFunctionToolDefinition
   [:map {:closed true}
    [:function
     [:map
      [:handler [:=> [:cat :map] :any]]
      [:transition-cb {:optional true} [:=> :cat :nil]]]]]))

(def TransitionTo
  [:or
   :keyword
   [:and
    [:=> [:cat :map] :any]
    [:fn {:error/message "Transition function must return a valid node keyword"}
     (fn [f]
       (try
         (let [result (f {})]
           (keyword? result))
         (catch Exception _ false)))]]])

(def LLMTransitionToolDefinition
  (mu/merge LLMFunctionToolDefinitionWithHandling
            [:map {:closed true}
             [:function
              [:map
               [:handler {:optional true} [:=> [:cat :map] :any]]
               [:transition-to TransitionTo]]]]))

(def RegisteredFunctions [:map-of :string [:map
                                           [:tool [:=> [:cat :map] :any]]
                                           [:async? {:optional true} :boolean]]])

(def LLMContext
  [:map
   [:messages LLMContextMessages]
   [:tools {:optional true} [:vector LLMFunctionToolDefinitionWithHandling]]
   [:tool-choice {:optional true :default :auto} ToolChoice]])

(def ScenarioUpdateContext
  [:map
   [:messages LLMContextMessages]
   [:tools {:optional true} [:vector [:or LLMTransitionToolDefinition LLMFunctionToolDefinitionWithHandling]]]
   [:tool-choice {:optional true :default :auto} ToolChoice]])

(def CoreAsyncChannel
  [:fn
   {:error/message "Must be a core.async channel"
    :description "core.async channel"}
   #(satisfies? impl/Channel %)])

(def PipelineConfigSchema
  [:map
   [:audio-in/sample-rate {:default 16000} SampleRate]
   [:audio-in/channels {:default 1} AudioChannels]
   [:audio-in/encoding {:default :pcm-signed} AudioEncoding]
   [:audio-in/sample-size-bits {:default 16} SampleSizeBits]
   [:audio-out/sample-rate {:default 16000} SampleRate]
   [:audio-out/channels {:default 1} AudioChannels]
   [:audio-out/encoding {:default :pcm-signed} AudioEncoding]
   [:audio-out/sample-size-bits {:default 16} SampleSizeBits]
   [:pipeline/language Language]
   [:pipeline/supports-interrupt? {:default false
                                   :optional true} :boolean]
   [:llm/context LLMContext]
   [:llm/registered-functions {:optional true} RegisteredFunctions]
   [:transport/in-ch CoreAsyncChannel]
   [:transport/out-ch CoreAsyncChannel]
   [:twilio/call-sid :string]
   [:twilio/stream-sid :string]])

(def PartialConfigSchema (mu/optional-keys PipelineConfigSchema))
