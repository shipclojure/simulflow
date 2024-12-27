(ns voice-fn.frames
  (:require
   [voice-fn.utils.core :refer [encode-base64]]))

(defn audio-input-frame [raw-data]
  {:type :audio/raw-input
   :data raw-data
   :ts (System/currentTimeMillis)})

(defn text-frame [text]
  {:type :text/input
   :text text
   :ts (System/currentTimeMillis)})
