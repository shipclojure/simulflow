(ns hooks.defframe
  (:require
   [clj-kondo.hooks-api :as api]))

(defn defframe [{:keys [node]}]
  (let [[name docstring frame-type] (rest (:children node))
        frame-name (api/token-node (symbol (str name)))
        pred-name (api/token-node (symbol (str name "?")))]
    {:node (api/list-node
             [(api/token-node 'do)
              (api/list-node
                [(api/token-node 'defn) frame-name docstring
                 (api/vector-node [(api/token-node 'data)])
                 (api/list-node
                   [(api/token-node 'create-frame)
                    frame-type
                    (api/token-node 'data)])])
              (api/list-node
                [(api/token-node 'defn) pred-name (str "Checks if frame is of type " frame-name)
                 (api/vector-node [(api/token-node 'frame)])
                 (api/list-node
                   [(api/token-node 'instance?)
                    frame-name
                    (api/token-node 'frame)])])])}))
