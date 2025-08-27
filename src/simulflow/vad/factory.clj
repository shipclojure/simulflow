(ns simulflow.vad.factory
  "Standard vad processors supported by simulflow"
  (:require
   [simulflow.vad.silero :as silero]))

(def factory
  {:vad.analyser/silero silero/create-silero-vad})
