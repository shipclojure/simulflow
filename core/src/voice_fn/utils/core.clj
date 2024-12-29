(ns voice-fn.utils.core
  (:require
   [clojure.string :as str]
   [jsonista.core :as json])
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

(defonce json-object-mapper (json/object-mapper {:decode-key-fn true}))

(defn parse-if-json
  "Parses a string as JSON if possible, otherwise returns the string."
  [s]
  (try
    (json/read-value s json-object-mapper)
    (catch Exception _
      s)))

(defn json-str
  [m]
  (json/write-value-as-string m))

(defn search-params
  [url]
  (let [params (second (str/split url #"\?"))
        param-list (if params (str/split params #"\&") [])]
    (reduce #(let [[k v] (str/split %2 #"\=")]
               (assoc %1 (keyword k) v)) {} param-list)))

(defn strip-search-params
  [url]
  (first (str/split url #"\?")))

(defn append-search-params
  [url search-params-m]
  (let [search-params (merge (search-params url)
                             search-params-m)
        search (->> (map  (fn [[k v]] (str (name k) "=" (if (keyword? v) (name v) v))) search-params)
                    (str/join #"&")
                    (str "?"))]
    (str (strip-search-params url) search)))
