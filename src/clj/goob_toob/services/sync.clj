(ns goob-toob.services.sync
  "Server-authoritative room clock. One atom holds every live room:
  media position (pos seconds at monotonic instant t0), play/pause, rate,
  the current video (id and duration) and the connected clients. The server
  never trusts client clocks; it just broadcasts truth and viewers' own
  catch-up policy keeps them honest. It also owns the queue's playback side:
  auto-advance when the clock passes the current video's end, the
  next/prev/jump commands, and the edits that move the pointer (removing
  the current item skips the room; removing the last empties it).

  An empty room (:video-id nil, :duration nil) stands paused at 0: play and
  seek are refused, the beat still goes out (peers) with :video-id nil, and
  the first item added becomes current, paused, for the host to start."
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
        (let [v (some-> (:video-id row) videos/get-by-id)
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

(defn- empty-room? [room-id] (nil? (get-in @rooms* [room-id :video-id])))

(defn host-cmd! [room-id op pos nick]
  (if (and (#{"play" "seek"} op) (empty-room? room-id))
    (log/info "room" room-id op "by" nick "refused: nothing queued")
    (do
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
        (broadcast room-id (assoc (state-msg (get @rooms* room-id)) :op op))))))

;; ---- the queue's playback side ------------------------------------------------

(defn set-item!
  "Move the room to video-id (must be queued), position 0, playing unless
  :playing? false (the first item of an empty room lands paused). Persists
  the pointer, broadcasts the item state."
  [room-id video-id & {:keys [playing?] :or {playing? true}}]
  (let [v   (videos/get-by-id video-id)
        now (mono-ms)]
    (rooms/set-current! room-id video-id)
    (swap! rooms* update room-id assoc :video-id video-id :duration (:duration v)
           :pos 0.0 :t0 now :playing? playing? :rate 1.0)
    (log/info "room" room-id "item" (:title v) (if playing? "" "(paused)"))
    (broadcast room-id (assoc (state-msg (get @rooms* room-id))
                              :op "item" :video (video-descriptor v) :ended false))))

(defn- clear-item!
  "The queue ran out from under the room: blank stage, paused at 0. The
  item broadcast carries :video nil so the watch page empties itself."
  [room-id]
  (let [now (mono-ms)]
    (rooms/set-current! room-id nil)
    (swap! rooms* update room-id assoc :video-id nil :duration nil
           :pos 0.0 :t0 now :playing? false :rate 1.0)
    (log/info "room" room-id "item" "-" "(nothing queued)")
    (broadcast room-id (assoc (state-msg (get @rooms* room-id))
                              :op "item" :video nil :ended false))))

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
      (when (and cur (= op "next")) (end-queue! room-id)))))

(defn queue-changed!
  "An admin edited the queue: tell a live room. Nothing to tell otherwise."
  [room-id]
  (when (get @rooms* room-id)
    (broadcast room-id {:type "queue" :queue (rooms/queue room-id)
                        :video-id (get-in @rooms* [room-id :video-id])})))

(defn- current-video
  "The room's current video id: the live room's when loaded, else the row's."
  [room-id]
  (if-let [r (get @rooms* room-id)]
    (:video-id r)
    (:video-id (rooms/get-by-id room-id))))

(defn remove-item!
  "An admin removes video-id from the queue. Removing the current item
  skips the room first: to the next item, else the previous, else to an
  empty stage. A live room moves with set-item!/clear-item! (item
  broadcast); a cold one just has its pointer rewritten. Then the row goes
  and the room hears the new queue."
  [room-id video-id]
  (let [cur (current-video room-id)]
    (when (and cur (= cur video-id))
      (let [ids    (map :video-id (rooms/queue room-id))
            target (or (sc/neighbour ids cur :next) (sc/neighbour ids cur :prev))]
        (log/info "room" room-id "removing the current item" video-id "->" (or target "nothing"))
        (cond
          (not (get @rooms* room-id)) (rooms/set-current! room-id target)
          target                      (set-item! room-id target)
          :else                       (clear-item! room-id))))
    (rooms/remove-item! room-id video-id)
    (queue-changed! room-id)))

(defn add-item!
  "An admin appends video-id to the queue. In an empty room it becomes the
  current item, paused at 0 (a live room gets the item broadcast), so the
  host presses play as on a fresh room."
  [room-id video-id]
  (let [was-empty? (nil? (current-video room-id))]
    (rooms/add-item! room-id video-id)
    (when was-empty?
      (if (get @rooms* room-id)
        (set-item! room-id video-id :playing? false)
        (rooms/set-current! room-id video-id)))
    (queue-changed! room-id)))

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
