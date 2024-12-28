(ns voice-fn.pipeline
  (:require
   [clojure.core.async :as a :refer [<! >! chan go-loop]]
   [taoensso.telemere :as t]
   [voice-fn.frames :as frames]))

(defmulti process-frame
  "Process a frame from the pipeline.
  - processor-type - type of processor: `:transport/local-audio` | `:transcription/deepgram`
  - pipeline - atom containing the state of the pipeline.
  - config - pipeline config
  - frame - the frame to be processed by the processor"
  {:arglists '([processor-type pipeline config frame])}
  (fn [processor-type state config frame]
    processor-type))

;; Pipeline creation logic here
(defn create-pipeline [pipeline-config]
  (let [main-ch (chan 1024)
        main-pub (a/pub main-ch :frame/type)
        pipeline (atom (merge {:pipeline/main-ch main-ch
                               :pipeline/main-pub main-pub}
                              pipeline-config))]

    ;; Start each processor
    (doseq [{:processor/keys [type accepted-frames]} (:pipeline/processors pipeline-config)]
      (let [processor-ch (chan 1024)]
        ;; Tap into main channel, filtering for accepted frame types
        (doseq [frame-type accepted-frames]
          (a/sub main-pub frame-type processor-ch))
        (swap! pipeline assoc-in [type :processor/in-ch] processor-ch)))
    pipeline))

(defn start-pipeline!
  [pipeline]
  (t/log! :debug "Starting pipeline")
  (a/put! (:pipeline/main-ch @pipeline) {:frame/type :system/start})
  ;; Start each processor
  (doseq [{:processor/keys [type] :as processor} (:pipeline/processors @pipeline)]
    (go-loop []
      (when-let [frame (<! (get-in @pipeline [type :processor/in-ch]))]
        (when-let [result (process-frame type pipeline processor frame)]
          ;; Put results back on main channel if the processor returned frames
          (when (frames/frame? result)
            (>! (:pipeline/main-ch @pipeline) result)))
        (recur)))))

(defn stop-pipeline!
  [pipeline]
  (t/log! :debug "Stopping pipeline")
  (a/put! (:pipeline/main-ch @pipeline) {:frame/type :system/stop}))

(defn close-processor!
  [pipeline type]
  (t/log! {:level :debug
           :id type} "Closing processor")
  (a/close! (get-in @pipeline [type :processor/in-ch])))
