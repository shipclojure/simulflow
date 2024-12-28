(ns voice-fn.transport.local.audio
  (:require
   [clojure.core.async :as a]
   [taoensso.telemere :as t]
   [uncomplicate.clojure-sound.core :refer [open! read! start!]]
   [uncomplicate.clojure-sound.sampled :refer [audio-format line line-info]]
   [voice-fn.frames :as frames]
   [voice-fn.pipeline :refer [close-processor! process-frame]])
  (:import
   (java.util Arrays)
   (javax.sound.sampled AudioFormat AudioSystem DataLine$Info TargetDataLine)))

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

(defn line-supported?
  [^DataLine$Info info]
  (AudioSystem/isLineSupported info))

(defn open-microphone!
  "Opens the microphone with specified format. Returns the TargetDataLine."
  [^AudioFormat format]
  (let [info (line-info :target format)
        line (line info)]
    (when-not (line-supported? info)
      (throw (ex-info "Audio line not supported"
                      {:format format})))
    (open! line format)
    (start! line)
    line))

(defn- frame-buffer-size
  "Get read buffer size based on the sample rate for input"
  [sample-rate]
  (* 2 (/ sample-rate 100)))

(defn start-audio-capture!
  "Starts capturing audio from the microphone.
   Returns a channel that will receive byte arrays of audio data.

   Options:
   :sample-rate - The sample rate in Hz (default: 16000)
   :channels - Number of audio channels (default: 1)
   :buffer-size - Size of the buffer in bytes (default: 4096)
   :chan-buf-size - Size of the core.async channel buffer (default: 1024)"
  ([] (start-audio-capture! {}))
  ([{:keys [sample-rate sample-size-bits channels buffer-size chan-buf-size]
     :or {sample-rate 16000
          channels 1
          buffer-size (frame-buffer-size sample-rate)
          sample-size-bits 16
          chan-buf-size 1024}}]
   (let [af (audio-format sample-rate sample-size-bits channels)
         line (open-microphone! af)
         out-ch (a/chan chan-buf-size)
         buffer (byte-array buffer-size)
         running? (atom true)]

     ;; Start capture loop in a separate thread
     (future
       (try
         (while @running?
           (let [bytes-read (read! line buffer 0 buffer-size)]
             (when (pos? bytes-read)
               ;; Copy only the bytes that were read
               (let [audio-data (Arrays/copyOfRange buffer 0 bytes-read)]
                 ;; Put data on channel, but don't block if channel is full
                 (a/offer! out-ch audio-data)))))
         (catch Exception e
           (a/put! out-ch {:error e}))
         (finally
           (.stop ^TargetDataLine line)
           (.close ^TargetDataLine line)
           (a/close! out-ch))))

     ;; Return a map with the channel and a stop function
     {:audio-chan out-ch
      :stop-fn #(do (a/close! out-ch)
                    (reset! running? false))})))

(defmethod process-frame :transport/local-audio
  [processor-type pipeline config frame]
  (case (:type frame)
    :system/start
    (do
      (t/log! :debug "Starting audio capture")
      (let [{:keys [audio-chan stop-fn]} (start-audio-capture! config)]
        ;; Store stop-fn in state for cleanup
        (swap! pipeline assoc-in [:transport/local-audio :stop-fn] stop-fn)
        ;; Start sending audio frames
        (a/go-loop []
          (when-let [data (a/<! audio-chan)]
            (a/>! (:main-ch @pipeline) (frames/audio-input-frame data))
            (recur)))))

    :system/stop
    (do
      (t/log! :debug "Stopping audio capture")
      (when-let [stop-fn (get-in @pipeline [:transport/local-audio :stop-fn])]
        (stop-fn)))
    (close-processor! pipeline processor-type))
  nil)
