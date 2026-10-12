(ns goob-toob.player
  "dash.js wrapper: attach a DASH manifest to a <video>, pick auto (ABR,
  best sync) or a locked resolution."
  (:require ["dashjs" :refer [MediaPlayer]]))

(defn create
  "Returns the dash.js context. on-ready fires when the stream is
  initialized (bitrate list available)."
  [video-el url on-ready]
  (let [ctx (.create (MediaPlayer))]
    (.on ctx "streamInitialized"
         (fn [_] (on-ready ctx)))
    (.updateSettings ctx #js {:streaming #js {:buffer #js {:fastSwitchEnabled true}}
                              :debug #js {:logLevel 1}})
    (.initialize ctx url video-el false)
    ctx))

(defn video-representations [ctx]
  (->> (array-seq (.getBitrateInfoListFor ctx "video"))
       (map-indexed (fn [i info] {:idx (.-qualityIndex info)
                                  :height (.-height info)
                                  :bitrate (.-bitrate info)}))
       (sort-by :height >)))

(defn set-auto [ctx]
  (.updateSettings ctx #js {:streaming #js {:abr #js {:autoSwitch true}}}))

(defn set-height [ctx idx]
  (.updateSettings ctx #js {:streaming #js {:abr #js {:autoSwitch false}}})
  (.setQualityFor ctx "video" idx))

(defn on [ctx event cb]
  (.on ctx event cb))
