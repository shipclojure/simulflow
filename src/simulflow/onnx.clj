(ns simulflow.onnx
  "Namespace with various utils for the Onnx framework "
  (:import
   (ai.onnxruntime OrtEnvironment OrtSession)
   (java.util Map)))

(defn inspect-model [^String model-path]
  (let [env (OrtEnvironment/getEnvironment)
        session ^OrtSession (.createSession env model-path)]
    (println "Input names and shapes:")
    (doseq [input ^Map (.getInputInfo session)]
      (println "  " (.getKey input) "->" (.getValue input)))
    (println "Output names and shapes:")
    (doseq [output (.getOutputInfo session)]
      (println "  " (.getKey output) "->" (.getValue output)))
    (.close session)))

(comment
  (inspect-model "resources/silero_vad.onnx"))
