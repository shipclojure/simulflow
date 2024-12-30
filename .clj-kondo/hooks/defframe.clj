(ns hooks.defframe
  (:require
   [clj-kondo.hooks-api :as api]))

(defn defframe [{:keys [node]}]
  (let [[name _type docstring] (rest (:children node))
        frame-name (api/token-node (api/symbol-from-node name))
        pred-name (api/token-node (symbol (str (api/symbol-from-node name) "?")))]
    {:node (api/list-node
             [(api/token-node 'do)
              (api/list-node
                [(api/token-node 'defn) frame-name docstring])
              (api/list-node
                [(api/token-node 'defn) pred-name])])}))
