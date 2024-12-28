(ns voice-fn.frames
  (:require
   [voice-fn.utils.core :refer [encode-base64]]))

(defprotocol Frame
  (data [this] "Returns the data for this type of frame"))

(defrecord BaseFrame [type data ts]
  Frame
  (data [_] data))

(defn frame? [frame]
  (satisfies? Frame frame))

(defn create-frame [type data]
  (->BaseFrame type data (System/currentTimeMillis)))

(defn audio-input-frame [raw-data]
  (create-frame :audio/raw-input raw-data))

(defn text-frame [text]
  (create-frame :text/input text))
