(ns simulflow.schema-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [malli.error :as me]
   [simulflow.mock-data :as mock]
   [simulflow.schema :as sut]))

;; LLM Messages

(deftest llm-message-schemas-test
  (testing "openai format llm messages"
    (testing "user messages"
      (is (m/validate sut/LLMUserMessage mock/stock-trade-user-msg))
      (is (m/validate sut/LLMUserMessage mock/stock-trade-user-msg-deprecated))
      (is (= {:content ["should be a string"]}
             (me/humanize (m/explain sut/LLMUserMessage {:role :user
                                                         :content [{:type :hello
                                                                    :content "hello world"}]}))))
      (is (= {:content ["should be a string"]}
             (me/humanize (m/explain sut/LLMUserMessage {:role :user
                                                         :content [{:type "text"
                                                                    :content 123}]})))))

    (testing "full set of messages is valid"
      (is (m/validate sut/LLMContextMessages mock/stock-trade-conversation)))

    (testing "invalid message is called out"
      (is (= [nil nil nil nil nil ["invalid type" "invalid type" "invalid type" "invalid type" "invalid type"]]
             (me/humanize (m/explain sut/LLMContextMessages (conj mock/stock-trade-conversation :invalid))))))))

;; Tool Definition

(deftest llm-functions-parameters-test
  (testing "LLMFunctionCallParameterSchema checks if required parameters are defined"
    (let [valid-parameters {:type :object
                            :description "Function to close a Twilio call"
                            :required [:call_sid]
                            :properties {:call_sid {:type :string
                                                    :description "The unique identifier of the call to be closed"}}}
          missing-required (assoc valid-parameters :required [:missing])
          not-missing (assoc-in missing-required [:properties :missing] {:type :number
                                                                         :description "This is a missing property"})]
      (is (m/validate sut/LLMFunctionCallParameters valid-parameters))
      (is (= ["Required parameters are not defined"]
             (me/humanize (m/explain sut/LLMFunctionCallParameters missing-required))))
      (is (m/validate sut/LLMFunctionCallParameters not-missing))))

  (testing "llms accept array type parameters"
    (is (m/validate sut/LLMFunctionCallParameters {:type :object
                                                   :required [:ticker :fields :date]
                                                   :properties {:ticker {:type :string
                                                                         :description "Stock ticker symbol for which to retrieve data"}
                                                                :fields {:type :array
                                                                         :description "Fields to retrieve for the stock data"
                                                                         :items {:type :string
                                                                                 :description "Field name to retrieve (e.g 'price' 'volume')"}}
                                                                :date {:type :string
                                                                       :description "Date for which to retrieve stock data in the format 'YYYY-MM-DD'"}}}))))

(deftest llm-function-tool-definition-test
  (testing "LLMFunctionToolDefinition"
    (let [valid-function
          {:type :function
           :function {:name "close_twilio_call"
                      :description "Function to close a twilio call"
                      :parameters {:type :object
                                   :required [:call_sid :reason]
                                   :properties {:call_sid {:type :string
                                                           :description "The unique identifier of the call to be closed"}
                                                :reason {:type :string
                                                         :description "The reason for closing the call"}}
                                   :additionalProperties false}
                      :strict true}}
          invalid-required (update-in valid-function [:function :parameters :properties] dissoc :reason)
          stocks-function {:type :function
                           :function
                           {:name "retrieve_latest_stock_data"
                            :description "Retrive latest stock data for the current day"
                            :parameters {:type :object
                                         :required [:ticker :fields :date]
                                         :properties {:ticker {:type :string
                                                               :description "Stock ticker symbol for which to retrieve data"}
                                                      :fields {:type :array
                                                               :description "Fields to retrieve for the stock data"
                                                               :items {:type :string
                                                                       :description "Field name to retrieve (e.g 'price' 'volume')"}}
                                                      :date {:type :string
                                                             :description "Date for which to retrieve stock data in the format 'YYYY-MM-DD'"}}
                                         :additionalProperties false}
                            :strict true}}]
      (is (m/validate sut/LLMFunctionToolDefinition valid-function))
      (is (= {:function {:parameters ["Required parameters are not defined"]}}
             (me/humanize (m/explain sut/LLMFunctionToolDefinition invalid-required))))
      (is (m/validate sut/LLMFunctionToolDefinition stocks-function)))))

(deftest parse-with-defaults-test
  (testing "applies defaults for valid input with all required fields"
    (let [schema [:map
                  [:required-field :string]
                  [:optional-field {:optional true} :string]
                  [:default-field {:default "default-value"} :string]]
          params {:required-field "value"}
          result (sut/parse-with-defaults schema params)]
      (is (= {:required-field "value" :default-field "default-value"} result))))

  (testing "throws when required fields are missing"
    (let [schema [:map
                  [:api-key :string]
                  [:optional-field {:optional true} :string]
                  [:default-field {:default "default-value"} :string]]
          params {:optional-field "optional"}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing required parameters"
           (sut/parse-with-defaults schema params)))))

  (testing "includes helpful error data when required fields missing"
    (let [schema [:map
                  [:api-key :string]
                  [:another-required :int]]
          params {}]
      (try
        (sut/parse-with-defaults schema params)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (prn data)
            (is (= #{:api-key :another-required} (set (:missing-required data))))
            (is (= #{:api-key :another-required} (set (:required-fields data))))
            (is (= [] (:provided-keys data))))))))

  (testing "works with complex schemas like OpenAI config"
    (let [schema [:map
                  [:openai/api-key :string]
                  [:llm/model {:default "gpt-4o-mini"} :string]
                  [:llm/temperature {:default 0.7 :optional true} :double]]
          params {:openai/api-key "sk-1234567890abcdef"}
          result (sut/parse-with-defaults schema params)]
      (is (= "sk-1234567890abcdef" (:openai/api-key result)))
      (is (= "gpt-4o-mini" (:llm/model result)))
      (is (= 0.7 (:llm/temperature result)))))

  (testing "handles :and schemas like DeepgramConfig"
    (let [schema [:and
                  [:map
                   [:transcription/api-key :string]
                   [:transcription/model {:default "nova-2"} :string]]
                  [:fn (constantly true)]]
          params {:transcription/api-key "token-123"}
          result (sut/parse-with-defaults schema params)]
      (is (= result {:transcription/api-key "token-123" :transcription/model "nova-2"}))))

  (testing "throws when validation fails after applying defaults"
    (let [schema [:map
                  [:required-field :string]
                  [:number-field {:default "not-a-number"} :int]]
          params {:required-field "value"}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Parameters invalid after applying defaults"
           (sut/parse-with-defaults schema params)))))

  (testing "preserves provided values over defaults"
    (let [schema [:map
                  [:required-field :string]
                  [:default-field {:default "default"} :string]]
          params {:required-field "req" :default-field "provided"}
          result (sut/parse-with-defaults schema params)]
      (is (= "req" (:required-field result)))
      (is (= "provided" (:default-field result))))))

(deftest get-required-fields-test
  (testing "extracts required fields from simple map schema"
    (let [schema [:map
                  [:required1 :string]
                  [:optional1 {:optional true} :string]
                  [:default1 {:default "value"} :string]
                  [:required2 :int]]
          result (sut/get-required-fields schema)]
      (is (= #{:required1 :required2} result))))

  (testing "handles :and schemas"
    (let [schema [:and
                  [:map
                   [:req-field :string]
                   [:opt-field {:optional true} :string]]
                  [:fn (constantly true)]]
          result (sut/get-required-fields schema)]
      (is (= #{:req-field} result))))

  (testing "returns empty set for schemas with no required fields"
    (let [schema [:map
                  [:optional1 {:optional true} :string]
                  [:default1 {:default "value"} :string]]
          result (sut/get-required-fields schema)]
      (is (= #{} result))))

  (testing "handles schemas that are not maps"
    (let [schema :string
          result (sut/get-required-fields schema)]
      (is (= #{} result)))))

(deftest parse-with-defaults-integration-test
  (testing "works with realistic processor schemas"
    ;; Test with ActivityMonitorConfigSchema pattern
    (let [activity-schema [:map
                           [::timeout-ms {:default 5000 :optional true} :int]
                           [::required-param :string]]
          params {::required-param "test"}
          result (sut/parse-with-defaults activity-schema params)]
      (is (= result {::required-param "test", ::timeout-ms 5000})))))

(deftest ->describe-parameters-test
  (testing "Simple schema with basic types"
    (let [schema [:map
                  [::simple-string {:optional true
                                    :description "A simple string field"}
                   :string]
                  [::simple-int {:description "A required integer field"}
                   :int]
                  [::no-description :boolean]]
          result (sut/->describe-parameters schema)]
      (is (= result {::simple-string "Type: string; Optional ; Description: A simple string field"
                     ::simple-int "Type: integer; Description: A required integer field"
                     ::no-description "Type: boolean"}))))

  (testing "Schema with :or alternatives"
    (let [schema [:map
                  [::or-simple {:optional true
                                :description "String or integer"}
                   [:or :string :int]]
                  [::or-collections {:description "Set or vector of strings"}
                   [:or
                    [:set :string]
                    [:vector :string]]]]
          result (sut/->describe-parameters schema)]
      (is (= result {::or-simple "Type: (string or integer); Optional ; Description: String or integer"
                     ::or-collections "Type: (set of string or vector of string); Description: Set or vector of strings"}))))

  (testing "Schema with complex nested types"
    (let [schema [:map
                  [::vector-field {:description "Vector of integers"}
                   [:vector :int]]
                  [::set-field {:optional true}
                   [:set :keyword]]
                  [::map-field {:description "Nested map"}
                   :map]]
          result (sut/->describe-parameters schema)]
      (is (= result {::vector-field "Type: vector of integer; Description: Vector of integers"
                     ::set-field "Type: set of keyword; Optional "
                     ::map-field "Type: map; Description: Nested map"}))))

  (testing "Schema with multi-alternative :or"
    (let [schema [:map
                  [::multi-or {:description "String, integer, or boolean"}
                   [:or :string :int :boolean]]
                  [::complex-or {:optional true
                                 :description "Multiple collection types"}
                   [:or
                    [:set :string]
                    [:vector :int]
                    [:set :keyword]]]]
          result (sut/->describe-parameters schema)]
      (is (= result {::multi-or "Type: (string or integer or boolean); Description: String, integer, or boolean"
                     ::complex-or "Type: (set of string or vector of integer or set of keyword); Optional ; Description: Multiple collection types"}))))

  (testing "Edge cases and validation"
    (testing "Non-map schema throws assertion error"
      (is (thrown-with-msg?
           AssertionError
           #"Can only transform :map schemas"
           (sut/->describe-parameters :string))))

    (testing "Empty map schema"
      (let [schema [:map]
            result (sut/->describe-parameters schema)]
        (is (= result {}))))

    (testing "Field without description or optional flag"
      (let [schema [:map
                    [::bare-field :string]]
            result (sut/->describe-parameters schema)]
        (is (= result {::bare-field "Type: string"}))))))

(deftest describe-schema-type-test
  (testing "Basic schema types"
    (is (= (sut/describe-schema-type :string) "string"))
    (is (= (sut/describe-schema-type :int) "integer"))
    (is (= (sut/describe-schema-type :boolean) "boolean"))
    (is (= (sut/describe-schema-type :keyword) "keyword"))
    (is (= (sut/describe-schema-type :any) "any"))
    (is (= (sut/describe-schema-type :map) "map")))

  (testing "Collection schema types"
    (is (= (sut/describe-schema-type [:set :string]) "set of string"))
    (is (= (sut/describe-schema-type [:vector :int]) "vector of integer"))
    (is (= (sut/describe-schema-type [:set :keyword]) "set of keyword")))

  (testing ":or schema types"
    (is (= (sut/describe-schema-type [:or :string :int]) "(string or integer)"))
    (is (= (sut/describe-schema-type [:or [:set :string] [:vector :string]]) "(set of string or vector of string)"))
    (is (= (sut/describe-schema-type [:or :string :int :boolean]) "(string or integer or boolean)")))

  (testing "Complex nested schemas"
    (is (= (sut/describe-schema-type [:or
                                      [:set :string]
                                      [:vector :int]
                                      [:set :keyword]])
           "(set of string or vector of integer or set of keyword)")))

  (testing "Unknown schema types fall back to string representation"
    ;; Test with a simple keyword that isn't recognized but is valid
    (is (= (sut/describe-schema-type :double) ":double"))))
