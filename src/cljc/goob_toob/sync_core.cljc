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

(def tuning
  {:dead-band       1.0
   :settle          0.15
   :nudge-enter     0.5
   :seek-ahead      1.5
   :catch-rate      1.05
   :nudge-rate      0.95
   :runway          8.0
   :room-slow-rate  0.97
   :room-slow-enter 3.0
   :room-slow-exit  1.0
   :status-fresh-ms 15000
   :gone-after-s    60.0
   :give-up-ms      180000})

(defn policy
  "prev is the mode from the last tick, d is local - expected in seconds
  (negative = behind), runway is buffered seconds ahead of currentTime.
  Returns :none, :catch-up, :nudge-slow or :seek-back. Never seeks forward."
  [prev d runway]
  (let [{:keys [dead-band settle nudge-enter seek-ahead] runway-min :runway} tuning]
    (cond
      (> d seek-ahead)                                               :seek-back
      (or (> d nudge-enter) (and (= prev :nudge-slow) (> d settle))) :nudge-slow
      (and (>= runway runway-min)
           (or (< d (- dead-band))
               (and (= prev :catch-up) (< d (- settle)))))           :catch-up
      :else                                                          :none)))

(defn rate-for [mode room-rate]
  (case mode
    :catch-up   (:catch-rate tuning)
    :nudge-slow (:nudge-rate tuning)
    room-rate))

(defn room-rate
  "prev is the room's current rate. clients is a seq of
  {:nick s :drift d :age-ms n :behind-ms m} where drift is pos - expected
  (negative = behind), age-ms is how old the report is and behind-ms is how
  long this client has continuously been at or past :room-slow-enter (0 when
  not). Returns {:rate r :for nick} with :for the worst eligible laggard
  while slowed, else nil. A client past :give-up-ms no longer counts."
  [prev clients]
  (let [{:keys [room-slow-rate room-slow-enter room-slow-exit
                status-fresh-ms gone-after-s give-up-ms]} tuning
        eligible (filter #(and (<= (:age-ms %) status-fresh-ms)
                               (<= (- (:drift %)) gone-after-s)
                               (<= (or (:behind-ms %) 0) give-up-ms))
                         clients)
        worst (apply max-key #(- (:drift %)) {:drift 0.0 :nick nil} eligible)
        behind (- (:drift worst))]
    (cond
      (>= behind room-slow-enter) {:rate room-slow-rate :for (:nick worst)}
      (<= behind room-slow-exit)  {:rate 1.0 :for nil}
      :else {:rate prev :for (when (= prev room-slow-rate) (:nick worst))})))

(defn reanchor
  "Change a playing room's rate without moving its position."
  [room now rate]
  (assoc room :pos (expected-pos (assoc room :now now)) :t0 now :rate rate))
