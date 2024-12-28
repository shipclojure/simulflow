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
(defn create-pipeline [processors-config]
  (let [main-ch (chan 1024)
        main-pub (a/pub main-ch :type)
        pipeline (atom {:main-ch main-ch
                        :processors-config processors-config
                        :main-pub main-pub})]

    ;; Start each processor
    (doseq [{:keys [type accepted-frames]} processors-config]
      (let [processor-ch (chan 1024)]
        ;; Tap into main channel, filtering for accepted frame types
        (doseq [frame-type accepted-frames]
          (a/sub main-pub frame-type processor-ch))
        (swap! pipeline assoc-in [type :in-ch] processor-ch)))
    pipeline))

(defn start-pipeline!
  [pipeline]
  (t/log! :debug "Starting pipeline")
  (a/put! (:main-ch @pipeline) {:type :system/start})
  ;; Start each processor
  (doseq [{:keys [type] :as processor} (:processors-config @pipeline)]
    (go-loop []
      (when-let [frame (<! (get-in @pipeline [type :in-ch]))]
        (when-let [result (process-frame type pipeline processor frame)]
          ;; Put results back on main channel if the processor returned frames
          (when (frames/frame? result)
            (>! (:main-ch @pipeline) result)))
        (recur)))))

(defn stop-pipeline!
  [pipeline]
  (t/log! :debug "Stopping pipeline")
  (a/put! (:main-ch @pipeline) {:type :system/stop}))

(defn close-processor!
  [pipeline type]
  (t/log! {:level :debug
           :id type} "Closing processor")
  (a/close! (get-in @pipeline [type :in-ch])))
