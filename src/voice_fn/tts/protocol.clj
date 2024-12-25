(ns voice-fn.tts.protocol
  (:require
   [clojure.core.async :as a]))

(defprotocol TTS
  (speak))
