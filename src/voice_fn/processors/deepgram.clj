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

(def deepgram-default-config {:encoding "linear16"
                              :language "ro"
                              :sample_rate 16000
                              :interim_results false
                              :punctuate true
                              :model "nova-2"})

(def deepgram-url-encoding
  (u/append-search-params deepgram-url deepgram-default-config))

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
  (ws/send! conn (close-connection-payload))
  (ws/close! conn))

(defn create-deepgram-connection!
  [api-key & {:keys [on-transcription]}]
  (let [connection-config {:headers {"Authorization" (str "Token " api-key)}
                           :on-open (fn [_]
                                      (t/log! :info "Deepgram websocket connection open"))
                           :on-message (fn [_ws ^HeapCharBuffer data _last?]
                                         (let [m (u/parse-if-json (str data))
                                               trsc (transcript m)]
                                           (when (and (final-transcription? m)
                                                      (not= trsc "")
                                                      (fn? on-transcription))
                                             (on-transcription trsc))))
                           :on-error (fn [_ e]
                                       (t/log! :error ["Error" e]))
                           :on-close (fn [_ code reason]
                                       (t/log! :info ["Deepgram websocket connection closed" "Code:" code "Reason" reason]))}]
    (ws/websocket deepgram-url-encoding connection-config)))

(defn- close-connection!
  [state]
  (close-deepgram-websocket! (get-in [:transcription/deepgram :conn] @state))
  (swap! state update-in [:transcription/deepgram] dissoc :conn))

(defmethod process-frame :transcription/deepgram
  [type pipeline {:keys [api-key]} frame]
  (case (:type frame)
    :system/start
    (let [ws-conn (create-deepgram-connection!
                    api-key
                    :on-transcription
                    (fn [text]
                      (a/put! (:main-ch @pipeline) (frames/text-frame text))))]
      (swap! pipeline assoc-in [type :conn] ws-conn))

    :system/stop (do
                   (close-connection! pipeline)
                   (close-processor! pipeline type))

    :audio/raw-input
    (when-let [conn (get-in @pipeline [type :conn])]
      (ws/send! conn (:data frame)))))
