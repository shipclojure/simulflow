(ns simulflow.transport.protocols)

(defprotocol FrameSerializer
  (serialize-frame [this frame] "Encode a frame to a specific format"))

(defprotocol FrameDeserializer
  (deserialize-frame [this raw-data] "Decode some raw data into a simulflow frame"))
