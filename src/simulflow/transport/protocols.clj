(ns simulflow.transport.protocols)

(defprotocol FrameCodec
  (serialize-frame [this frame] "Encode a frame to a specific format")
  (deserialize-frame [this raw-data] "Decode some raw data into a simulflow frame"))
