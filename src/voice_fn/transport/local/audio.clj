(ns voice-fn.transport.local.audio
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [clojure.java.io :as io]
   [taoensso.telemere :as t]
   [uncomplicate.clojure-sound.core :refer [open! read! start!]]
   [uncomplicate.clojure-sound.sampled :refer [audio-format line line-info]]
   [voice-fn.frame :as frame])
  (:import
   (java.util Arrays)
   (javax.sound.sampled AudioFormat AudioSystem DataLine$Info TargetDataLine)))

(t/set-min-level! :debug)

(defn- calculate-chunk-size
  "Calculate bytes for ms miliseconds of audio based on format"
  [ms ^AudioFormat audio-format]
  (let [frame-size (.getFrameSize audio-format)
        frame-rate (.getFrameRate audio-format)]
    (int (* frame-size (int (/ (* frame-rate ms) 1000))))))

(defn start-audio-capture-file!
  "Reads from WAV file in 20ms chunks.
   Returns a channel that will receive byte arrays of audio data."
  [{:audio-in/keys [file-path]
    :or {file-path "input.wav"}}]
  (let [audio-file (io/resource file-path)
        audio-stream (AudioSystem/getAudioInputStream audio-file)
        audio-format (.getFormat audio-stream)
        chunk-size (calculate-chunk-size 20 audio-format)
        buffer (byte-array chunk-size)
        out-ch (a/chan 1024)
        read-file #(loop []
                     (when (pos? (.read audio-stream buffer 0 chunk-size))
                       (let [audio-data (Arrays/copyOf buffer chunk-size)]
                         (a/>!! out-ch (frame/audio-input-raw audio-data))
                         (recur))))]
    ((flow/futurize read-file :exec :io))
    {::flow/in-ports {:file-in out-ch}}))

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

(def local-transport-in
  (flow/process
    {:describe (fn [] {:outs {:out "Channel on which audio frames are put"}
                       :params {:audio-in/sample-rate "Sample rate for audio input. Default 16000"
                                :audio-in/channels "Channels for audio input. Default 1"
                                :audio-in/sample-size-bits "Sample size in bits. Default 16"}})
     :init (fn [{:audio-in/keys [sample-rate sample-size-bits channels]
                 :or {sample-rate 16000
                      channels 1
                      sample-size-bits 16}}]
             (let [buffer-size (frame-buffer-size sample-rate)
                   af (audio-format sample-rate sample-size-bits channels)
                   line (open-microphone! af)
                   out-ch (a/chan 1024)
                   buffer (byte-array buffer-size)
                   running? (atom true)
                   close #(do
                            (reset! running? false)
                            (a/close! out-ch)
                            (.stop ^TargetDataLine line)
                            (.close ^TargetDataLine line))
                   recording-loop #(do
                                     (while @running?
                                       (let [bytes-read (read! line buffer 0 buffer-size)]
                                         (when (pos? bytes-read)
                                           (let [audio-data (Arrays/copyOfRange buffer 0 bytes-read)]
                                             (a/>!! out-ch (frame/audio-input-raw audio-data)))))))]
               ((flow/futurize recording-loop :exec :io))
               {::flow/in-ports {:mic-in out-ch}
                :audio-in/sample-size-bits sample-size-bits
                :audio-in/sample-rate sample-rate
                :audio-in/channels channels
                ::close close}))
     :transition (fn [state transiton]
                   (when (and (= transiton ::flow/stop)
                              (fn? (::close state)))
                     (t/log! :info "Closing transport in")
                     ((::close state))))
     :transform (fn [state _ frame]
                  [state {:out [frame]}])}))
