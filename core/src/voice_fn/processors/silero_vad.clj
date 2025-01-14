(ns voice-fn.processors.silero-vad
  (:require
   [clojure.java.data :as j]
   [clojure.java.io :as io])
  (:import
   (ai.onnxruntime OrtEnvironment)))

(def env (OrtEnvironment/getEnvironment))

(take 10 (.getBytes (slurp (io/resource "silero_vad.onnx"))))

(comment

  (def session (.createSession env (.getBytes (slurp (io/resource "silero_vad.onnx")))))

  ,)
