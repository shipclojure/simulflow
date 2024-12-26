(ns voice-fn.transport.local.audio
  (:require
   [clojure.core.async :as a]
   [taoensso.telemere :as t]
   [uncomplicate.clojure-sound.core :refer [open! read! start!]]
   [uncomplicate.clojure-sound.sampled :refer [audio-format line line-info]]
   [voice-fn.frames :as frames])
  (:import
   (javax.sound.sampled AudioInputStream)))

(def audio-config-schema [:map [:audio-in/sample-rate :int
                                :audio-in/channels :int
                                :audio-in/sample-size-bits :int
                                :audio-out/sample-rate :int
                                :audio-out/bitrate :int
                                :audio-out/sample-size-bits :int
                                :audio-out/channels :int]])

(def local-audio-state-schema
  [:map
   [:running? :boolean]
   [:config audio-config-schema]])

(def default-config {:audio-in/sample-rate 16000
                     :audio-in/channels 1
                     :audio-in/sample-size-bits 16 ;; 2 bytes
                     :audio-out/sample-rate 24000
                     :audio-out/bitrate 96000
                     :audio-out/sample-size-bits 16
                     :audio-out/channels 1})

;; What does a transport input need?
;;
;; - a channel to which to put the frames
;; - an optional configuration for audio
;; - a start & stop implementation

(defn- frame-buffer-size
  "Get read buffer size based on the sample rate for input"
  [sample-rate]
  (* 2 (/ sample-rate 100)))

(defn start-local-audio-input!
  [state]
  (let [{:audio-in/keys [sample-rate channels sample-size-bits]} (:config @state)
        buffer-size (frame-buffer-size sample-rate)
        buffer (byte-array buffer-size)
        af (audio-format sample-rate sample-size-bits channels)
        _ (t/log! :info ["AudioFormat" af])
        li (line-info :target af)
        target-line (line li)
        _ (t/log! :info ["Line" target-line])
        _ (open! target-line)
        _ (start! target-line)
        is (AudioInputStream. target-line)]
    (a/go-loop []
      (let [count (read! is buffer 0 buffer-size)]
        (if-let  [ch (:audio-input-channel @state)]
          (do
            (when (pos? count)
              (a/>! ch (frames/->raw-audio-input-frame
                         (byte-array buffer)
                         :sample-rate sample-rate
                         :channels channels)))
            (recur))
          (t/log! {:level :info
                   :id :transport/audio-local-input} "Exiting audio loop"))))))

(comment
  (def state (atom {:config default-config
                    :audio-input-channel (a/chan 1096)}))

  (a/go-loop []
    (let [res (a/<! (:audio-input-channel @state))]
      (when res
        (t/log! {:level :info}
                ["Result" (:base64 res)])))
    (recur))

  (reset! state nil)

  (start-local-audio-input! state))
