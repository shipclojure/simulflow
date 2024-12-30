(ns hooks.defframe
  (:require
   [clj-kondo.hooks-api :as api]))

(defn defframe [{:keys [node]}]
  (let [[name _type docstring] (rest (:children node))
        frame-name (api/token-node (symbol (str name)))
        pred-name (api/token-node (symbol (str name "?")))]
    {:node (api/list-node
             [(api/token-node 'do)
              (api/list-node
                [(api/token-node 'defn) frame-name docstring

                 (api/vector-node [(api/token-node 'frame)])
                 (api/token-node 'frame)]) ;; body returning nil
              (api/list-node
                [(api/token-node 'defn) pred-name
                 (api/vector-node [(api/token-node 'frame)]) ;; args [frame]
                 (api/list-node [(api/token-node 'instance?) frame-name (api/token-node 'frame)])])])}))
