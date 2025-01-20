(ns voice-fn.processors.openai-test
  (:require
   [clojure.core.async :as a]
   [midje.sweet :refer [fact facts]]
   [voice-fn.frame :as frame]
   [voice-fn.mock-data :as mock]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.processors.openai :as sut]
   [voice-fn.protocol :as p]
   [voice-fn.test-system :as ts]))

(defmethod pipeline/create-processor :test/processor-observer
  [id]
  (reify p/Processor
    (processor-id [_] id)
    (processor-schema [_] :any)
    (accepted-frames [_] #{:frame.llm/tool-request})
    (make-processor-config [_ _ processor-config] processor-config)
    (process-frame [_ pipeline _ frame]
      (swap! pipeline update-in [:test/processor :processed-frames] (fn [pf] (conj (or pf []) frame))))))

(facts
  "About the openai llm"

  (fact
    "processor puts a tool-request-frame on the pipeline"
    (with-redefs [sut/stream-openai-chat-completion (fn [_params]
                                                      (let [stream-ch (a/chan 1024)]
                                                        (a/onto-chan! stream-ch mock/mock-tool-call-response)
                                                        stream-ch))]
      (let [in (a/chan 1024)
            out (a/chan 1024)
            pipeline (ts/create-test-pipeline
                       {:transport/in-ch in
                        :transport/out-ch out}
                       [{:processor/id :test/processor-observer}
                        {:processor/id :processor.llm/openai
                         :processor/config {:openai/api-key "sk-123123123123123123123123123123123123123123123123"}}])]
        (pipeline/start-pipeline! pipeline)
        (pipeline/send-frame! pipeline (frame/llm-context {:messages [{:role :user :content "hello"}]}))
        (a/<!! (a/timeout 200)) ;; let llm process the tool call chunks
        (let [frame (get-in @pipeline [:test/processor :processed-frames 0])]
          (frame/llm-tools-call-request? frame) => true
          (:frame/data frame)  => {:arguments {:date "2023-10-10" :fields ["price" "volume"] :ticker "MSFT"}
                                   :function-name "retrieve_latest_stock_data"
                                   :tool-call-id "call_frPVnoe8ruDicw50T8sLHki7"})

        (pipeline/stop-pipeline! pipeline)))))
