(ns user
  (:require
   [clojure.java.io :as io]
   [malli.dev :as malli-dev]))

(defmacro jit [sym]
  `(requiring-resolve '~sym))

(defonce initiated-clj-reload? (atom false))

(defn reset
  []
  (when-not @initiated-clj-reload?
    ((jit clj-reload.core/init) {:dirs ["src" "dev" "test" "../examples/src"]})
    (reset! initiated-clj-reload? true))
  ((jit clj-reload.core/reload)))

(defn export-types []
  ;; collect schemas and start instrumentation
  ((jit malli-dev/start!))

  ;; create export file
  (def export-file
    (io/file "resources/clj-kondo.exports/com.shipclojure/simulflow_types/config.edn"))

  ;; make parents if not exist
  (io/make-parents export-file)

  ;; copy the configs
  (io/copy
    (io/file ".clj-kondo/metosin/malli-types-clj/config.edn")
    export-file)

  ;; clear the cache and stop instrumentation
  ((jit malli-dev/stop!)))

(comment
  (export-types)
  ,)
