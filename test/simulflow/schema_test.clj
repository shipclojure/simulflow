(ns simulflow.schema-test
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [midje.sweet :refer [fact facts]]
   [simulflow.mock-data :as mock]
   [simulflow.schema :as sut]))

;; LLM Messages

(facts
  "about schema for openai format llm messages"

  (fact "about user messages"
        (m/validate sut/LLMUserMessage mock/stock-trade-user-msg) => true
        (m/validate sut/LLMUserMessage mock/stock-trade-user-msg-deprecated) => true
        (me/humanize (m/explain sut/LLMUserMessage {:role :user
                                                    :content [{:type :hello
                                                               :content "hello world"}]}))
        => {:content ["should be a string"]}
        (me/humanize (m/explain sut/LLMUserMessage {:role :user
                                                    :content [{:type "text"
                                                               :content 123}]}))
        =>  {:content ["should be a string"]})

  (fact "full set of messages is valid"
        (m/validate sut/LLMContextMessages mock/stock-trade-conversation) => true)
  (fact "invalid message is called out"
        (me/humanize (m/explain sut/LLMContextMessages (conj mock/stock-trade-conversation :invalid)))
        => [nil nil nil nil nil ["invalid type" "invalid type" "invalid type" "invalid type" "invalid type"]]))

;; Tool Definition

(facts "about llm functions parameters"
  (fact "LLMFunctionCallParameterSchema checks if required parameters are defined"

        (let [valid-parameters {:type :object
                                :description "Function to close a Twilio call"
                                :required [:call_sid]
                                :properties {:call_sid {:type :string
                                                        :description "The unique identifier of the call to be closed"}}}
              missing-required (assoc valid-parameters :required [:missing])
              not-missing (assoc-in missing-required [:properties :missing] {:type :number
                                                                             :description "This is a missing property"})]
          (m/validate sut/LLMFunctionCallParameters valid-parameters) => true
          (me/humanize (m/explain sut/LLMFunctionCallParameters missing-required)) => ["Required parameters are not defined"]
          (m/validate sut/LLMFunctionCallParameters not-missing) => true))
  (fact "llms accept array type parameters"
        (m/validate sut/LLMFunctionCallParameters {:type :object
                                                   :required [:ticker :fields :date]
                                                   :properties {:ticker {:type :string
                                                                         :description "Stock ticker symbol for which to retrieve data"}
                                                                :fields {:type :array
                                                                         :description "Fields to retrieve for the stock data"
                                                                         :items {:type :string
                                                                                 :description "Field name to retrieve (e.g 'price' 'volume')"}}
                                                                :date {:type :string
                                                                       :description "Date for which to retrieve stock data in the format 'YYYY-MM-DD'"}}})
        => true))

(fact "LLMFunctionToolDefinition"
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
        (m/validate sut/LLMFunctionToolDefinition valid-function) => true

        (me/humanize (m/explain sut/LLMFunctionToolDefinition invalid-required)) => {:function {:parameters ["Required parameters are not defined"]}}
        (m/validate sut/LLMFunctionToolDefinition stocks-function) => true))
