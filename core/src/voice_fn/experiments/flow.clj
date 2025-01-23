(ns voice-fn.experiments.flow
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [clojure.core.async.flow.impl :as impl]
   [clojure.core.async.flow.spi :as spi]
   [clojure.datafy :refer [datafy]]
   [clojure.pprint :as pprint]
   [hato.websocket :as ws]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.processors.deepgram :as deepgram]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.transport.serializers :refer [make-twilio-serializer]]
   [voice-fn.utils.core :as u])
  (:import
   (java.nio HeapCharBuffer)
   (java.util.concurrent TimeUnit)))

(t/set-min-level! :debug)

(def real-gdef
  {:procs
   {;; transport in receives base64 encoded audio and sends it to the next
    ;; processor (usually a transcription processor)
    :transport-in
    {:proc (flow/process
             {:describe (fn [] {:ins {:in "Channel for audio input "}
                                :outs {:sys-out "Channel for system messages that have priority"
                                       :out "Channel on which audio frames are put"}})

              :transform (fn [state _ input]
                           (let [data (u/parse-if-json input)]
                             (case (:event data)
                               "start" (when-let [stream-sid (:streamSid data)]
                                         [state {:system [(frame/system-config-change {:twilio/stream-sid stream-sid
                                                                                       :transport/serializer (make-twilio-serializer stream-sid)})]}])
                               "media"
                               [state {:out [(frame/audio-input-raw
                                               (u/decode-base64 (get-in data [:media :payload])))]}]

                               "close"
                               [state {:system [(frame/system-stop true)]}]
                               nil)))})}
    ;; transcription processor that will receive audio frames from transport in,
    ;; send them to ws-conn and send down to sink new transcriptions from websocket
    :deepgram-transcriptor
    {:proc
     (flow/process
       {:describe
        (fn [] {:ins {:sys-in "Channel for system messages that take priority"
                      :in "Channel for audio input frames (from transport-in) "}
                :outs {:sys-out "Channel for system messages that have priority"
                       :out "Channel on which transcription frames are put"}
                :params {:deepgram/api-key "Api key for deepgram"}
                :workload :io})
        :init
        (fn [args]
          (pprint/pprint args)
          (let [websocket-url (deepgram/make-websocket-url {:transcription/api-key (:deepgram/api-key args)
                                                            :transcription/interim-results? true
                                                            :transcription/punctuate? false
                                                            :transcription/vad-events? true
                                                            :transcription/smart-format? true
                                                            :transcription/model :nova-2
                                                            :transcription/utterance-end-ms 1000
                                                            :transcription/language :en
                                                            :transcription/encoding :mulaw
                                                            :transcription/sample-rate 8000})
                conn-config {:headers {"Authorization" (str "Token " (:deepgram/api-key args))}
                             :on-open (fn [ws]
                                        ;; Send a keepalive message every 3 seconds to maintain websocket connection
                                        #_(a/go-loop []
                                            (a/<! (a/timeout 3000))
                                            (when (get-in @pipeline [type :websocket/conn])
                                              (t/log! {:level :debug :id type} "Sending keep-alive message")
                                              (ws/send! ws keep-alive-payload)
                                              (recur)))
                                        (t/log! :info "Deepgram websocket connection open"))
                             :on-message (fn [_ws ^HeapCharBuffer data _last?]
                                           (let [m (u/parse-if-json (str data))]

                                             (cond
                                               (deepgram/speech-started-event? m)
                                               ;; (send-frame! pipeline (frame/user-speech-start true))
                                               (prn "Send speech started frame down the pipeline")

                                               (deepgram/utterance-end-event? m)
                                               ;; (send-frame! pipeline (frame/user-speech-stop true))
                                               (prn "Send speech stopped frame down the pipeline")

                                               (deepgram/final-transcript? m)
                                               ;; (send-frame! pipeline (frame/transcription trsc))
                                               (prn "send transcription frame down the pipeline")

                                               (deepgram/interim-transcript? m)
                                               ;; (send-frame! pipeline (send-frame! pipeline (frame/transcription-interim trsc)))
                                               (prn "send interim transcription frame down the pipeline"))))
                             :on-error (fn [_ e]
                                         (t/log! {:level :error :id :deepgram-transcriptor} ["Error" e]))
                             :on-close (fn [_ws code reason]
                                         (t/log! {:level :info :id :deepgram-transcriptor} ["Deepgram websocket connection closed" "Code:" code "Reason:" reason]))}
                _ (t/log! {:level :info :id :deepgram-transcriptor} "Connecting to transcription websocket")
                ws-conn @(ws/websocket
                           websocket-url
                           conn-config)]
            {:websocket/conn ws-conn}))

        ;; Close ws when pipeline stops
        :transition (fn [{:websocket/keys [conn] :as state} transition]
                      (if (and (= transition ::flow/stop)
                               conn)
                        (do
                          (t/log! {:id :deepgram-transcriptor :level :info} "Closing transcription websocket connection")
                          (ws/send! conn deepgram/close-connection-payload)
                          (ws/close! conn)
                          {})
                        state))

        :transform (fn [{:websocket/keys [conn]} in-name frame]
                     (cond
                       (frame/audio-input-raw? frame)
                       (when conn (ws/send! (:frame/data frame)))))})}
    :print-sink
    {:proc (flow/process
             {:describe (fn [] {:ins {:in "Channel for receiving transcriptions"}})
              :transform (fn [_ _ frame]
                           (when (frame/transcription? frame)
                             (t/log! {:id :print-sink :level :info} ["Transcript: " (:frame/data frame)])))})}}
   :conns [[[:transport-in :sys-out] [:deepgram-transcriptor :sys-in]]
           [[:transport-in :out] [:deepgram-transcriptor :in]]
           [[:deepgram-transcriptor :out] [:print-sink :in]]]
   :args {:deepgram/api-key (secret [:deepgram :api-key])}})

(defn raw-proc
  "see lib ns for docs"
  [{:keys [describe init transition transform introduce] :as impl} {:keys [workload compute-timeout-ms]}]
  (assert (= 1 (count (keep identity [transform introduce]))) "must provide exactly one of :transform or :introduce")
  (let [{:keys [params ins] :as desc} (describe)
        workload (or workload (:workload desc) :mixed)]
    (assert (not (and ins introduce)) "can't specify :ins when :introduce")
    (assert (or (not params) init) "must have :init if :params")
    (assert (not (and introduce (= workload :compute))) "can't specify :introduce and :compute")
    (reify
      clojure.core.protocols/Datafiable
      (datafy [_]
        (let [{:keys [params ins outs]} desc]
          {:impl impl :params (-> params keys vec) :ins (-> ins keys vec) :outs (-> outs keys vec)}))
      spi/ProcLauncher
      (describe [_] desc)
      (start [_ {:keys [pid args ins outs resolver]}]
        (let [comp? (= workload :compute)
              transform (cond-> transform (= workload :compute)
                                #(.get (impl/futurize transform {:exec (spi/get-exec resolver :compute)})
                                       compute-timeout-ms TimeUnit/MILLISECONDS))
              exs (spi/get-exec resolver (if (= workload :mixed) :mixed :io))
              io-id (zipmap (concat (vals ins) (vals outs)) (concat (keys ins) (keys outs)))
              control (::flow/control ins)
              ;; TODO rotate/randomize after control per normal alts?
              read-chans (into [control] (-> ins (dissoc ::flow/control) vals))
              run
              #(loop [status :paused, state (when init (init args)), count 0]
                 (let [pong (fn []
                              (let [pins (dissoc ins ::flow/control)
                                    pouts (dissoc outs ::flow/error ::flow/report)]
                                (a/>!! (outs ::flow/report)
                                       #::flow{:report :ping, :pid pid, :status status
                                       :state state, :count count
                                       :ins (zipmap (keys pins) (map impl/chan->data (vals pins)))
                                       :outs (zipmap (keys pouts) (map impl/chan->data (vals pouts)))})))
                   handle-command (partial impl/handle-command pid pong)
                   [nstatus nstate count]
                   (try
                     (if (= status :paused)
                       (let [nstatus (handle-command status (a/<!! control))
                             nstate (impl/handle-transition transition status nstatus state)]
                         [nstatus nstate count])
                       ;; :running
                       (let [[msg c] (if transform
                                       (a/alts!! read-chans :priority true)
                                       ;; introduce
                                       (when-let [msg (a/poll! control)]
                                         [msg control]))
                             cid (io-id c)]
                         (if (= c control)
                           (let [nstatus (handle-command status msg)
                                 nstate (impl/handle-transition transition status nstatus state)]
                             [nstatus nstate count])
                           (try
                             (let [[nstate outputs] (if transform
                                                      (transform state cid msg)
                                                      (introduce state))
                                   [nstatus nstate]
                                   (impl/send-outputs status nstate outputs outs
                                                      resolver control handle-command transition)]
                               [nstatus nstate (inc count)])
                             (catch Throwable ex
                               (a/>!! (outs ::flow/error)
                                      #::flow{:pid pid, :status status, :state state
                                      :count (inc count), :cid cid, :msg msg :op :step, :ex ex})
                             [status state count])))))
                   (catch Throwable ex
                     (a/>!! (outs ::flow/error)
                            #::flow{:pid pid, :status status, :state state, :count (inc count), :ex ex})
                   [status state count]))]
          (when-not (= nstatus :exit) ;; fall out
            (recur nstatus nstate (long count)))))]
    ((impl/futurize run {:exec exs})))))))

(comment
  (datafy (:proc (:deepgram-transcriptor (:procs real-gdef))))

  (def g (flow/create-flow real-gdef))

  (def res (flow/start g))

  (flow/resume g)
  (flow/stop g)

  ,)
