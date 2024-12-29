(ns voice-fn.processors.deepgram
  (:require
   [clojure.core.async :as a]
   [hato.websocket :as ws]
   [taoensso.telemere :as t]
   [voice-fn.frames :as frames]
   [voice-fn.pipeline :refer [close-processor! process-frame]]
   [voice-fn.utils.core :as u])
  (:import
   (java.nio HeapCharBuffer)))

(def ^:private deepgram-url "wss://api.deepgram.com/v1/listen")

(def deepgram-encoding
  "Mapping from clojure sound encoding to deepgram notation"
  {:pcm-signed :linear16
   :ulaw :mulaw
   :alaw :alaw})

(def deepgram-default-config {:encoding "linear16"
                              :language "ro"
                              :sample_rate 16000
                              :interim_results false
                              :punctuate true
                              :model "nova-2"})

(defn make-deepgram-url
  [{:audio-in/keys [sample-rate encoding] :pipeline/keys [language]}
   {:transcription/keys [interim-results? punctuate? model]
    :or {interim-results? false
         punctuate? false}}]
  (u/append-search-params deepgram-url {:encoding (deepgram-encoding encoding)
                                        :language language
                                        :sample_rate sample-rate
                                        :model model
                                        :interim_results interim-results?
                                        :punctuate punctuate?}))

(defn transcript?
  [m]
  (= (:event m) "transcript"))

(defn- transcript
  [m]
  (-> m :channel :alternatives first :transcript))

(defn final-transcription?
  [m]
  (and (transcript? m)
       (= (:type m) "final")))

(defn close-connection-payload
  []
  (u/json-str {:type "CloseStream"}))

(defn close-deepgram-websocket!
  [conn]
  (when conn
    (ws/send! conn (close-connection-payload))
    (ws/close! conn)))

(defn create-deepgram-connection!
  [pipeline-config processor-config {:keys [on-transcription on-close]}]
  (let [connection-config {:headers {"Authorization" (str "Token " (:transcription/api-key processor-config))}
                           :on-open (fn [_]
                                      (t/log! :info "Deepgram websocket connection open"))
                           :on-message (fn [_ws ^HeapCharBuffer data _last?]
                                         (let [m (u/parse-if-json (str data))
                                               trsc (transcript m)]
                                           (when (not= trsc "")
                                             (on-transcription trsc))))
                           :on-error (fn [_ e]
                                       (t/log! :error ["Error" e]))
                           :on-close (fn [ws code reason]
                                       (t/log! :info ["Deepgram websocket connection closed" "Code:" code "Reason" reason])
                                       (when (fn? on-close)
                                         (on-close ws)))}]
    (ws/websocket (make-deepgram-url pipeline-config processor-config) connection-config)))

(defn- close-websocket-connection!
  [pipeline]
  (close-deepgram-websocket! (get-in [:transcription/deepgram :websocket/conn] @pipeline))
  (swap! pipeline update-in [:transcription/deepgram] dissoc :websocket/conn))

(defmethod process-frame :transcription/deepgram
  [type pipeline processor frame]
  (let [on-close! (fn []
                    (t/log! :debug "Stopping transcription engine")
                    (close-websocket-connection! pipeline)
                    (close-processor! pipeline type))]
    (case (:frame/type frame)
      :system/start
      (let [_ (t/log! :debug "Starting transcription engine")
            ws-conn @(create-deepgram-connection!
                       (:pipeline/config @pipeline)
                       (:processor/config processor)
                       {:on-transcription (fn [text]
                                            (a/put! (:pipeline/main-ch @pipeline) (frames/text-input-frame text)))
                        :on-close on-close!})]
        (swap! pipeline assoc-in [type :websocket/conn] ws-conn))
      :system/stop (on-close!)

      :audio/raw-input
      (when-let [conn (get-in @pipeline [type :websocket/conn])]
        (ws/send! conn (:data frame))))))
