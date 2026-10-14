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
    (.initialize ctx video-el url false)
    ctx))

(defn video-representations [ctx]
  (->> (array-seq (.getBitrateInfoListFor ctx "video"))
       (map-indexed (fn [i info] {:idx (.-qualityIndex info)
                                  :height (.-height info)
                                  :bitrate (.-bitrate info)}))
       (sort-by :height >)))

(defn set-auto [ctx]
  (.updateSettings ctx #js {:streaming #js {:abr #js {:autoSwitchBitrate #js {:video true}}}}))

(defn set-height [ctx idx]
  (.updateSettings ctx #js {:streaming #js {:abr #js {:autoSwitchBitrate #js {:video false}}}})
  (.setQualityFor ctx "video" idx))

(defn on [ctx event cb]
  (.on ctx event cb))

(defn- jstr
  ;; stringify any JS value for the log; objects may hold circular refs
  ;; (dash.js request VOs do), so fall back to a shallow key dump
  [x]
  (when (some? x)
    (cond
      (string? x) x
      (number? x) (str x)
      (boolean? x) (str x)
      :else
      (try
        (js/JSON.stringify x)
        (catch js/Error _
          (try
            (let [out (js-obj)]
              (doseq [k (js/Object.keys x)]
                (let [v (unchecked-get x k)]
                  (unchecked-set out k (if (or (string? v) (number? v) (boolean? v)) v (str v)))))
              (js/JSON.stringify out))
            (catch js/Error _ (str x))))))))

(defn on-faults
  "Forward dash.js failure + lifecycle events to cb as a compact map.
  The watch page relays these to the server log (see routes/clientlog), so
  server.log shows what the browser actually saw without any devtools."
  [ctx cb]
  (let [fire (fn [ev data] (cb (into {:ev ev} (remove (fn [[_ v]] (nil? v)) data))))
        ;; dash.js ERROR payload is exactly {error: DashJSError{code,message,data}}
        ;; — a constructor VO, so cljs object? is false on it; read fields directly
        err-fields (fn [e]
                     (let [err (.-error e)]
                       (if (some? err)
                         {:code (jstr (.-code err)) :msg (jstr (.-message err))
                          :data (jstr (.-data err))}
                         {:msg (jstr (.-message e))})))]
    (.on ctx "error"
         (fn [e] (fire "dash-error" (err-fields e))))
    (.on ctx "manifestLoadingError"
         (fn [e] (fire "manifest-failed" {:url (jstr (.-url e)) :http (jstr (.-code e))
                                          :msg (jstr (.-message e))})))
    (.on ctx "fragLoadingError"
         (fn [e] (fire "frag-failed" {:url (jstr (some-> (.-request e) (.-url)))
                                      :http (jstr (.-code e))})))
    (.on ctx "playbackStalled" (fn [_] (fire "dash-stalled" {})))
    (.on ctx "playbackStarted" (fn [_] (fire "playback-started" {})))))
