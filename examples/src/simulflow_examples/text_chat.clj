(ns simulflow-examples.text-chat
  {:clj-reload/no-unload true}
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [simulflow.processors.llm-context-aggregator :as context]
   [simulflow.processors.openai :as openai]
   [simulflow.secrets :refer [secret]]
   [simulflow.transport.text-in :as text-in]
   [simulflow.transport.text-out :as text-out]
   [taoensso.telemere :as t]))

;; Set log level to reduce noise during chat
(t/set-min-level! :warn)

(defn make-text-chat-flow
  "Text-based chat flow using stdin/stdout instead of voice I/O.

  This example demonstrates how to interact with simulflow through text:
  - User types messages instead of speaking
  - LLM responses are streamed to console as they're generated
  - Input is blocked while LLM is responding
  - Clean prompt management handled by text-output processor
  - Uses existing frame types: user-speech-start/transcription/user-speech-stop sequence

  For a convenient CLI version, use: bin/chat
  "
  ([] (make-text-chat-flow {}))
  ([{:keys [llm-context debug? model]
     :or {llm-context {:messages
                       [{:role "system"
                         :content "You are a helpful AI assistant. Be concise and conversational.
                                  You are communicating through text chat."}]
                       :tools
                       [{:type :function
                         :function
                         {:name "get_weather"
                          :handler (fn [{:keys [town]}]
                                     (str "The weather in " town " is 17 degrees celsius"))
                          :description "Get the current weather of a location"
                          :parameters {:type :object
                                       :required [:town]
                                       :properties {:town {:type :string
                                                           :description "Town for which to retrieve the current weather"}}
                                       :additionalProperties false}
                          :strict true}}
                        {:type :function
                         :function
                         {:name "quit_chat"
                          :handler (fn [_]
                                     (println "\nGoodbye!")
                                     (System/exit 0))
                          :description "Quit the chat session"
                          :parameters {:type :object
                                       :properties {}
                                       :additionalProperties false}
                          :strict true}}]}
          debug? false
          model "gpt-4o-mini"}}]

   (flow/create-flow
     {:procs
      {;; Read from stdin and emit user-speech-start/transcription/user-speech-stop sequence
       :text-input {:proc text-in/text-input-process
                    :args {}}
       ;; Handle conversation context and user speech aggregation
       :context-aggregator {:proc context/context-aggregator
                            :args {:llm/context llm-context
                                   :aggregator/debug? debug?}}
       ;; Generate LLM responses with streaming
       :llm {:proc openai/openai-llm-process
             :args {:openai/api-key (secret [:openai :new-api-sk])
                    :llm/model model}}
       ;; Handle assistant message assembly for context
       :assistant-context-assembler {:proc context/assistant-context-assembler
                                     :args {:debug? debug?}}
       ;; Stream LLM output and manage prompts
       :text-output {:proc text-out/text-output-process
                     :args {:text-out/response-prefix "Assistant: "
                            :text-out/response-suffix ""
                            :text-out/show-thinking debug?
                            :text-out/user-prompt "You: "
                            :text-out/manage-prompts true}}}
      :conns
      [;; Main conversation flow
       [[:text-input :out] [:context-aggregator :in]]
       [[:context-aggregator :out] [:llm :in]]
       ;; Stream LLM responses to output for clean formatting
       [[:llm :out] [:text-output :in]]
       ;; Assemble assistant context for conversation history
       [[:llm :out] [:assistant-context-assembler :in]]
       [[:assistant-context-assembler :out] [:context-aggregator :in]]
       ;; System frame routing for input blocking/unblocking
       ;; LLM emits llm-full-response-start/end frames to :out, which are system frames
       [[:llm :out] [:text-input :sys-in]]
       ;; System frames for lifecycle management
       [[:text-input :sys-out] [:context-aggregator :sys-in]]]})))

(defn start-text-chat!
  "Start a text-based chat session with the simulflow agent.

  Usage:
  (start-text-chat!)

  Then type messages and press Enter. The assistant will respond with streaming text.
  Input is blocked while the assistant is responding.

  Type messages with 'quit' to exit via the quit_chat function.

  CLI Alternative: Use bin/chat for a convenient command-line interface."
  ([] (start-text-chat! {}))
  ([config]
   (println "🤖 Starting Simulflow Text Chat...")
   (println "💡 Type your messages and press Enter")
   (println "⏸️  Input is blocked while assistant responds")
   (println "🚪 Ask to 'quit chat' to exit")
   (println "🔗 CLI version available at: bin/chat")
   (println (apply str (repeat 50 "=")))
   ;; Create and start the flow
   (let [chat-flow (make-text-chat-flow config)
         {:keys [report-chan error-chan]} (flow/start chat-flow)]
     ;; Start error/report monitoring in background
     (future
       (loop []
         (when-let [[msg c] (a/alts!! [report-chan error-chan] :default nil)]
           (when msg
             (when (= c error-chan)
               (println "\n❌ Error:" msg))
             (recur)))))
     ;; Resume the flow to begin processing
     (flow/resume chat-flow)
     ;; Return flow for manual control if needed
     chat-flow)))

(defn -main
  "Main entry point for text chat demo"
  [& args]
  (let [config (if (some #(= % "--debug") args)
                 {:debug? true}
                 {})]
    (start-text-chat! config)))

(comment
  ;; Interactive usage examples:
  ;; 1. Basic usage
  (def chat (start-text-chat!))
  ;; Now type in the console:
  ;; You: Hello, how are you?
  ;; Assistant: Hello! I'm doing well, thank you for asking...
  ;; 2. With debug mode
  (def debug-chat (start-text-chat! {:debug? true}))
  ;; 3. With custom system message
  (def expert-chat (start-text-chat!
                     {:llm-context
                      {:messages
                       [{:role "system"
                         :content "You are a technical expert in Clojure programming. Be detailed and precise."}]}}))
  ;; 4. Stop the chat manually (or just ask to quit)
  (flow/stop chat))
  ;; 5. Test the weather function
  ;; You: What's the weather like in Paris?
  ;; Assistant: I'll check the weather in Paris for you.
  ;; [Tool call executed]
  ;; The weather in Paris is 17 degrees celsius
  ;; 6. CLI usage (alternative to REPL)
  ;; From terminal: bin/chat
  ;; Or with debug: bin/chat --debug
