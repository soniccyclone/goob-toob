(ns goob-toob.e2e.net
  (:require [goob-toob.e2e.async :refer-macros [go-try]]
            [cljs.core.async.interop :refer-macros [<p!]]))

(defn throttle!
  "Chromium only: shapes the page's network to kbps down and up through CDP."
  [^js ctx page kbps]
  (go-try
    (let [^js cdp (<p! (.newCDPSession ctx page))
          bytes-per-s (/ (* kbps 1000) 8)]
      (<p! (.send cdp "Network.enable"))
      (<p! (.send cdp "Network.emulateNetworkConditions"
                  #js {:offline false :latency 40
                       :downloadThroughput bytes-per-s :uploadThroughput bytes-per-s}))
      cdp)))
