(ns simulflow.async
  (:require
   [clojure.core.async :refer [go-loop]]
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

(defmacro vthread
  "Executes body in a virtual thread (when available) or falls back to a regular
   thread pool. Returns immediately to the calling thread.

   Similar to core.async/thread but leverages virtual threads on Java 21+.

   Example:
   (vthread
     (let [result (http/get \"https://example.com\")]
       (process-result result)))"
  [& body]
  `((vfuturize (fn [] ~@body))))

(defmacro vthread-loop
  "Like (vthread (loop ...)). Executes the body in a virtual thread with a loop.

   Example:
   (vthread-loop [count 0]
     (when (< count 10)
       (process-item count)
       (recur (inc count))))"
  [bindings & body]
  `(vthread (loop ~bindings ~@body)))
