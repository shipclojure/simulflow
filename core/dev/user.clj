(ns user
  (:require
   [clj-reload.core :as reload]))

(reload/init {:dirs ["src" "dev" "test" "../examples/src"]})

(defn reset
  []
  (reload/reload))
