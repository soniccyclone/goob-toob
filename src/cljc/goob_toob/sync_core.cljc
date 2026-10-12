(ns goob-toob.sync-core
  "Pure sync math shared by the server (authoritative clock) and the client
  (drift policy). No platform deps so it tests on the JVM and compiles to JS."
  (:refer-clojure :exclude [abs]))

(defn abs [x] (if (neg? x) (- x) x))

(defn expected-pos
  "Where the media SHOULD be at instant `now` (same monotonic clock as t0).
  pos is media seconds at t0."
  [{:keys [pos t0 now playing? rate]}]
  (if playing?
    (+ pos (* (max 0 (- now t0)) (or rate 1) 0.001))
    pos))

(defn ntp-offset
  "Server-clock offset estimate: t0 = client ms at send, st = server ms at
  receive, t1 = client ms at receive. Positive offset => server clock ahead."
  [t0 st t1]
  (- (+ st (/ (- t1 t0) 2.0)) t1))

(defn best-offset
  "Keep the sample with the smallest RTT (least queuing delay = best estimate)."
  [samples]
  (:offset (first (sort-by :rtt samples))))

(defn drift [local expected] (- local expected))

(defn policy
  "What a viewer should do given drift (local - expected, seconds):
  :none         in sync
  :nudge-fast   behind by a little -> playbackRate 1.05
  :nudge-slow   ahead by a little -> playbackRate 0.95
  :seek         behind/ahead by a lot -> hard seek (this is the catch-up)"
  [d]
  (let [a (abs d)]
    (cond
      (< a 0.3)  :none
      (> a 1.5)  :seek
      (neg? d)   :nudge-fast
      :else      :nudge-slow)))

(def nudge-fast-rate 1.05)
(def nudge-slow-rate 0.95)
(def settle-threshold 0.15)
