(ns simulflow.secrets
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(defn- secret-map
  []
  (edn/read-string (slurp (io/resource "secrets.edn"))))

(defn secret
  [path]
  (get-in (secret-map) path))
