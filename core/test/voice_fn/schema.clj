(ns voice-fn.schema
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [midje.sweet :refer [fact]]
   [voice-fn.schema :as sut]))

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

(fact "LLMFunctionToolDefinition"
      (let [valid-function {:type :function
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
            invalid-required (update-in valid-function [:function :parameters :properties] dissoc :reason)]
        (m/validate sut/LLMFunctionToolDefinition valid-function) => true

        (me/humanize (m/explain sut/LLMFunctionToolDefinition invalid-required)) => {:function {:parameters ["Required parameters are not defined"]}}))
