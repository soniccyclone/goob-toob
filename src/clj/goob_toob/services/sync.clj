(ns goob-toob.services.sync
  "Server-authoritative room clock. One atom holds every live room:
  media position (pos seconds at monotonic instant t0), play/pause, rate,
  and the connected clients. The server never trusts client clocks; it just
  broadcasts truth and viewers' own catch-up policy keeps them honest."
  (:require [clojure.core.async :as a]
            [cheshire.core :as json]
            [goob-toob.sync-core :as sc]
            [goob-toob.repos.room :as rooms]))

(defonce rooms* (atom {})) ; room-id -> {:video-id :pos :t0 :playing? :rate :clients}
(defonce conns*  (atom {})) ; cid -> {:send :room-id :host? :nick}

(defn mono-ms [] (quot (System/nanoTime) 1000000))

(defn room-for [room-id]
  (or (get @rooms* room-id)
      (when-let [row (rooms/get-by-id room-id)]
        (let [r {:video-id (:video_id row) :pos 0.0 :t0 (mono-ms)
                 :playing? false :rate 1.0 :clients {}}]
          (swap! rooms* assoc room-id r)
          r))))

(defn state-msg [room]
  {:type "state" :pos (:pos room) :t0 (:t0 room)
   :playing (:playing? room) :rate (:rate room) :now (mono-ms)})

(defn beat-msg [room] (assoc (state-msg room) :type "beat"))

(defn peers-list [room]
  (mapv (fn [[cid c]]
          {:nick (:nick c) :lag (double (or (:lag c) 0)) :buffering (boolean (:buffering c))})
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
  (swap! rooms* update-in [room-id :clients] assoc cid {:nick nick :lag 0 :buffering false}))

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
          (assoc rs room-id r))))
    (broadcast room-id (assoc (state-msg (get @rooms* room-id)) :op op))))

(defn status! [room-id cid pos buffering]
  (when-let [room (get @rooms* room-id)]
    (let [lag (when (some? pos) (- (sc/expected-pos (assoc room :now (mono-ms))) pos))]
      (swap! rooms* update-in [room-id :clients cid]
             assoc :lag (double (or lag 0)) :buffering (boolean buffering)))))

(defn start-beat-loop! []
  (future
    (loop []
      (Thread/sleep 2000)
      (doseq [[room-id room] @rooms*
              :when (seq (:clients room))]
        (broadcast room-id (beat-msg room)))
      (recur))))
