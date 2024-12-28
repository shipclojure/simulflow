(ns voice-fn.frames)

(defrecord BaseFrame [type data ts])

(defn frame? [frame]
  (instance? BaseFrame frame))

(defn create-frame
  [type data]
  (let [ts (System/currentTimeMillis)]
    ;; Using namespaced keywords for convenience sicne records don't support
    ;; namespaced params by default
    (map->BaseFrame {:type type
                     :frame/type type
                     :data data
                     :frame/data data
                     :ts ts
                     :frame/ts ts})))

(defn audio-input-frame [raw-data]
  (create-frame :audio/raw-input raw-data))

(defn text-frame [text]
  (create-frame :text/input text))
