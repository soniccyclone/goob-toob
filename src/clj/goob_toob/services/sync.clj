(ns goob-toob.services.sync
  "Server-authoritative room clock. One atom holds every live room:
  media position (pos seconds at monotonic instant t0), play/pause, rate,
  and the connected clients. The server never trusts client clocks; it just
  broadcasts truth and viewers' own catch-up policy keeps them honest."
  (:require [clojure.core.async :as a]
            [cheshire.core :as json]
            [goob-toob.sync-core :as sc]
            [goob-toob.log :as log]
            [goob-toob.repos.room :as rooms]))

(defonce rooms* (atom {})) ; room-id -> {:video-id :pos :t0 :playing? :rate :clients}
(defonce conns*  (atom {})) ; cid -> {:send :room-id :host? :nick}

(defn mono-ms [] (quot (System/nanoTime) 1000000))

(defn room-for [room-id]
  (or (get @rooms* room-id)
      (when-let [row (rooms/get-by-id room-id)]
        (let [r {:video-id (:video-id row) :pos 0.0 :t0 (mono-ms)
                 :playing? false :rate 1.0 :clients {}}]
          (swap! rooms* assoc room-id r)
          r))))

(defn state-msg [room]
  {:type "state" :pos (:pos room) :t0 (:t0 room)
   :playing (:playing? room) :rate (:rate room) :now (mono-ms)})

(defn beat-msg [room] (assoc (state-msg room) :type "beat"))

(defn peers-list [room]
  (mapv (fn [[cid c]]
          {:nick (:nick c) :lag (double (or (:drift c) 0)) :buffering (boolean (:buffering c))})
        (:clients room)))

(defn send-to [cid msg]
  (when-let [c (get @conns* cid)]
    (try ((:send c) (json/generate-string msg)) (catch Exception _ nil))))

(defn broadcast [room-id msg]
  (doseq [cid (keys (get-in @rooms* [room-id :clients]))]
    (send-to cid msg)))

(defn broadcast-peers [room-id]
  (broadcast room-id {:type "peers" :peers (peers-list (get @rooms* room-id))}))

(defn join! [room-id cid nick]
  (room-for room-id)
  (swap! rooms* update-in [room-id :clients] assoc cid {:nick nick :drift 0.0 :buffering false}))

(defn leave! [room-id cid]
  (swap! rooms* update-in [room-id :clients] dissoc cid))

(defn host-cmd! [room-id op pos]
  (let [now (mono-ms)]
    (swap! rooms*
      (fn [rs]
        (let [r (get rs room-id)
              r (case op
                  "play"  (assoc r :pos (or pos (:pos r)) :t0 now :playing? true)
                  "pause" (assoc r :pos (or pos (sc/expected-pos (assoc r :now now)))
                                   :t0 now :playing? false)
                  "seek"  (assoc r :pos (or pos 0.0) :t0 now)
                  r)]
          (assoc rs room-id (assoc r :rate 1.0)))))
    (broadcast room-id (assoc (state-msg (get @rooms* room-id)) :op op))))

(defn- behind-since [since now drift]
  (let [behind (- drift)]
    (cond
      (<= behind (:room-slow-exit sc/tuning))           nil
      (and (nil? since)
           (>= behind (:room-slow-enter sc/tuning)))    now
      :else                                            since)))

(defn status! [room-id cid pos buffering]
  (when-let [room (get @rooms* room-id)]
    (let [now (mono-ms)
          drift (double (if (some? pos) (- pos (sc/expected-pos (assoc room :now now))) 0))]
      (swap! rooms* update-in [room-id :clients cid]
             (fn [c]
               (assoc c :drift drift :at now :buffering (boolean buffering)
                        :behind-since (behind-since (:behind-since c) now drift)))))))

(defn client-drifts [room now]
  (for [[_ c] (:clients room)]
    {:nick (:nick c) :drift (or (:drift c) 0.0) :age-ms (- now (or (:at c) 0))
     :behind-ms (if-let [t (:behind-since c)] (- now t) 0)}))

(defn tick-room! [room-id]
  (let [now (mono-ms)
        room (get @rooms* room-id)
        {:keys [rate] laggard :for} (sc/room-rate (:rate room) (client-drifts room now))]
    (when (and (:playing? room) (not= rate (:rate room)))
      (swap! rooms* update room-id sc/reanchor now rate)
      (log/info "room" room-id "rate" rate (if laggard (str "for " laggard) ""))
      (broadcast room-id (assoc (state-msg (get @rooms* room-id)) :op "rate")))
    (let [room (get @rooms* room-id)]
      (broadcast room-id (assoc (beat-msg room)
                                :slow-for (when (= (:rate room) (:room-slow-rate sc/tuning)) laggard)
                                :peers (peers-list room))))))

(defn start-beat-loop! []
  (future
    (loop []
      (Thread/sleep 2000)
      (doseq [[room-id room] @rooms*
              :when (seq (:clients room))]
        (tick-room! room-id))
      (recur))))
