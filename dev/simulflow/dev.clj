(ns simulflow.dev
  (:require
   [clj-reload.core :as reload]
   [clojure.java.io :as io]
   [malli.dev :as malli-dev]
   [taoensso.telemere :as t]))

(t/set-min-level! :debug)

(defn enable-reflection-warnings!
  "Enable reflection warnings for all loaded namespaces and set as default for new ones."
  []
  ;; Set default for new namespaces
  (alter-var-root #'*warn-on-reflection* (constantly true))

  ;; Enable for all currently loaded namespaces
  (doseq [ns-sym (all-ns)]
    (binding [*ns* ns-sym]
      (set! *warn-on-reflection* true)))

  (println "Reflection warnings enabled for all namespaces"))

;; Enable reflection warnings by default in dev
(enable-reflection-warnings!)

(defmacro jit [sym]
  `(requiring-resolve '~sym))

(defonce initiated-clj-reload? (atom false))

(defn reset
  []
  (when-not @initiated-clj-reload?
    (reload/init {:dirs ["src" "dev" "test" "../examples/src"]})
    (reset! initiated-clj-reload? true))
  (reload/reload))

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

(comment ;; s-:

  (reset)
  ,)

(comment
  (export-types)
  ,)
