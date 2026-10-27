(ns goob-toob.services.sync
  "Server-authoritative room clock. One atom holds every live room:
  media position (pos seconds at monotonic instant t0), play/pause, rate,
  the current video (id and duration) and the connected clients. The server
  never trusts client clocks; it just broadcasts truth and viewers' own
  catch-up policy keeps them honest. It also owns the queue's playback side:
  auto-advance when the clock passes the current video's end, and the
  next/prev/jump commands."
  (:require [clojure.core.async :as a]
            [cheshire.core :as json]
            [goob-toob.sync-core :as sc]
            [goob-toob.log :as log]
            [goob-toob.repos.room :as rooms]
            [goob-toob.repos.video :as videos]))

(defonce rooms* (atom {})) ; room-id -> {:video-id :duration :pos :t0 :playing? :rate :clients}
(defonce conns*  (atom {})) ; cid -> {:send :room-id :host? :nick}

(defn mono-ms [] (quot (System/nanoTime) 1000000))

(defn room-for [room-id]
  (or (get @rooms* room-id)
      (when-let [row (rooms/get-by-id room-id)]
        (let [v (videos/get-by-id (:video-id row))
              r {:video-id (:video-id row) :duration (:duration v)
                 :pos 0.0 :t0 (mono-ms) :playing? false :rate 1.0 :clients {}}]
          (swap! rooms* assoc room-id r)
          r))))

(defn state-msg [room]
  {:type "state" :video-id (:video-id room) :pos (:pos room) :t0 (:t0 room)
   :playing (:playing? room) :rate (:rate room) :now (mono-ms)})

(defn beat-msg [room] (assoc (state-msg room) :type "beat"))

(defn video-descriptor
  "What the watch page needs to play a video row."
  [v]
  {:id (:id v) :title (:title v) :duration (:duration v)
   :dash (str "/dash/" (:id v) "/manifest.mpd")})

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

(defn host-cmd! [room-id op pos nick]
  (log/info "room" room-id op (or pos "-") "by" nick)
  (let [now (mono-ms)]
    (swap! rooms*
      (fn [rs]
        (let [r (get rs room-id)
              r (case op
                  "play"  (assoc r :pos (or pos (:pos r)) :t0 now :playing? true)
                  "pause" (assoc r :pos (or pos (sc/expected-pos (assoc r :now now)))
                                   :t0 now :playing? false)
                  ;; clamped to the video: the client clamps too, but the socket accepts any number (#61)
                  "seek"  (assoc r :pos (max 0.0 (min (or pos 0.0) (or (:duration r) ##Inf))) :t0 now)
                  r)]
          (assoc rs room-id (assoc r :rate 1.0)))))
    (broadcast room-id (assoc (state-msg (get @rooms* room-id)) :op op))))

;; ---- the queue's playback side ------------------------------------------------

(defn set-item!
  "Move the room to video-id (must be queued), position 0, playing.
  Persists the pointer, broadcasts the item state."
  [room-id video-id]
  (let [v   (videos/get-by-id video-id)
        now (mono-ms)]
    (rooms/set-current! room-id video-id)
    (swap! rooms* update room-id assoc :video-id video-id :duration (:duration v)
           :pos 0.0 :t0 now :playing? true :rate 1.0)
    (log/info "room" room-id "item" (:title v))
    (broadcast room-id (assoc (state-msg (get @rooms* room-id))
                              :op "item" :video (video-descriptor v) :ended false))))

(defn end-queue!
  "Pause the room at the end of its last video and say so."
  [room-id]
  (let [now (mono-ms)]
    (swap! rooms* update room-id #(assoc % :pos (double (or (:duration %) 0)) :t0 now
                                           :playing? false :rate 1.0))
    (log/info "room" room-id "reached the end of the queue")
    (broadcast room-id (assoc (state-msg (get @rooms* room-id)) :op "pause" :ended true))))

(defn item-cmd!
  "next / prev / jump (to video-id). prev at the first item is a no-op; next
  at the last ends the queue; a jump to an unqueued video does nothing."
  [room-id op video-id nick]
  (log/info "room" room-id op (or video-id "-") "by" nick)
  (let [ids    (map :video-id (rooms/queue room-id))
        cur    (get-in @rooms* [room-id :video-id])
        target (case op
                 "next" (sc/neighbour ids cur :next)
                 "prev" (sc/neighbour ids cur :prev)
                 "jump" (some #{video-id} ids)
                 nil)]
    (if target
      (set-item! room-id target)
      (when (= op "next") (end-queue! room-id)))))

(defn queue-changed!
  "An admin edited the queue: tell a live room. Nothing to tell otherwise."
  [room-id]
  (when (get @rooms* room-id)
    (broadcast room-id {:type "queue" :queue (rooms/queue room-id)
                        :video-id (get-in @rooms* [room-id :video-id])})))

;; ---- the beat -----------------------------------------------------------------

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
          drift (double (if (some? pos) (- pos (sc/expected-pos (assoc room :now now))) 0))
          c (get-in room [:clients cid])
          prev (when (:at c) (:drift c))]
      (when prev
        (let [delta (- drift prev)]
          (when (> (Math/abs delta) 1.0)
            (log/info "client" (:nick c) "jumped" (format "%+.1fs" delta)
                      "(drift" (format "%.1f" prev) "->" (format "%.1f" drift) ")"))))
      (swap! rooms* update-in [room-id :clients cid]
             (fn [c]
               (assoc c :drift drift :at now :buffering (boolean buffering)
                        :behind-since (behind-since (:behind-since c) now drift)))))))

(defn client-drifts [room now]
  (for [[_ c] (:clients room)]
    {:nick (:nick c) :drift (or (:drift c) 0.0) :age-ms (- now (or (:at c) 0))
     :buffering (boolean (:buffering c))
     :behind-ms (if-let [t (:behind-since c)] (- now t) 0)}))

(defn live-view [room-id]
  (when-let [room (get @rooms* room-id)]
    (let [now (mono-ms)]
      {:pos (:pos room) :t0 (:t0 room) :playing (:playing? room) :rate (:rate room)
       :now now :clients (vec (client-drifts room now))})))

(defn- advance!
  "The clock passed the end of the current video: next item, or the end of
  the queue. The item/pause broadcast is this tick's message."
  [room-id room]
  (if-let [next-id (sc/neighbour (map :video-id (rooms/queue room-id)) (:video-id room) :next)]
    (set-item! room-id next-id)
    (end-queue! room-id)))

(defn tick-room! [room-id]
  (let [now  (mono-ms)
        room (get @rooms* room-id)]
    (if (sc/ended? room now)
      (advance! room-id room)
      (let [{:keys [rate] laggard :for} (sc/room-rate (:rate room) (client-drifts room now))]
        (when (and (:playing? room) (not= rate (:rate room)))
          (swap! rooms* update room-id sc/reanchor now rate)
          (log/info "room" room-id "rate" rate (if laggard (str "for " laggard) ""))
          (broadcast room-id (assoc (state-msg (get @rooms* room-id)) :op "rate")))
        (let [room (get @rooms* room-id)]
          (broadcast room-id (assoc (beat-msg room)
                                    :slow-for (when (= (:rate room) (:room-slow-rate sc/tuning)) laggard)
                                    :peers (peers-list room))))))))

(defn tick-all! []
  (doseq [[room-id room] @rooms* :when (seq (:clients room))]
    (try (tick-room! room-id)
         (catch Exception e (log/info "beat" room-id "failed:" (.getMessage e))))))

(defn start-beat-loop! []
  (future
    (loop []
      (Thread/sleep 2000)
      (tick-all!)
      (recur))))
