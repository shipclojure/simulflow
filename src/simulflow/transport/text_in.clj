(ns simulflow.transport.text-in
  (:refer-clojure :exclude [send])
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [clojure.string :as str]
   [simulflow.async :refer [vthread-loop]]
   [simulflow.frame :as frame]
   [taoensso.telemere :as t])
  (:import
   (java.io BufferedReader InputStreamReader)))

(defn text-input-transform
  "Transform function handles stdin input and LLM response lifecycle for blocking"
  [state input-port frame]
  (let [{:keys [blocked?]} state]
    (cond
      ;; Handle LLM response lifecycle via :sys-in for blocking
      (and (= input-port :sys-in) (frame/llm-full-response-start? frame))
      [(assoc state :blocked? true) {}]
      (and (= input-port :sys-in) (frame/llm-full-response-end? frame))
      [(assoc state :blocked? false) {}]
      ;; Handle text input from stdin - transform decides whether to process
      (and (= input-port ::stdin) (string? frame))
      (if blocked?
        (do
          (t/log! {:level :debug :id :text-input} "Ignoring input while LLM is responding")
          [state {}])
        (let [user-text (str/trim frame)]
          (if (not-empty user-text)
            ;; Emit the standard speech sequence: start -> transcription -> stop
            [state (frame/send
                     (frame/user-speech-start true)
                     (frame/transcription user-text)
                     (frame/user-speech-stop true))]
            [state {}])))
      :else [state {}])))

(defn text-input-init!
  "Initialize text input processor with stdin reading loop (no prompt handling)"
  [params]
  (let [stdin-ch (a/chan 1024)
        running? (atom true)
        ;; Create buffered reader for stdin
        reader (BufferedReader. (InputStreamReader. System/in))
        ;; Close function to be called on stop
        close-fn (fn []
                   (reset! running? false)
                   (try (.close reader) (catch Exception _))
                   (a/close! stdin-ch))]
    ;; Start stdin reading loop in virtual thread (no prompts)
    (vthread-loop []
      (when @running?
        (try
          ;; Block on stdin read - this is safe in virtual threads
          (when-let [line (.readLine reader)]
            (when @running?
              (when-not (a/offer! stdin-ch line)
                (t/log! :warn "stdin channel full, dropping input"))))
          (catch Exception e
            (if @running?
              (do
                (t/log! {:level :error :id :text-input :error e} "Error reading from stdin")
                (Thread/sleep 100))
              ;; Normal shutdown, don't log error
              nil)))
        (recur)))
    ;; Set up state with channels
    {::flow/in-ports {::stdin stdin-ch}
     ::close close-fn
     :blocked? false}))

(defn text-input-processor
  "Multi-arity text input processor following simulflow patterns"
  ([]
   ;; Describe processor
   {:ins {:in "Text input frames"
          :sys-in "System frames (LLM response lifecycle)"}
    :outs {:out "User speech frames (transcription sequence)"
           :sys-out "System frames"}
    :params {}})
  ([config]
   ;; Init processor
   (text-input-init! config))
  ([state transition]
   ;; Handle transitions
   (case transition
     ::flow/stop (when-let [close-fn (::close state)]
                   (close-fn))
     state))
  ([state input-port frame]
   ;; Transform function
   (text-input-transform state input-port frame)))

;; Export for use in flows
(def text-input-process
  (flow/process text-input-processor))
