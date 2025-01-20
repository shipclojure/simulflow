(ns voice-fn.test-system
  (:require
   [clojure.test :refer [is]]
   [voice-fn.core]
   [voice-fn.frame :as frame]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.protocol :as p]))

;; Mock Processor Implementation
(defmethod pipeline/create-processor :test/processor
  [id]
  (reify p/Processor
    (processor-id [_] id)
    (processor-schema [_] [:map [:test/config :string]])
    (accepted-frames [_] #{:frame.test/type :frame.system/start :frame.system/stop})
    (make-processor-config [_ _ processor-config] processor-config)
    (process-frame [_ pipeline _ frame]
      (swap! pipeline update-in [:test/processor :processed-frames] (fn [pf] (conj (or pf []) frame)))
      (frame/create-frame :frame.test/processed (:frame/data frame)))))

(def default-pipeline-config {:audio-in/sample-rate 16000
                              :audio-in/encoding :pcm-signed
                              :audio-in/channels 2
                              :audio-in/sample-size-bits 16
                              :audio-out/sample-rate 16000
                              :audio-out/encoding :pcm-signed
                              :audio-out/sample-size-bits 16
                              :audio-out/channels 2
                              :pipeline/supports-interrupt? true
                              :pipeline/language :en
                              :llm/context {:messages [{:role "system" :content  "You are a helpful assistant. Respond concisely."}]}})

(defn create-test-pipeline
  ([config]
   (create-test-pipeline config []))
  ([config processors]
   (pipeline/create-pipeline {:pipeline/config (merge default-pipeline-config config)
                              :pipeline/processors processors})))

(defmacro valid-pipeline?
  "Tests if a pipeline instantiation is valid.
   Usage: (valid-pipeline? some-pipeline)

   Performs the following checks:
   - Pipeline exists and is not nil
   - Pipeline is an atom
   - Pipeline contains main channel
   - Pipeline contains system channel

   Returns true if all checks pass, throws assertion error otherwise."
  [pipeline-form]
  `(do
     (is (some? ~pipeline-form)
         (str "Pipeline should be created, but got: " ~pipeline-form))

     (is (instance? clojure.lang.Atom ~pipeline-form)
         (str "Pipeline should be an atom, but got: " (type ~pipeline-form)))

     (is (get-in (deref ~pipeline-form) [:pipeline/main-ch])
         (str "Pipeline should have main channel, but keys are: "
              (keys (deref ~pipeline-form))))

     (is (get-in (deref ~pipeline-form) [:pipeline/system-ch])
         (str "Pipeline should have system channel, but keys are: "
              (keys (deref ~pipeline-form))))

     true))
