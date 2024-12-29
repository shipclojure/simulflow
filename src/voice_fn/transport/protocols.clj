(ns voice-fn.transport.protocols)

(defprotocol FrameSerializer
  (serialize-frame [this frame])
  (deserialize-frame [this raw-data]))
