(ns simulflow.async
  (:require
   [clojure.core.async.flow :as flow])
  (:import
   (java.util.concurrent Executors)))

(def virtual-threads-supported?
  (try
    (Class/forName "java.lang.Thread$Builder$OfVirtual")
    true
    (catch ClassNotFoundException _
      false)))

(def ^:private virtual-executor
  (when virtual-threads-supported?
    (Executors/newVirtualThreadPerTaskExecutor)))

(defn vfuturize
  "Like flow/futurize but uses virtual threads when available (Java 21+),
   otherwise falls back to the specified executor type (default :mixed)"
  ([f & {:keys [exec]
         :or {exec :mixed}}]
   (if virtual-threads-supported?
     (flow/futurize f :exec virtual-executor)
     (flow/futurize f :exec exec))))
