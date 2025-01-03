(ns voice-fn.schema
  (:require
   [clojure.core.async.impl.protocols :as async-protocols]
   [malli.core :as m]
   [malli.transform :as mt]))

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

(def SampleRate
  [:enum
   {:description "Audio sample rate in Hz"
    :error/message "Invalid sample rate"}
   8000                                 ; Low quality telephony
   11025                                ; Quarter of CD quality
   12000                                ; Some voice recording
   16000                                ; Common for speech recognition
   22050                                ; Half CD quality
   24000                                ; Some digital audio
   32000                                ; Digital broadcasting
   44100                                ; CD quality
   48000                                ; Professional audio/DAT
   88200                                ; High-res audio (2× 44.1)
   96000                                ; High-res audio/video
   176400                               ; Highest-res audio (4× 44.1)
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
   1   ;; mono
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

(def LLMContextessageRole
  (flex-enum
    {:description "Role of the message participant"
     :error/message "Role must be system, assistant, or user"}
    ["system"
     "assistant"
     "user"]))

(def LLMContextMessage
  [:map
   {:description "Message from the LLM Context"
    :error/message "Invalid message format"
    :closed true} ;; No additional fields allowed
   [:role LLMContextessageRole]
   [:content [:string {:min 1}]]])

(def LLMContext
  [:vector
   {:min 1
    :max 100 ;; Optional max limit
    :description "Vector of context messages"
    :error/message "Must have at least one context message"}
   LLMContextMessage])

(def Channel
  [:fn
   {:error/message "Must be a core.async channel"
    :description "core.async channel"}
   #(satisfies? async-protocols/Channel %)])
