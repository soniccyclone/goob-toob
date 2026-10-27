(ns goob-toob.e2e.metrics
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [cljs.core.async :as a :refer [<!]]
            [goob-toob.e2e.async :refer-macros [go-try <?]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.party :as party]
            [goob-toob.e2e.thresholds :refer [W]]))

(defn sample
  "Probes page every (:sample-every W) ms for ms. Each sample is the probe
  map plus :at (ms since the window opened). opts: :live, a fn returning a
  Promise of the server's view, polled once a second into :server; :host,
  a second page probed alongside into :host."
  ([page ms] (sample page ms {}))
  ([page ms {:keys [live host]}]
   (go-try
     (let [t0 (js/Date.now)
           every (:sample-every W)]
       (loop [out [] n 0]
         (let [at (- (js/Date.now) t0)]
           (if (> at ms)
             out
             (let [p (<? (party/probe page))
                   server (when (and live (zero? (mod n (quot 1000 every))))
                            (<p! (live)))
                   h (when host (<? (party/probe host)))
                   s (cond-> (assoc p :at at)
                       server (assoc :server server)
                       h (assoc :host h))]
               (<! (a/timeout every))
               (recur (conj out s) (inc n))))))))))

(defn abs* [x] (if (neg? x) (- x) x))

(defn p95 [xs]
  (when (seq xs)
    (let [v (vec (sort xs))]
      (nth v (max 0 (dec (js/Math.ceil (* 0.95 (count v)))))))))

(defn first-at [pred series]
  (some #(when (pred %) (:at %)) series))

(defn counter-delta [series k]
  (- (get-in (last series) [:counters k] 0) (get-in (first series) [:counters k] 0)))

(defn summarize
  "Drift stats over the samples at or after settle-ms; counters as deltas
  across the whole window."
  [series settle-ms]
  (let [after (filter #(and (>= (:at %) (or settle-ms 0)) (number? (:drift %))) series)
        drifts (map (comp abs* :drift) after)
        rates (keep :rate series)]
    {:drift-abs-max (when (seq drifts) (apply max drifts))
     :drift-abs-p95 (p95 drifts)
     :drift-final (:drift (last series))
     :rate-min (when (seq rates) (apply min rates))
     :rate-max (when (seq rates) (apply max rates))
     :stalls (counter-delta series :stalls)
     :seeks (counter-delta series :seeks)
     :muted (:muted (last series))
     :started-at (first-at :started series)
     :off-rate-s (:off-rate-s (last series))
     :fullscreen (:fullscreen (last series))}))

(defn- fmt [x]
  (cond
    (nil? x) "-"
    (and (number? x) (not (integer? x))) (.toFixed x 3)
    (map? x) (str/join " " (for [[k v] x] (str (name k) "=" (fmt v))))
    :else (str x)))

(def ^:private columns
  [:drift-abs-p95 :drift-abs-max :drift-final :rate-min :rate-max :seeks :stalls :started-at
   :off-rate-s :fullscreen])

(def ^:private header
  (str "| project | spec | journey | drift p95 | drift max | drift final | rate min | rate max"
       " | seeks | stalls | started at ms | off-rate s | fullscreen | extra |\n"
       "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n"))

(defn metrics-dir [^js full-config]
  (path/join (api/e2e-dir full-config) "metrics"))

(defn reset-summary! [^js full-config]
  (let [dir (metrics-dir full-config)]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync (path/join dir "summary.md") header)))

(defn- slug [s]
  (-> (str/lower-case s) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn record!
  "Writes metrics/<project>-<spec>-<title>.json with the series, the summary
  and the extra numbers, attaches the summary to the report and appends a
  row to metrics/summary.md. Call it before asserting."
  [^js info summary series extra]
  (let [dir (metrics-dir (.-config info))
        project (.. info -project -name)
        spec (path/basename (.-file info) ".js")
        title (.-title info)
        body (js/JSON.stringify (clj->js {:project project :spec spec :title title
                                          :summary summary :extra extra :series series})
                                nil 1)]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync (path/join dir (str project "-" spec "-" (slug title) ".json")) body)
    (fs/appendFileSync (path/join dir "summary.md")
                       (str "| " (str/join " | " (concat [project spec title]
                                                         (map #(fmt (get summary %)) columns)
                                                         [(fmt extra)]))
                            " |\n"))
    (.attach info "metrics" #js {:body (js/JSON.stringify (clj->js {:summary summary :extra extra}) nil 1)
                                 :contentType "application/json"})))

(defn sample-until
  "Polls f (returns a Promise) every (:sample-every W) ms until pred holds
  on a value or ms pass. Values gain :at."
  [f pred ms]
  (go-try
    (let [t0 (js/Date.now)]
      (loop [out []]
        (let [at (- (js/Date.now) t0)
              v (assoc (js->clj (<p! (f))) :at at)
              out (conj out v)]
          (if (or (pred v) (> at ms))
            out
            (do (<! (a/timeout (:sample-every W)))
                (recur out))))))))

(defn sample-fn [f ms]
  (sample-until f (constantly false) ms))
