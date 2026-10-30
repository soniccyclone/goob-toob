(ns goob-toob.player
  "dash.js wrapper: attach a DASH manifest to a <video>, pick auto (ABR,
  best sync) or a locked resolution, and the audio language (one
  adaptation set per language since the tracks plan)."
  (:require ["dashjs" :refer [MediaPlayer]]
            [goob-toob.tracks :as tracks]))

(defn set-initial-audio!
  "Which audio adaptation set the stream being attached starts on, by
  language, so the first segments fetched are already the right ones.
  Order matters in dash.js 4.7.4: setInitialMediaSettingsFor throws
  before initialize, and attachSource on a player that already streamed
  resets the media controller, initial settings included, so this goes
  right *after* attachSource and before the manifest arrives (the load is
  async, the selection runs on streamInitialized). The lang goes in as a
  RegExp over its aliases (^(en|eng)$): handed a string dash.js runs
  bcp-47-normalize over it first (eng -> en) and then fails to match the
  manifest's own \"eng\". Nil lang: nothing set, dash.js picks on its own."
  [ctx lang]
  (when lang
    (try
      (.setInitialMediaSettingsFor ctx "audio"
                                   #js {:lang (js/RegExp. (tracks/lang-regex-source lang))})
      (catch js/Error _ nil))))

(defn create
  "Returns the dash.js context. on-ready fires when the stream is
  initialized (bitrate list available). lang is the audio language the
  first stream starts on (see set-initial-audio!), or nil."
  [video-el url start-kbps lang on-ready]
  (let [ctx (.create (MediaPlayer))]
    (.on ctx "streamInitialized"
         (fn [_] (on-ready ctx)))
    (.updateSettings ctx #js {:streaming #js {:buffer #js {:fastSwitchEnabled true}
                                              :retryAttempts #js {:MediaSegment js/Infinity
                                                                  :InitializationSegment js/Infinity}
                                              :abr #js {:initialBitrate #js {:video start-kbps}}
                                              :gaps #js {:jumpLargeGaps false}}
                              :debug #js {:logLevel 1}})
    ;; the view first, the source after: the audio setting must sit between
    (.initialize ctx video-el nil false)
    (.attachSource ctx url)
    (set-initial-audio! ctx lang)
    ctx))

(defn attach!
  "Switch the player to another manifest in place (nil drops the source:
  dash.js resets its controllers and clears the element). The player's
  settings persist; streamInitialized fires again for a new source, so
  create's on-ready re-runs. lang: the audio language the new stream
  starts on, or nil (set-initial-audio!)."
  [ctx url lang]
  (.attachSource ctx url)
  (when url (set-initial-audio! ctx lang)))

(defn- track-lang [^js track]
  (let [l (.-lang track)]
    (when (and (string? l) (not= "" l)) l)))

(defn audio-tracks
  "The stream's audio adaptation sets as [{:lang :label :index :track}],
  manifest order; [] before the stream is initialized (dash.js throws on
  getTracksFor until then)."
  [ctx]
  (try
    (mapv (fn [^js t]
            {:lang (track-lang t)
             :label (some-> (.-labels t) (aget 0) .-text)
             :index (.-index t)
             :track t})
          (array-seq (or (.getTracksFor ctx "audio") #js [])))
    (catch js/Error _ [])))

(defn current-audio
  "The language of the audio track playing, nil before stream-init or for
  an untagged set."
  [ctx]
  (try (some-> (.getCurrentTrackFor ctx "audio") track-lang)
       (catch js/Error _ nil)))

(defn set-audio!
  "Switch to the audio track in lang (first match over its aliases).
  dash.js flushes the audio buffer and refetches at the current time: no
  seek, currentTime and playbackRate untouched. True when a switch was
  made; nil when no such track or it already plays, or before stream-init."
  [ctx lang]
  (when-let [{:keys [^js track] :as t} (first (filter #(tracks/same-lang? lang (:lang %))
                                                      (audio-tracks ctx)))]
    (when-not (tracks/same-lang? (:lang t) (current-audio ctx))
      (try (.setCurrentTrack ctx track) true
           (catch js/Error _ nil)))))

(defn video-representations [ctx]
  (->> (array-seq (.getBitrateInfoListFor ctx "video"))
       (map-indexed (fn [i info] {:idx (.-qualityIndex info)
                                  :height (.-height info)
                                  :bitrate (.-bitrate info)}))
       (sort-by :height >)))

(defn quality-list [ctx]
  (mapv (fn [info] {:idx (.-qualityIndex info)
                    :height (.-height info)
                    :bitrate (.-bitrate info)})
        (array-seq (.getBitrateInfoListFor ctx "video"))))

(defn current-quality [ctx]
  (.getQualityFor ctx "video"))

(defn set-max-kbps [ctx kbps]
  (.updateSettings ctx #js {:streaming #js {:abr #js {:maxBitrate #js {:video kbps}}}}))

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
    ;; dash.js 4.7.4 has no "fragLoadingError" event (#46). The only thing it
    ;; emits per failed segment *attempt* is the ISO 23009-1 HttpList metric
    ;; HTTPLoader records before scheduling the retry; "error" (code 27/28)
    ;; and "fragmentLoadingCompleted"-with-error fire only once retries are
    ;; exhausted, which never happens with the unlimited retries in `create`.
    ;; A failure record has a numeric non-2xx responsecode (0 = network
    ;; error) and, per the spec note in MetricsModel.addHttpRequest, no
    ;; `trace` key -- an ABR abort keeps its traces, a redirect stub has a
    ;; null responsecode, so both are skipped.
    (.on ctx "metricAdded"
         (fn [e]
           (let [vo (.-value e)]
             (when (and (= "HttpList" (.-metric e))
                        (some? vo)
                        (contains? #{"MediaSegment" "InitializationSegment" "IndexSegment"}
                                   (.-type vo))
                        (number? (.-responsecode vo))
                        (not (<= 200 (.-responsecode vo) 299))
                        (undefined? (.-trace vo)))
               (fire "frag-failed" {:url (jstr (.-url vo))
                                    :http (jstr (.-responsecode vo))})))))
    (.on ctx "playbackStalled" (fn [_] (fire "dash-stalled" {})))
    (.on ctx "playbackStarted" (fn [_] (fire "playback-started" {})))))
