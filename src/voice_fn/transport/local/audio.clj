(ns voice-fn.transport.local.audio
  (:require
   [clojure.core.async :as a]
   [taoensso.telemere :as t]
   [uncomplicate.clojure-sound.core :refer [open! read! start!]]
   [uncomplicate.clojure-sound.sampled :refer [audio-format line line-info]]
   [voice-fn.frames :as frames]
   [voice-fn.pipeline :refer [close-processor! process-frame]])
  (:import
   (java.io File)
   (java.util Arrays)
   (javax.sound.sampled AudioFormat AudioSystem DataLine$Info TargetDataLine)))

(defn- calculate-chunk-size
  "Calculate bytes for ms miliseconds of audio based on format"
  [ms ^AudioFormat audio-format]
  (let [frame-size (.getFrameSize audio-format)
        frame-rate (.getFrameRate audio-format)]
    (* frame-size (int (/ (* frame-rate ms) 1000)))))

(defn start-audio-capture-file!
  "Reads from WAV file in 20ms chunks.
   Returns a channel that will receive byte arrays of audio data."
  [{:audio-in/keys [file-path]
    :or {file-path "input.wav"}}]
  (let [audio-file (File. file-path)
        audio-stream (AudioSystem/getAudioInputStream audio-file)
        audio-format (.getFormat audio-stream)
        chunk-size (calculate-chunk-size 20 audio-format)
        buffer (byte-array chunk-size)
        out-ch (a/chan 1024)
        running? (atom true)]

    (future
      (try
        (while (and @running?
                    (pos? (.read audio-stream buffer 0 chunk-size)))
          (let [audio-data (Arrays/copyOf buffer chunk-size)]
            (Thread/sleep 20) ; Simulate real-time playback
            (a/offer! out-ch audio-data)))
        (catch Exception e
          (a/put! out-ch {:error e}))
        (finally
          (.close audio-stream)
          (a/close! out-ch))))

    {:audio-chan out-ch
     :stop-fn #(do (reset! running? false)
                   (a/close! out-ch))}))

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
   :channels - Number of audio channels (default: 1)"
  ([] (start-audio-capture! {}))
  ([{:audio-in/keys [sample-rate sample-size-bits channels]
     :or {sample-rate 16000
          channels 1
          sample-size-bits 16}}]
   (let [buffer-size (frame-buffer-size sample-rate)
         af (audio-format sample-rate sample-size-bits channels)
         line (open-microphone! af)
         out-ch (a/chan 1024)
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
  [processor-type pipeline _ frame]
  (case (:frame/type frame)
    :system/start
    (do
      (t/log! :debug "Starting audio capture")
      (let [{:keys [audio-chan stop-fn]} (start-audio-capture-file! (:pipeline/config @pipeline))]
        ;; Store stop-fn in state for cleanup
        (swap! pipeline assoc-in [:transport/local-audio :stop-fn] stop-fn)
        ;; Start sending audio frames
        (a/go-loop []
          (when-let [data (a/<! audio-chan)]
            (a/>! (:pipeline/main-ch @pipeline) (frames/audio-input-frame data))
            (recur)))))

    :system/stop
    (do
      (t/log! :debug "Stopping audio capture")
      (when-let [stop-fn (get-in @pipeline [:transport/local-audio :stop-fn])]
        (stop-fn)))
    (close-processor! pipeline processor-type))
  nil)
