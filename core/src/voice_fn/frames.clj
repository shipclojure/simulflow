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

(defn audio-input-frame
  "User audio input frame. Put on the pipeline by the input transport. Data is
  bynary"
  [bytes]
  (create-frame :audio/raw-input bytes))

(defn audio-input-frame?
  [i]
  (and (frame? i) (= :audio/raw-input (:frame/type i))))

(defn audio-output-frame
  "Audio frame to be played back to the user through output transport. Generated
  by text to speech processors or multi modal (with voice capabilities) LLM
  processors."
  [bytes]
  (create-frame :audio/output bytes))

(defn audio-output-frame?
  [i]
  (and (frame? i) (= :audio/output (:frame/type i))))

(defn text-input-frame
  "Frame usually outputted by a transcription processor. Serves as input for text
  LLM processors."
  [text]
  (create-frame :text/input text))

(defn llm-output-text-chunk-frame
  "Frame outputted by text based streaming LLMs"
  [text-chunk]
  (create-frame :llm/output-text-chunk text-chunk))

(defn error-frame
  [data]
  (create-frame :system/error data))
