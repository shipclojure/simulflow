(ns user)

(defmacro jit [sym]
  `(requiring-resolve '~sym))

(defonce initiated-clj-reload? (atom false))

(defn reset
  []
  (when-not @initiated-clj-reload?
    ((jit clj-reload.core/init) {:dirs ["src" "dev" "test" "../examples/src"]})
    (reset! initiated-clj-reload? true))
  ((jit clj-reload.core/reload)))
