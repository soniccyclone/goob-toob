(ns goob-toob.telemetry
  "Reads the watch page's telemetry (one JSON line per uploaded chunk, see
  the telemetry block in watch.cljs and routes/telemetry) and flags
  episodes per client: stretches off 1.0x, drift excursions, stall
  clusters, dropped-frame bursts, buffer starvation and the two A/V
  desync signatures (#57). Pure: files or dirs in, maps out. From a shell:

      clojure -M -m goob-toob.telemetry storage/telemetry/2026-10-27
      make telemetry FILE=storage/telemetry/2026-10-27/<room>/<session>-<nick>.jsonl

  and from the REPL, (analyse (read-path \"storage/telemetry/2026-10-27\"))."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [goob-toob.sync-core :as sc])
  (:import [java.io File]
           [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]))

(def thresholds
  "Every detector's knobs, in seconds unless named otherwise. An episode
  is a run of consecutive 1 Hz samples where the condition holds (a run
  survives a gap of a sample or two); the *-s values are the shortest run
  reported, the *-min values the smallest total."
  {:off-rate-s        20   ; |rate - 1| > 0.001 this long: the stretcher has had its chance (#57 suspect 1)
   :drift-band        (:dead-band sc/tuning) ; |drift| beyond the policy's dead band...
   :drift-s           5    ; ...this long
   :stall-gap-s       30   ; stalls closer than this are one cluster
   :stall-cluster-min 2    ; stalls a cluster needs before it is flagged
   :dropped-per-s     5    ; dropped-frame delta in one second that makes a burst sample
   :dropped-burst-min 15   ; frames a burst must total to be flagged
   :starve-vb         1.0  ; video buffer below this while playing...
   :starve-s          2    ; ...this long
   :av-gap-low        1.0  ; one track's buffer under this while the other is...
   :av-gap-high       8.0  ; ...over this (dash.js fills the two SourceBuffers independently and
                           ; levels tens of seconds apart are routine; one starving while the
                           ; other is full is not)...
   :av-gap-s          5    ; ...this long
   :av-healthy-ab     2.0  ; audio buffer at or above this is "the sound is fine"
   :av-climb-s        30   ; dropped frames climbing, sound fine, rate 1.0, this long...
   :av-climb-min      30}) ; ...by at least this many frames: the picture is losing to the sound

;; ---- reading ------------------------------------------------------------------

(def ^:private columns
  "The chunk header's short names -> sample keys."
  {"t" :t "ct" :current-time "exp" :expected "d" :drift "r" :rate "rr" :room-rate
   "p" :paused "b" :buffering "rs" :ready-state "ab" :audio-buffer "vb" :video-buffer
   "df" :dropped-frames "tf" :total-frames "q" :quality "qc" :qcap "fs" :fullscreen})

(defn decode-chunk
  "One line's map with its positional rows expanded to sample maps: :t as
  epoch ms (t0 plus the row's offset), the 0/1 flags as booleans."
  [{:keys [header samples t0] :as chunk}]
  (let [ks (mapv #(get columns % (keyword %)) header)]
    (assoc chunk :samples
           (mapv (fn [row]
                   (-> (zipmap ks row)
                       (update :t #(+ (or t0 0) (or % 0)))
                       (update :paused #(= 1 %))
                       (update :buffering #(= 1 %))
                       (update :fullscreen #(= 1 %))))
                 samples))))

(defn read-file
  "The chunks of one jsonl file, decoded."
  [f]
  (with-open [r (io/reader f)]
    (->> (line-seq r)
         (remove str/blank?)
         (mapv #(decode-chunk (json/parse-string % true))))))

(defn jsonl-files
  "path as a file, or every *.jsonl under it as a dir."
  [path]
  (let [f (io/file path)]
    (if (.isDirectory f)
      (sort-by str (filter #(and (.isFile ^File %) (str/ends-with? (.getName ^File %) ".jsonl"))
                           (file-seq f)))
      [f])))

(defn read-path [path]
  (vec (mapcat read-file (jsonl-files path))))

(defn clients
  "Chunks grouped by session, earliest client first:
  {:session :nick :vids :samples (by time) :events (by time)}."
  [chunks]
  (->> (group-by :session chunks)
       (map (fn [[session cs]]
              {:session session
               :nick (:nick (first cs))
               :vids (vec (distinct (keep :vid cs)))
               :samples (vec (sort-by :t (mapcat :samples cs)))
               :events (vec (sort-by :t (mapcat :events cs)))}))
       (sort-by #(or (:t (first (:samples %))) 0))
       vec))

;; ---- runs ---------------------------------------------------------------------

(defn- runs
  "Maximal runs of samples satisfying pred, a run surviving up to gap-s
  seconds without one. Each: {:start :end :samples} (the qualifying
  samples; :start/:end their first and last :t)."
  [pred gap-s samples]
  (let [gap-ms (* 1000 gap-s)]
    (loop [ss samples run nil acc []]
      (if-let [s (first ss)]
        (cond
          (pred s)
          (recur (rest ss)
                 (if run
                   (-> run (assoc :end (:t s)) (update :samples conj s))
                   {:start (:t s) :end (:t s) :samples [s]})
                 acc)

          (and run (> (- (:t s) (:end run)) gap-ms))
          (recur (rest ss) nil (conj acc run))

          :else (recur (rest ss) run acc))
        (if run (conj acc run) acc)))))

(defn- span-s [{:keys [start end]}] (/ (- end start) 1000.0))

(defn- with-deltas
  "Each sample with :dropped-delta (frames since the previous sample) and
  :dropped-per-s (that delta over the seconds elapsed)."
  [samples]
  (->> (cons nil samples)
       (partition 2 1)
       (mapv (fn [[prev s]]
               (let [dd (if (and prev (number? (:dropped-frames s)) (number? (:dropped-frames prev)))
                          (max 0 (- (:dropped-frames s) (:dropped-frames prev)))
                          0)
                     dt (if prev (max 0.5 (/ (- (:t s) (:t prev)) 1000.0)) 1.0)]
                 (assoc s :dropped-delta dd :dropped-per-s (/ dd dt)))))))

(defn- playing? [s] (and (not (:paused s)) (not (:buffering s))))
(defn- off-rate? [s] (and (number? (:rate s)) (> (Math/abs (- (double (:rate s)) 1.0)) 0.001)))
(defn- stalled? [s] (and (:buffering s) (not (:paused s))))
(defn- abs-drift [s] (when (number? (:drift s)) (Math/abs (double (:drift s)))))
(defn- extreme [f xs] (when (seq xs) (apply f xs)))
(defn- round [x n] (when (number? x) (let [p (Math/pow 10 n)] (/ (Math/round (* p (double x))) p))))

(defn- episode [type client run evidence]
  {:type type :nick (:nick client) :session (:session client)
   :start (:start run) :end (:end run) :duration-s (round (span-s run) 1)
   :evidence evidence})

;; ---- detectors ------------------------------------------------------------------

(defn- off-rate-episodes [client samples]
  (for [r (runs off-rate? 1.5 samples)
        :when (>= (span-s r) (:off-rate-s thresholds))
        :let [rates (map :rate (:samples r))]]
    (episode :off-rate client r
             {:rate-min (extreme min rates) :rate-max (extreme max rates)
              :drift-start (round (:drift (first (:samples r))) 2)
              :drift-end (round (:drift (last (:samples r))) 2)})))

(defn- drift-episodes [client samples]
  (for [r (runs #(some-> (abs-drift %) (> (:drift-band thresholds))) 1.5 samples)
        :when (>= (span-s r) (:drift-s thresholds))
        :let [ds (map :drift (:samples r))]]
    (episode :drift client r
             {:drift-min (round (extreme min ds) 2) :drift-max (round (extreme max ds) 2)
              :rate-max (extreme max (keep :rate (:samples r)))})))

(defn- stall-episodes
  "Stall runs (buffering while not paused) closer than :stall-gap-s form a
  cluster; a cluster of :stall-cluster-min or more stalls is flagged."
  [client samples]
  (let [stalls (runs stalled? 1.5 samples)
        clusters (reduce (fn [acc r]
                           (let [c (peek acc)]
                             (if (and c (<= (- (:start r) (:end c)) (* 1000 (:stall-gap-s thresholds))))
                               (conj (pop acc) (-> c (assoc :end (:end r)) (update :stalls conj r)))
                               (conj acc {:start (:start r) :end (:end r) :stalls [r]}))))
                         [] stalls)]
    (for [c clusters
          :when (>= (count (:stalls c)) (:stall-cluster-min thresholds))]
      (episode :stalls client c
               {:stalls (count (:stalls c))
                :stalled-s (round (reduce + (map #(max 1.0 (span-s %)) (:stalls c))) 1)
                :video-buffer-min (extreme min (keep :video-buffer (mapcat :samples (:stalls c))))}))))

(defn- dropped-episodes [client samples]
  (for [r (runs #(> (:dropped-per-s %) (:dropped-per-s thresholds)) 2 samples)
        :let [total (reduce + (map :dropped-delta (:samples r)))]
        :when (>= total (:dropped-burst-min thresholds))]
    (episode :dropped-frames client r
             {:dropped total
              :peak-per-s (round (extreme max (map :dropped-per-s (:samples r))) 1)
              :rate-max (extreme max (keep :rate (:samples r)))})))

(defn- starve-episodes [client samples]
  (for [r (runs #(and (playing? %) (number? (:video-buffer %))
                      (< (:video-buffer %) (:starve-vb thresholds)))
                1.5 samples)
        :when (>= (span-s r) (:starve-s thresholds))]
    (episode :buffer-starved client r
             {:video-buffer-min (extreme min (keep :video-buffer (:samples r)))
              :audio-buffer-min (extreme min (keep :audio-buffer (:samples r)))})))

(defn- av-starving
  "Which track's buffer is under :av-gap-low while the other holds
  :av-gap-high or more, or nil."
  [{:keys [audio-buffer video-buffer]}]
  (when (and (number? audio-buffer) (number? video-buffer))
    (let [{:keys [av-gap-low av-gap-high]} thresholds]
      (cond (and (< audio-buffer av-gap-low) (>= video-buffer av-gap-high)) "audio"
            (and (< video-buffer av-gap-low) (>= audio-buffer av-gap-high)) "video"))))

(defn- av-gap-episodes
  "The two SourceBuffers have diverged: one track starving while the other
  is full, for :av-gap-s. The starving track stalls or plays on alone,
  which is where an offset starts. (A level difference alone is not the
  signal: dash.js fetches the two tracks on their own schedules.)"
  [client samples]
  (for [r (runs #(and (playing? %) (av-starving %)) 1.5 samples)
        :when (>= (span-s r) (:av-gap-s thresholds))
        :let [ss (:samples r)]]
    (episode :av-gap client r
             {:starving (av-starving (first ss))
              :audio-buffer-min (extreme min (map :audio-buffer ss))
              :video-buffer-min (extreme min (map :video-buffer ss))
              :audio-buffer-max (extreme max (map :audio-buffer ss))
              :video-buffer-max (extreme max (map :video-buffer ss))})))

(defn- av-climb-episodes
  "Dropped frames climbing for :av-climb-s with the sound fine (audio
  buffer at or above :av-healthy-ab) and the rate at 1.0: the renderer is
  late against the audio clock, which is what an A/V offset looks like
  from inside the element."
  [client samples]
  (for [r (runs #(and (playing? %) (not (off-rate? %))
                      (>= (or (:audio-buffer %) 0) (:av-healthy-ab thresholds))
                      (pos? (:dropped-delta %)))
                2 samples)
        :let [total (reduce + (map :dropped-delta (:samples r)))]
        :when (and (>= (span-s r) (:av-climb-s thresholds))
                   (>= total (:av-climb-min thresholds)))]
    (episode :av-climb client r
             {:dropped total
              :per-s (round (/ total (max 1.0 (span-s r))) 2)
              :audio-buffer-min (extreme min (keep :audio-buffer (:samples r)))})))

(defn episodes
  "Every flagged episode of one client (see clients), by start time."
  [client]
  (let [samples (with-deltas (:samples client))]
    (->> (concat (off-rate-episodes client samples)
                 (drift-episodes client samples)
                 (stall-episodes client samples)
                 (dropped-episodes client samples)
                 (starve-episodes client samples)
                 (av-gap-episodes client samples)
                 (av-climb-episodes client samples))
         (sort-by :start)
         vec)))

;; ---- summaries ------------------------------------------------------------------

(defn- p95 [xs]
  (when (seq xs)
    (let [v (vec (sort xs))]
      (nth v (max 0 (dec (int (Math/ceil (* 0.95 (count v))))))))))

(defn summary
  "One client's numbers: span, drift, time off 1.0x, stalls, dropped frames
  and its markers (the button's marks, the auto trigger, idle/resume)."
  [{:keys [samples events] :as client}]
  (let [drifts (keep abs-drift samples)
        df (keep :dropped-frames samples)]
    (merge (select-keys client [:session :nick :vids])
           {:samples (count samples)
            :from (:t (first samples)) :to (:t (last samples))
            :span-s (when (seq samples) (round (/ (- (:t (last samples)) (:t (first samples))) 1000.0) 0))
            :drift-abs-p95 (round (p95 drifts) 2)
            :drift-abs-max (round (extreme max drifts) 2)
            :off-rate-s (count (filter off-rate? samples))
            :stalls (count (runs stalled? 1.5 samples))
            :dropped (when (seq df) (- (last df) (first df)))
            :events (count events)
            :marks (vec (filter #(#{"mark" "auto" "idle" "resume"} (:type %)) events))})))

(defn analyse
  "Chunks (read-path, read-file) -> {:clients [summary ...] :episodes [...]}
  with the episodes of every client by start time."
  [chunks]
  (let [cs (clients chunks)]
    {:clients (mapv summary cs)
     :episodes (vec (sort-by :start (mapcat episodes cs)))}))

(defn file-info
  "What the admin listing says about one chunk file: its size, chunks and
  samples, the first and last sample's time."
  [^File f]
  (let [chunks (read-file f)
        ts (keep :t (mapcat :samples chunks))]
    {:name (.getName f) :size (.length f)
     :chunks (count chunks) :samples (count ts)
     :first (extreme min ts) :last (extreme max ts)}))

;; ---- printing ------------------------------------------------------------------

(def ^:private stamp (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn- when-at [t]
  (if (number? t)
    (.format stamp (.atZone (Instant/ofEpochMilli (long t)) (ZoneId/systemDefault)))
    "?"))

(defn- clock [t]
  (if (number? t) (subs (when-at t) 11) "?"))

(defn print-report
  "The analysis as text: a block per client, then the episodes."
  [{:keys [clients episodes]}]
  (doseq [c clients]
    (println (str "client " (:nick c) " (" (:session c) ")"
                  "  " (when-at (:from c)) " .. " (clock (:to c)) "  " (:span-s c) " s"))
    (println (str "  samples " (:samples c)
                  "  drift p95 " (:drift-abs-p95 c) " max " (:drift-abs-max c)
                  "  off-rate " (:off-rate-s c) " s"
                  "  stalls " (:stalls c)
                  "  dropped " (:dropped c)
                  "  events " (:events c)))
    (doseq [m (:marks c)]
      (println (str "  " (clock (:t m)) " " (:type m) " " (or (:note m) (:reason m) ""))))
    (println))
  (if (empty? episodes)
    (println "no episodes flagged")
    (do (println (str (count episodes) " episode(s):"))
        (doseq [e episodes]
          (println (str "  " (clock (:start e)) " .. " (clock (:end e))
                        "  " (name (:type e)) "  " (:nick e) "  " (:duration-s e) " s  "
                        (pr-str (:evidence e))))))))

(defn -main
  "clojure -M -m goob-toob.telemetry <file-or-dir> ..."
  [& paths]
  (if (empty? paths)
    (println "usage: clojure -M -m goob-toob.telemetry <file-or-dir> ...")
    (print-report (analyse (mapcat read-path paths)))))
