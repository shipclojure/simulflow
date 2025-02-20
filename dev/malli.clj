(ns malli
  (:require
   [clojure.java.io :as io]
   [malli.dev :as malli-dev]))

(defn export-types []
  ;; collect schemas and start instrumentation
  (malli-dev/start!)

  ;; create export file
  (let [export-file (io/file "resources/clj-kondo.exports/com.shipclojure/voice-fn-types/config.edn")]

    ;; make parents if not exist
    (io/make-parents export-file)

    ;; copy the configs
    (io/copy
      (io/file ".clj-kondo/metosin/malli-types-clj/config.edn")
      export-file))

  ;; clear the cache and stop instrumentation
  (malli-dev/stop!))

(comment

  (export-types)

  ,)
