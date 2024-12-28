(ns voice-fn.transport.websocket.protocols)

(defprotocol FrameSerializer
  (serialize-frame [this frame])
  (deserialize-frame [this raw-data]))
