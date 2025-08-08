(ns simulflow.transport
  (:require
   [clojure.core.async.flow :as flow]
   [simulflow.frame :as frame]
   [simulflow.schema :as schema]
   [simulflow.transport.in :as in]
   [simulflow.transport.out :as out]
   [simulflow.utils.audio :as audio]
   [simulflow.utils.core :as u :refer [defaliases]]))

;; =============================================================================
;; Processors

;; =============================================================================
;; Schemas

(def AudioSplitterConfig
  "Configuration for audio splitter processor"
  [:map
   [:audio.out/duration-ms {:default 40
                            :description "Duration in milliseconds of each audio chunk"}
    [:int {:min 1 :max 1000}]]])

;; ============================================================================= 

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
  "Validate and apply defaults to audio splitter configuration."
  [config]
  (schema/parse-with-defaults AudioSplitterConfig config))

(defn audio-splitter-fn
  "Audio splitter processor function with multi-arity support."
  ([] {:ins {:in "Channel for raw audio frames"}
       :outs {:out "Channel for audio frames split by chunk size"}
       :params (schema/->describe-parameters AudioSplitterConfig)})
  ([config]
   (audio-splitter-config config))
  ([state _]
   ;; Transition function must return the state
   state)
  ([state _ frame]
   (cond
     (frame/audio-output-raw? frame)
     (let [{:audio.out/keys [duration-ms]} state
           {:keys [audio sample-rate]} (:frame/data frame)
           ;; Calculate chunk size based on frame's sample rate and configured duration
           chunk-size (audio/audio-chunk-size {:sample-rate sample-rate
                                               :channels 1 ; PCM is mono
                                               :sample-size-bits 16 ; PCM is 16-bit
                                               :duration-ms duration-ms})]
       (if-let [chunks (split-audio-into-chunks audio chunk-size)]
         ;; Create new frames preserving the sample rate from original frame
         [state {:out (mapv (fn [chunk-audio]
                              (frame/audio-output-raw {:audio chunk-audio
                                                       :sample-rate sample-rate}))
                            chunks)}]
         [state]))
     :else [state])))

(def audio-splitter
  "Takes in audio-output-raw frames and splits them up into :audio.out/duration-ms
  chunks. Chunks are split to achieve realtime streaming."
  (flow/process audio-splitter-fn))

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
  {:src in/mic-transport-in-fn}

  ;; async-transform-in
  {:src in/async-transport-in-describe}
  {:src in/async-transport-in-init!}
  {:src in/async-transport-in-fn})
