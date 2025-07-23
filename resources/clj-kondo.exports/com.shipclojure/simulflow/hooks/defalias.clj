(ns hooks.defalias
  (:require
   [clj-kondo.hooks-api :as api]))

(defn defalias [{:keys [node]}]
  (let [children (rest (:children node))
        [alias src & _] children]
    (cond
      ;; Two argument form: (defalias alias-name source-symbol)
      (and alias src)
      {:node (api/list-node
              [(api/token-node 'def)
               alias
               src])}

      ;; Single argument form: (defalias source-symbol)
      alias
      (let [src-symbol (:value alias)
            alias-name (api/token-node (symbol (name src-symbol)))]
        {:node (api/list-node
                [(api/token-node 'def)
                 alias-name
                 alias])})

      ;; Fallback
      :else
      {:node (api/list-node [(api/token-node 'do)])})))

(defn defaliases [{:keys [node]}]
  (let [children (rest (:children node))
        ;; Generate a def for each alias clause
        defs (mapv (fn [clause]
                     (cond
                       ;; Simple symbol form: (defaliases some.ns/symbol)
                       (api/token-node? clause)
                       (let [src-symbol (:value clause)
                             alias-name (api/token-node (symbol (name src-symbol)))]
                         (api/list-node
                          [(api/token-node 'def)
                           alias-name
                           clause]))

                       ;; Map form: {:alias my-name :src some.ns/symbol}
                       (api/map-node? clause)
                       (let [map-children (:children clause)
                             ;; Parse key-value pairs
                             pairs (partition 2 map-children)
                             alias-pair (first (filter #(= :alias (:value (first %))) pairs))
                             src-pair (first (filter #(= :src (:value (first %))) pairs))
                             alias-name (when alias-pair (second alias-pair))
                             src-name (when src-pair (second src-pair))]
                         (if (and alias-name src-name)
                           (api/list-node
                            [(api/token-node 'def)
                             alias-name
                             src-name])
                           ;; Fallback if we can't parse the map
                           (api/list-node [(api/token-node 'do)])))

                       :else
                       ;; Unknown form, just emit a do
                       (api/list-node [(api/token-node 'do)])))
                   children)]
    {:node (api/list-node
            (into [(api/token-node 'do)]
                  defs))}))
