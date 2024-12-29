(ns voice-fn.transport.async
  (:require
   [clojure.core.async :as a]
   [taoensso.telemere :as t]
   [voice-fn.frames :as frames]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.transport.protocols :as tp]))

(defn- input->frame
  ([input]
   (if (frames/audio-input-frame? input)
     input
     (throw (ex-info "Input is not a valid input frame. Please provide a serializer"
                     {:input input
                      :cause :error/transport-missing-serializer}))))
  ([input serializer]
   (if (nil? serializer)
     (input->frame input)
     (if (frames/audio-input-frame? input)
       input
       (tp/deserialize-frame serializer input)))))

(defmethod pipeline/process-frame :transport/async-input
  [processor-type pipeline _ frame]
  (let [{:transport/keys [in-ch serializer]} (:pipeline/config @pipeline)
        running? (atom false)]
    (case (:frame/type frame)
      :system/start
      (do
        (t/log! {:level :info
                 :id processor-type} "Staring transport input")
        (reset! running? true)
        (a/go-loop []
          (when running?
            (when-let [input (a/<! in-ch)]
              (when-let [input-frame (try
                                       (input->frame input serializer)
                                       (catch clojure.lang.ExceptionInfo e
                                         (let [data (merge (ex-data e)
                                                           {:message (ex-message e)})]
                                           (a/>! (:pipeline/main-ch @pipeline) (frames/error-frame data)))))]
                (a/>! (:pipeline/main-ch @pipeline) input-frame))
              (recur)))))
      :system/stop
      (t/log! {:level :info
               :id processor-type} "Stopping transport input")
      (reset! running? false))))

(defmethod pipeline/process-frame :transport/async-output
  [_ pipeline _ frame]
  (let [{:transport/keys [out-ch serializer]} (:pipeline/config @pipeline)]
    (case (:frame/type frame)
      :audio/output (when-let [output (if serializer
                                        (tp/serialize-frame frame serializer)
                                        frame)]
                      (a/put! out-ch output))
      :system/stop (a/close! out-ch))))
