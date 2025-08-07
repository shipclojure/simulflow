(ns simulflow.onnx
  "Namespace with various utils for the Onnx framework "
  (:import
   (ai.onnxruntime OrtEnvironment)))

(defn inspect-model [model-path]
  (let [env (OrtEnvironment/getEnvironment)
        session (.createSession env model-path)]
    (println "Input names and shapes:")
    (doseq [input (.getInputInfo session)]
      (println "  " (.getKey input) "->" (.getValue input)))
    (println "Output names and shapes:")
    (doseq [output (.getOutputInfo session)]
      (println "  " (.getKey output) "->" (.getValue output)))
    (.close session)))

(comment
  (inspect-model "resources/silero_vad.onnx"))
