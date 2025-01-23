(ns voice-fn.lab
  "This is a gist demonstrating basic usage of clojure.core.async.flow"
  (:require
   [clojure.core.async :as async]
   [clojure.core.async.flow :as flow]
   [clojure.pprint :as pp]))

;; =============== play with flow ==============

(defn monitoring [{:keys [report-chan error-chan]}]
  (prn "========= monitoring start")
  (async/thread
    (loop []
      (let [[val port] (async/alts!! [report-chan error-chan])]
        (if (nil? val)
          (prn "========= monitoring shutdown")
          (do
            (prn (str "======== message from " (if (= port error-chan) :error-chan :report-chan)))
            (pp/pprint val)
            (recur))))))
  nil)

(defn ddupe
  ([] {:ins {:in "stuff"}
       :outs {:out "stuff w/o consecutive dupes"}})
  ([_] {:last nil})
  ([{:keys [last]} _ v]
   [{:last v} (when (not= last v) {:out [v]})]))

(def gdef
  {:procs
   {:dice-source
    {:proc (flow/process
             {:describe (fn [] {:outs {:out "roll the dice!"}})
              :introduce (fn [_]
                           (Thread/sleep 200)
                           [nil {:out [[(inc (rand-int 6)) (inc (rand-int 6))]]}])})}

    :craps-finder
    {:proc (-> #(when (#{2 3 12} (apply + %)) %) flow/lift1->step flow/step-process)}

    :dedupe
    {:proc (flow/step-process #'ddupe)}

    :prn-sink
    {:proc (flow/process
             {:describe (fn [] {:ins {:in "gimme stuff to print!"}})
              :transform (fn [_ _ v] (prn v))})}}
   :conns
   [[[:dice-source :out] [:dedupe :in]]
    [[:dedupe :out] [:craps-finder :in]]
    [[:craps-finder :out] [:prn-sink :in]]]})

(def g (flow/create-flow gdef))
(monitoring (flow/start g))
(flow/resume g) ;; wait a bit for craps to print
(flow/pause g)
(flow/inject g [:craps-finder :in] [[1 2] [2 1] [2 1] [6 6] [6 6] [4 3] [1 1]])
(flow/ping g)
(flow/stop g)

(def xdef
  {:procs
   {:source {:proc (flow/process
                     {:describe (fn [])})}}})
