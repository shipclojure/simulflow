(ns simulflow.processors.system-frame-router
  "Simple system frame router that collects frames on :sys-in and broadcasts on :sys-out.

  This processor acts as a fan-out hub for system frames within a single flow,
  eliminating the need for N×(N-1) connections between system frame producers and consumers."
  (:require
   [clojure.core.async.flow :as flow]
   [clojure.datafy :as datafy]
   [simulflow.frame :as frame]
   [taoensso.telemere :as t]))

(defn system-frame-router
  "Creates a system frame router processor.

  This processor:
  1. Receives all frames on its :sys-in port
  2. Forwards system frames to its :sys-out port
  3. Drops non-system frames with optional logging

  Usage in flow:
    :system-router {:proc system-frame-router}

  Connections:
    [[:producer :sys-out] [:system-router :sys-in]]
    [[:system-router :sys-out] [:consumer1 :sys-in]]
    [[:system-router :sys-out] [:consumer2 :sys-in]]
    [[:system-router :sys-out] [:consumer3 :sys-in]]"

  ([]
   ;; 0-arity: describe the processor
   {:ins {:sys-in "System frames from producers"}
    :outs {:sys-out "System frames broadcasted to consumers"}
    :params {:log-dropped? "Log when non-system frames are dropped"}})

  ([{:keys [log-dropped?] :or {log-dropped? false}}]
   ;; 1-arity: init processor state
   {:log-dropped? log-dropped?})

  ([state _transition]
   ;; 2-arity: handle state transitions (not used)
   state)

  ([state input-port frame]
   ;; 3-arity: transform function - main routing logic
   (cond
     (and (= input-port :sys-in) (some? frame))
     (if (frame/system-frame? frame)
       ;; Forward system
       [state {:sys-out [frame]}]
       ;; Drop non-system frames
       (do
         (when (:log-dropped? state)
           (t/log! {:level :debug :id :system-frame-router :frame-type (:frame/type frame)}
                   "Dropped non-system frame"))
         [state]))

     :else
     ;; Unknown input port or nil frame - no output
     [state {}])))

(def system-frame-router-process
  "Convenience function that wraps the system-frame-router in a flow/process.

  This is the standard way to create the processor for use in flows."
  (flow/process system-frame-router))

(defn contains-system-router?
  "Returns true if the flow-config contains the system-frame-router-process"
  [flow-config]
  (reduce-kv (fn [_ _ v]
               (if (= (:proc v) system-frame-router-process)
                 (reduced true)
                 false))
             false
             flow-config))

(defn generate-system-router-connections
  "Given a flow config's processor map, analyzes each processor's :proc with clojure.datafy/datafy
  to check if it has :sys-in and :sys-out channels in its :desc. Returns a vector
  of connection pairs that should be added to connect processors to the system-router.

  Assumes the system-router processor is named :system-router in the flow config.

  Returns connections in the format:
  [;; System frame producers -> router
   [[:producer-with-sys-out :sys-out] [:system-router :sys-in]]
   ;; Router -> system frame consumers
   [[:system-router :sys-out] [:consumer-with-sys-in :sys-in]]]"
  [flow-config-processors]
  (let [processor-analyses (for [[proc-name {:keys [proc]}] flow-config-processors
                                 :when (and proc (not= proc-name :system-router))]
                             (try
                               (let [desc (-> proc datafy/datafy :desc)]
                                 {:name proc-name
                                  :has-sys-in? (contains? (:ins desc) :sys-in)
                                  :has-sys-out? (contains? (:outs desc) :sys-out)})
                               (catch Exception e
                                 {:name proc-name
                                  :has-sys-in? false
                                  :has-sys-out? false
                                  :error (.getMessage e)})))

        sys-out-producers (filter :has-sys-out? processor-analyses)
        sys-in-consumers (filter :has-sys-in? processor-analyses)

        producer-connections (map (fn [{:keys [name]}]
                                    [[name :sys-out] [:system-router :sys-in]])
                                  sys-out-producers)

        consumer-connections (map (fn [{:keys [name]}]
                                    [[:system-router :sys-out] [name :sys-in]])
                                  sys-in-consumers)]

    (vec (concat producer-connections consumer-connections))))

(comment)
  ;; Usage in a flow - eliminates complex system frame connections

  ;; Before (complex N×N connections):
  ;; [[:activity-monitor :out] [:context-aggregator :in]]
  ;; [[:activity-monitor :out] [:tts :in]]
  ;; [[:transport-out :out] [:activity-monitor :in]]
  ;; [[:transport-out :out] [:context-aggregator :in]]

  ;; After (simple fan-out via system router):
  ;; {:procs {:system-router {:proc system-frame-router-process}
  ;;          :activity-monitor {:proc activity-monitor-process}
  ;;          :context-aggregator {:proc context-aggregator-process}
  ;;          :transport-out {:proc transport-out-process}
  ;;          :tts {:proc tts-process}}
  ;;
  ;;  :conns [;; System frame producers -> router
  ;;          [[:activity-monitor :out] [:system-router :sys-in]]
  ;;          [[:transport-out :out] [:system-router :sys-in]]
  ;;
  ;;          ;; Router -> system frame consumers
  ;;          [[:system-router :sys-out] [:activity-monitor :sys-in]]
  ;;          [[:system-router :sys-out] [:context-aggregator :sys-in]]
  ;;          [[:system-router :sys-out] [:tts :sys-in]]]}
