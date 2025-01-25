(ns voice-fn.experiments.flow
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [clojure.datafy :refer [datafy]]
   [hato.websocket :as ws]
   [malli.core :as m]
   [malli.transform :as mt]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.processors.deepgram :as deepgram]
   [voice-fn.processors.elevenlabs :as xi]
   [voice-fn.processors.llm-context-aggregator :as ca]
   [voice-fn.processors.openai :as openai :refer [OpenAILLMConfigSchema]]
   [voice-fn.secrets :refer [secret]]
   [voice-fn.transport.serializers :refer [make-twilio-serializer]]
   [voice-fn.utils.core :as u])
  (:import
   (java.nio HeapCharBuffer)))

(t/set-min-level! :debug)

(def transport-in
  (flow/process
    {:describe (fn [] {:ins {:in "Channel for audio input "}
                       :outs {:sys-out "Channel for system messages that have priority"
                              :out "Channel on which audio frames are put"}})

     :transform (fn [state _ input]
                  (let [data (u/parse-if-json input)]
                    (case (:event data)
                      "start" (when-let [stream-sid (:streamSid data)]
                                [state {:sys-out [(frame/system-config-change {:twilio/stream-sid stream-sid
                                                                               :transport/serializer (make-twilio-serializer stream-sid)})]}])
                      "media"
                      [state {:out [(frame/audio-input-raw
                                      (u/decode-base64 (get-in data [:media :payload])))]}]

                      "close"
                      [state {:sys-out [(frame/system-stop true)]}]
                      nil)))}))

(def deepgram-processor
  (flow/process
    {:describe (fn [] {:ins {:sys-in "Channel for system messages that take priority"
                             :in "Channel for audio input frames (from transport-in) "}
                       :outs {:sys-out "Channel for system messages that have priority"
                              :out "Channel on which transcription frames are put"}
                       :params {:transcription/api-key "Api key required for deepgram connection"
                                :transcription/interim-results? "Wether deepgram should send interim transcriptions back"
                                :transcription/punctuate? "If transcriptions are punctuated or not. Not required if transcription/smart-format is true"
                                :transcription/vad-events? "Enable this for deepgram to send speech-start/utterance end events"
                                :transcription/smart-format? "Enable smart format"
                                :transcription/model "Model used for transcription"
                                :transcription/utterance-end-ms "silence time after speech in ms until utterance is considered ended"
                                :transcription/language "Language for speech"
                                :transcription/encoding "Audio encoding of the input audio"
                                :transcription/sample-rate "Sample rate of the input audio"}
                       :workload :io})
     :init (fn [args]
             (let [websocket-url (deepgram/make-websocket-url args)
                   ws-read-chan (a/chan 1024)
                   ws-write-chan (a/chan 1024)
                   alive? (atom true)
                   conn-config {:headers {"Authorization" (str "Token " (:transcription/api-key args))}
                                :on-open (fn [_]
                                           (t/log! :info "Deepgram websocket connection open"))
                                :on-message (fn [_ws ^HeapCharBuffer data _last?]
                                              (a/put! ws-read-chan (str data)))
                                :on-error (fn [_ e]
                                            (t/log! {:level :error :id :deepgram-transcriptor} ["Error" e]))
                                :on-close (fn [_ws code reason]
                                            (reset! alive? false)
                                            (t/log! {:level :info :id :deepgram-transcriptor} ["Deepgram websocket connection closed" "Code:" code "Reason:" reason]))}

                   _ (t/log! {:level :info :id :deepgram-transcriptor} "Connecting to transcription websocket")
                   ws-conn @(ws/websocket
                              websocket-url
                              conn-config)

                   write-to-ws #(loop []
                                  (when @alive?
                                    (when-let [msg (a/<!! ws-write-chan)]
                                      (cond
                                        (and (frame/audio-input-raw? msg) @alive?)
                                        (do
                                          (ws/send! ws-conn (:frame/data msg))
                                          (recur))))))
                   keep-alive #(loop []
                                 (when @alive?
                                   (a/<!! (a/timeout 3000))
                                   (t/log! {:level :debug :id :deepgram} "Sending keep-alive message")
                                   (ws/send! ws-conn deepgram/keep-alive-payload)
                                   (recur)))]
               ((flow/futurize write-to-ws :exec :io))
               ((flow/futurize keep-alive :exec :io))

               {:websocket/conn ws-conn
                :websocket/alive? alive?
                ::flow/in-ports {:ws-read ws-read-chan}
                ::flow/out-ports {:ws-write ws-write-chan}}))

     ;; Close ws when pipeline stops
     :transition (fn [{:websocket/keys [conn] :as state} transition]
                   (t/log! {:level :debug} ["TRANSITION" transition])
                   (when (= transition ::flow/stop)
                     (t/log! {:id :deepgram-transcriptor :level :info} "Closing transcription websocket connection")
                     (reset! (:websocket/alive? state) false)
                     (when conn
                       (ws/send! conn deepgram/close-connection-payload)
                       (ws/close! conn))

                     state)
                   state)

     :transform (fn [state in-name msg]
                  (if (= in-name :ws-read)
                    (let [m (u/parse-if-json msg)
                          frames (deepgram/deepgram-event->frames m)]
                      [state {:out frames}])
                    (cond
                      (frame/audio-input-raw? msg)
                      [state {:ws-write [msg]}]
                      :else [state])))}))

(def elevenlabs-tts-process
  (flow/process
    {:describe (fn [] {:ins {:sys-in "Channel for system messages that take priority"
                             :in "Channel for audio input frames (from transport-in) "}
                       :outs {:sys-out "Channel for system messages that have priority"
                              :out "Channel on which transcription frames are put"}
                       :params {:elevenlabs/api-key "Api key required for 11labs connection"
                                :elevenlabs/model-id "Model used for voice generation"
                                :elevenlabs/voice-id "Voice id"
                                :voice/stability "Optional voice stability factor (0.0 to 1.0)"
                                :voice/similarity-boost "Optional voice similarity boost factor (0.0 to 1.0)"
                                :voice/use-speaker-boost? "Wether to enable speaker boost enchancement"
                                :flow/language "Language to use"
                                :audio.out/encoding "Encoding for the audio generated"
                                :audio.out/sample-rate "Sample rate for the audio generated"}
                       :workload :io})
     :init (fn [args]
             (let [url (xi/make-elevenlabs-ws-url args)
                   ws-read (a/chan 100)
                   ws-write (a/chan 100)
                   alive? (atom true)
                   conf {:on-open (fn [ws]
                                    (let [configuration (xi/begin-stream-message args)]
                                      (t/log! :info ["Elevenlabs websocket connection open. Sending configuration message" configuration])
                                      (ws/send! ws configuration)))
                         :on-message (fn [_ws ^HeapCharBuffer data _last?]
                                       (a/put! ws-read (str data)))
                         :on-error (fn [_ e]
                                     (t/log! :error ["Elevenlabs websocket error" (ex-message e)]))
                         :on-close (fn [_ws code reason]
                                     (reset! alive? false)
                                     (t/log! :info ["Elevenlabs websocket connection closed" "Code:" code "Reason:" reason]))}
                   _ (t/log! {:level :info :id :deepgram-transcriptor} "Connecting to transcription websocket")
                   ws-conn @(ws/websocket
                              url
                              conf)

                   write-to-ws #(loop []
                                  (when @alive?
                                    (when-let [msg (a/<!! ws-write)]
                                      (cond
                                        (and (frame/speak-frame? msg) @alive?)
                                        (do
                                          (ws/send! ws-conn (xi/text-message (:frame/data msg)))
                                          (recur))))))
                   keep-alive #(loop []
                                 (when @alive?
                                   (a/<!! (a/timeout 3000))
                                   (t/log! {:level :debug :id :elevenlabs} "Sending keep-alive message")
                                   (ws/send! ws-conn xi/keep-alive-message)
                                   (recur)))]
               ((flow/futurize write-to-ws :exec :io))
               ((flow/futurize keep-alive :exec :io))

               {:websocket/conn ws-conn
                :websocket/alive? alive?
                ::flow/in-ports {:ws-read ws-read}
                ::flow/out-ports {:ws-write ws-write}}))
     :transition (fn [{:websocket/keys [conn] :as state} transition]
                   (when (= transition ::flow/stop)
                     (t/log! {:id :elevenlabs :level :info} "Closing tts websocket connection")
                     (reset! (:websocket/alive? state) false)
                     (when conn
                       (ws/send! conn xi/close-stream-message)
                       (ws/close! conn)))
                   state)

     :transform (fn [{:audio/keys [acc] :as state} in-name msg]
                  (if (= in-name :ws-read)
                    ;; xi sends one json response in multiple events so it needs
                    ;; to be concattenated until the final json can be parsed
                    (let [attempt (u/parse-if-json (str acc msg))]
                      (if (map? attempt)
                        [(assoc state :audio/acc "") (when-let [audio (:audio attempt)]
                                                       {:out (frame/audio-output-raw (u/decode-base64 audio))})]
                        ;; continue concatenating
                        [(assoc state :audio/acc attempt)]))
                    (cond
                      (frame/speak-frame? msg)
                      [state {:ws-write [msg]}]
                      :else [state])))}))

(def context-aggregator-process
  (flow/process
    {:describe (fn [] {:ins {:in "Channel for aggregation messages"}
                       :outs {:out "Channel where new context aggregations are put"}})
     :params {:llm/context "Initial LLM context. See schema/LLMContext"
              :messages/role "Role that this processor aggregates"
              :aggregator/start-frame? "Predicate checking if the frame is a start-frame?"
              :aggregator/end-frame? "Predicate checking if the frame is a end-frame?"
              :aggregator/accumulator-frame? "Predicate checking the main type of frame we are aggregating"
              :aggregator/interim-results-frame? "Optional predicate checking if the frame is an interim results frame"
              :aggregator/handles-interrupt? "Optional Wether this aggregator should handle or not interrupts"
              :aggregator/debug? "Optional When true, debug logs will be called"}
     :workload :compute
     :init identity
     :transform ca/aggregator-transform}))

(def openai-llm-process
  (flow/process
    {:describe (fn [] {:ins {:in "Channel for incoming context aggregations"}
                       :outs {:out "Channel where streaming responses will go"}})
     :parmas {:llm/model "Openai model used"
              :openai/api-key "OpenAI Api key"
              :llm/temperature "Optional temperature parameter for the llm inference"
              :llm/max-tokens "Optional max tokens to generate"
              :llm/presence-penalty "Optional (-2.0 to 2.0)"
              :llm/top-p "Optional nucleus sampling threshold"
              :llm/seed "Optional seed used for deterministic sampling"
              :llm/max-completion-tokens "Optional Max tokens in completion"
              :llm/extra "Optional extra model parameters"}
     :workload :io
     :init (fn [params]
             (let [state (m/decode OpenAILLMConfigSchema params mt/default-value-transformer)
                   llm-write (a/chan 100)
                   llm-read (a/chan 1024)
                   write-to-llm #(loop []
                                   (t/log! {:level :info :id :llm} "Starting LLM loop")
                                   (if-let [msg (a/<!! llm-write)]
                                     (do
                                       (t/log! {:level :debug :id :llm} ["LLM CONTEXT" msg])
                                       (assert (or (frame/llm-context? msg)
                                                   (frame/control-interrupt-start? msg)) "Invalid frame sent to LLM. Only llm-context or interrupt-start")
                                       (openai/flow-do-completion! state llm-read msg)
                                       (recur))
                                     (t/log! {:level :info :id :llm} "Closing llm loop")))]
               ((flow/futurize write-to-llm :exec :io))
               {::flow/in-ports {:llm-read llm-read}
                ::flow/out-ports {:llm-write llm-write}}))

     :transform (fn [state in msg]
                  (if (= in :llm-read)
                    [state {:out [msg]}]
                    (cond
                      (frame/llm-context? msg)
                      [state {:llm-write [msg]}])))}))

(defn sentence-assembler
  ([] {:ins {:in "Channel for llm text chunks"}
       :outs {:out "Channel for assembled speak frames"}})
  ([_] {:acc nil})
  ([{:keys [acc]} msg]
   (when (frame/llm-text-chunk? msg)
     (let [{:keys [sentence accumulator]} (u/assemble-sentence acc (:frame/data msg))]
       (if sentence
         [{:acc accumulator} {:out [(frame/speak-frame sentence)]}]
         [{:acc accumulator}])))))

(def gdef
  {:procs
   {:transport-in {:proc transport-in}
    :deepgram-transcriptor {:proc deepgram-processor
                            :args {:transcription/api-key (secret [:deepgram :api-key])
                                   :transcription/interim-results? true
                                   :transcription/punctuate? false
                                   :transcription/vad-events? true
                                   :transcription/smart-format? true
                                   :transcription/model :nova-2
                                   :transcription/utterance-end-ms 1000
                                   :transcription/language :en
                                   :transcription/encoding :mulaw
                                   :transcription/sample-rate 8000}}
    :user-context-aggregator  {:proc context-aggregator-process
                               :args {:messages/role "user"
                                      :llm/context {:messages [{:role :assistant :content "You are a helpful assistant"}]}
                                      :aggregator/start-frame? frame/user-speech-start?
                                      :aggregator/end-frame? frame/user-speech-stop?
                                      :aggregator/accumulator-frame? frame/transcription?
                                      :aggregator/interim-results-frame? frame/transcription-interim?
                                      :aggregator/handles-interrupt? false}} ;; User speaking shouldn't be interrupted
    :assistant-context-aggregator {:proc context-aggregator-process
                                   :args {:messages/role "assistant"
                                          :llm/context {:messages [{:role :assistant :content "You are a helpful assistant"}]}
                                          :aggregator/start-frame? frame/llm-full-response-start?
                                          :aggregator/end-frame? frame/llm-full-response-end?
                                          :aggregator/accumulator-frame? frame/llm-text-chunk?}}
    :llm {:proc openai-llm-process
          :args {:openai/api-key (secret [:openai :new-api-sk])
                 :llm/model "gpt-4o-mini"}}

    :llm-sentence-assembler {:proc (flow/step-process #'sentence-assembler)}
    :tts {:proc elevenlabs-tts-process
          :args {:elevenlabs/api-key (secret [:elevenlabs :api-key])
                 :elevenlabs/model-id "eleven_flash_v2_5"
                 :elevenlabs/voice-id "7sJPxFeMXAVWZloGIqg2"
                 :voice/stability 0.5
                 :voice/similarity-boost 0.8
                 :voice/use-speaker-boost? true}}

    :print-sink {:proc (flow/process
                         {:describe (fn [] {:ins {:in "Channel for receiving transcriptions"}})
                          :transform (fn [_ _ frame]
                                       (when (frame/audio-output-raw? frame)
                                         (t/log! {:id :print-sink :level :info} ["RESULT: " (:frame/data frame)])))})}}

   :conns [[[:transport-in :sys-out] [:deepgram-transcriptor :sys-in]]
           [[:transport-in :out] [:deepgram-transcriptor :in]]
           [[:deepgram-transcriptor :out] [:user-context-aggregator :in]]
           [[:user-context-aggregator :out] [:llm :in]]
           [[:llm :out] [:assistant-context-aggregator :in]]

           ;; cycle so that context aggregators are in sync
           [[:assistant-context-aggregator :out] [:user-context-aggregator :in]]
           [[:user-context-aggregator :out] [:assistant-context-aggregator :in]]

           [[:llm :out] [:llm-sentence-assembler :in]]
           [[:llm-sentence-assembler :out] [:tts :in]]

           [[:tts :out] [:print-sink :in]]]})

(comment
  (datafy (:proc (:deepgram-transcriptor (:procs gdef))))

  (def g (flow/create-flow gdef))

  (def res (flow/start g))

  ;; TODO When weird things happen, check the error & report channels
  res

  (flow/resume g)
  (flow/stop g)

  ,)
