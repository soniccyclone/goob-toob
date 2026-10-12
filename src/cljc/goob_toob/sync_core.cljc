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
    (double pos)))

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
  :none        in sync
  :catch-up    behind -> glide back at catch-rate (no jump)
  :nudge-slow  ahead by a little -> playbackRate 0.95
  :seek        ahead by a lot, or >30 s behind (lost the plot) -> hard seek"
  [d]
  (let [a (abs d)]
    (cond
      (< a 0.3)   :none
      (neg? d)    (if (>= a 30.0) :seek :catch-up)
      (<= a 1.5)  :nudge-slow
      :else       :seek)))

(defn catch-rate
  "Gradual catch-up playback rate for negative drift: 0.5 s behind -> 1.05x,
  5 s or more behind -> 1.5x. Continuous, so stalls heal with a glide, not a
  seek."
  [d]
  (min 1.5 (+ 1.0 (* 0.1 (abs d)))))

(def nudge-slow-rate 0.95)
(def settle-threshold 0.15)
