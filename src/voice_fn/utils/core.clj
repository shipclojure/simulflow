(ns voice-fn.utils.core
  (:import
   (java.util Base64)))

(defmulti encode-base64 (fn [s] (class s)))

(defmethod encode-base64 String
  [s]
  (let [encoder (Base64/getEncoder)]
    (.encodeToString encoder (.getBytes s "UTF-8"))))

(defmethod encode-base64 (Class/forName "[B")
  [bytes]
  (let [encoder (Base64/getEncoder)]
    (.encodeToString encoder bytes)))

(defn decode-base64
  [s]
  (let [decoder (Base64/getDecoder)]
    (.decode decoder s)))
