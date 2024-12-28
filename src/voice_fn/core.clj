(ns voice-fn.core
  (:require
   [taoensso.telemere :as t]
   [voice-fn.pipeline :as pipeline]
   [voice-fn.processors.deepgram :as deepgram]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.transport.local.audio]))

(def pipeline
  {:pipeline/config {:audio-in/sample-rate 16000
                     :audio-in/encoding :pcm-signed
                     :audio-in/channels 1
                     :audio-in/file-path "test-voice.wav"
                     :audio-in/sample-size-bits 16 ;; 2 bytes
                     :audio-out/sample-rate 24000
                     :audio-out/bitrate 96000
                     :audio-out/sample-size-bits 16
                     :audio-out/channels 1
                     :pipeline/language :ro}
   :pipeline/processors [{:processor/type :transport/local-audio
                          :processor/accepted-frames #{:system/start :system/stop}
                          :processor/generates-frames #{:audio/raw-input}}
                         {:processor/type :transcription/deepgram
                          :processor/accepted-frames #{:system/start :system/stop :audio/raw-input}
                          :processor/generates-frames #{:text/input}
                          :processor/config {:transcription/api-key (secret [:deepgram :api-key])
                                             :transcription/interim-results? false
                                             :transcription/punctuate? false
                                             :transcription/model :nova-2}}
                         {:processor/type :log/text-input
                          :processor/accepted-frames #{:text/input}
                          :processor/config {}}]})

(defmethod pipeline/process-frame :log/text-input
  [_ _ _ frame]
  (t/log! {:level :info
           :id :log/text-input} ["Frame" (:data frame)]))

(t/set-min-level! :debug)

(comment
  (def p (pipeline/create-pipeline pipeline))

  (:pipeline/processors @p)

  (pipeline/start-pipeline! p)
  (pipeline/stop-pipeline! p)

  ,)
