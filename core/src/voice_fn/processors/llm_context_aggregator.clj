(ns voice-fn.processors.llm-context-aggregator
  (:require
   [clojure.core.async :as a]
   [voice-fn.frames :as f]
   [voice-fn.pipeline :as pipeline]))

concat

(defn concat-context
  "Concat to context a new message. If the last message from the context is from
  the same role, concattenate in the same context object.
  (concat-context [{:role :system :content \"Hello\"}] :system \", world\")
  ;; => [{:role :system :content \"Hello, world\"}]
  "
  ([context entry]
   (concat-context context (:role entry) (:content entry)))
  ([context role content]
   (let [last-entry (last context)]
     (if (= (name (:role last-entry)) (name role))
       (into (vec (butlast context))
             [{:role role
               :content (str (:content last-entry) " " content)}])
       (into context
             [{:role role :content content}])))))

(defmethod pipeline/process-frame :llm/context-aggregator
  [_ pipeline _ frame]
  (case (:frame/type frame)
    :llm/output-text-sentence
    (swap! pipeline update-in [:pipeline/config :llm/context] concat-context "assistant" (:frame/data frame))
    :text/input
    (do (swap! pipeline update-in [:pipeline/config :llm/context] concat-context "user" (:frame/data frame))
        (a/put! (:pipeline/main-ch @pipeline) (f/llm-user-context-added-frame true)))))
