(ns simulflow-examples.text-chat-demo
  (:require
   [clojure.core.async :as a]
   [clojure.core.async.flow :as flow]
   [simulflow-examples.text-chat :as text-chat]))

(defn start-text-chat!
  "Start a text-based chat session with the simulflow agent.
  
  Usage:
  (start-text-chat!)
  
  Then type messages and press Enter. The assistant will respond with streaming text.
  Input is blocked while the assistant is responding.
  
  Type messages with 'quit' to exit via the quit_chat function."
  ([] (start-text-chat! {}))
  ([config]
   (println "🤖 Starting Simulflow Text Chat...")
   (println "💡 Type your messages and press Enter")
   (println "⏸️  Input is blocked while assistant responds")
   (println "🚪 Ask to 'quit chat' to exit")
   (println "=" 50)
   
   ;; Create and start the flow
   (let [chat-flow (text-chat/make-text-chat-flow config)
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
  (flow/stop chat)
  
  ;; 5. Test the weather function
  ;; You: What's the weather like in Paris?
  ;; Assistant: I'll check the weather in Paris for you.
  ;; [Tool call executed]
  ;; The weather in Paris is 17 degrees celsius
  
  )