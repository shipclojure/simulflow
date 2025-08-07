(ns simulflow.transport
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [simulflow.frame :as frame]
   [simulflow.transport.in :as in]
   [simulflow.transport.out :as out]
   [simulflow.utils.audio :as audio]
   [simulflow.utils.core :as u :refer [defaliases]]))

(defn async-in-transform
  [state _ input]
  (if (bytes? input)
    [state {:out [(frame/audio-input-raw input)]}]
    [state]))

;; =============================================================================
;; Processors

(defn split-audio-into-chunks
  "Split audio byte array into chunks of specified size.
   Returns vector of byte arrays, each of chunk-size or smaller for the last chunk."
  [audio-data chunk-size]
  (when (and audio-data chunk-size (pos? chunk-size) (pos? (count audio-data)))
    (loop [audio audio-data
           chunks []]
      (let [audio-size (count audio)
            chunk-actual-size (min chunk-size audio-size)
            chunk (byte-array chunk-actual-size)]
        ;; Copy chunk-size amount of data into next chunk
        (System/arraycopy audio 0 chunk 0 chunk-actual-size)
        (if (> audio-size chunk-actual-size)
          (let [new-audio-size (- audio-size chunk-actual-size)
                remaining-audio (byte-array new-audio-size)]
            (System/arraycopy audio chunk-actual-size remaining-audio 0 new-audio-size)
            (recur remaining-audio (conj chunks chunk)))
          ;; No more chunks to process, return final result
          (conj chunks chunk))))))

(defn audio-splitter-config
  "Calculate and validate audio splitter configuration.
   Returns map with validated :audio.out/chunk-size."
  [{:audio.out/keys [chunk-size sample-rate sample-size-bits channels duration-ms] :as config}]
  (assert (or chunk-size (and sample-rate sample-size-bits channels duration-ms))
          "Either provide :audio.out/chunk-size or sample-rate, sample-size-bits, channels and chunk duration for the size to be computed")
  (assoc config :audio.out/chunk-size
         (or chunk-size
             (audio/audio-chunk-size {:sample-rate sample-rate
                                      :sample-size-bits sample-size-bits
                                      :channels channels
                                      :duration-ms duration-ms}))))

(defn audio-splitter-fn
  "Audio splitter processor function with multi-arity support."
  ([] {:ins {:in "Channel for raw audio frames"}
       :outs {:out "Channel for audio frames split by chunk size"}
       :params {:audio.out/chunk-size "The chunk size by which to split each audio frame. Specify either this or the other parameters so that chunk size can be computed"
                :audio.out/sample-rate "Sample rate of the output audio"
                :audio.out/sample-size-bits "Size in bits for each sample"
                :audio.out/channels "Number of channels. 1 or 2 (mono or stereo audio)"
                :audio.out/duration-ms "Duration in ms of each chunk that will be streamed to output"}})
  ([config]
   (audio-splitter-config config))
  ([state _]
   ;; Transition function must return the state
   state)
  ([state _ frame]
   (cond
     (frame/audio-output-raw? frame)
     (let [{:audio.out/keys [chunk-size]} state
           audio-data (:frame/data frame)]
       (if-let [chunks (split-audio-into-chunks audio-data chunk-size)]
         [state {:out (mapv frame/audio-output-raw chunks)}]
         [state]))
     :else [state])))

(def audio-splitter
  "Takes in audio-output-raw frames and splits them up into :audio.out/duration-ms
  chunks. Chunks are split to achieve realtime streaming."
  (flow/process audio-splitter-fn))

(def async-transport-in
  "Takes in twilio events and transforms them into audio-input-raw and config
  changes."
  (flow/process
    (flow/map->step {:describe (fn [] {:outs {:out "Channel on which audio frames are put"}
                                       :params {:transport/in-ch "Channel from which input comes. Input should be byte array"}})

                     :transition (fn [{::flow/keys [in-ports out-ports]} transition]
                                   (when (= transition ::flow/stop)
                                     (doseq [port (remove nil? (concat (vals in-ports) (vals out-ports)))]
                                       (a/close! port))))

                     :init (fn [{:transport/keys [in-ch]}]
                             {::flow/in-ports {:in in-ch}})

                     :transform async-in-transform})))

;; Backward compatibility
(defaliases
  {:src out/realtime-out-describe}
  {:src out/realtime-out-init!}
  {:src out/realtime-out-transition}
  {:src out/realtime-out-transform}
  {:src out/realtime-out-fn}
  {:src out/realtime-out-processor}
  {:src out/realtime-speakers-out-describe}
  {:src out/realtime-speakers-out-init!}
  {:alias realtime-speakers-out-transition :src out/realtime-out-transition}
  {:alias realtime-speakers-out-transform :src out/realtime-out-transform}
  {:src out/realtime-speakers-out-fn}
  {:src out/realtime-speakers-out-processor}

  ;; twilio in
  {:src in/twilio-transport-in}
  {:src in/twilio-transport-in-describe}
  {:src in/twilio-transport-in-init!}
  {:src in/twilio-transport-in-transform}
  {:src in/twilio-transport-in-fn}

  ;; mic transport in
  {:src in/microphone-transport-in}
  {:src in/mic-transport-in-describe}
  {:src in/mic-transport-in-init!}
  {:src in/mic-transport-in-transform}
  {:src in/mic-transport-in-fn})
