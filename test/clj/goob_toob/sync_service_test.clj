(ns goob-toob.sync-service-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [goob-toob.repos.video :as videos]
            [goob-toob.repos.room :as rooms]
            [goob-toob.services.sync :as sync]
            [goob-toob.sync-core :as sc]
            [goob-toob.test-util :refer [with-tmp]]))

(use-fixtures :each with-tmp)

(def ^:dynamic *clock* nil)
(def ^:dynamic *sent* nil)

(defn- at! [ms] (reset! *clock* ms))

(defn- rate-msgs [] (filter #(= "rate" (:op %)) @*sent*))

(defn- room-id! []
  (videos/insert! {:id "v1" :title "Clip" :original-path "/tmp/v1.mp4"})
  (let [id (:id (rooms/create! "v1"))]
    (sync/join! id "host" "host")
    (sync/join! id "lag" "laggard")
    id))

(defn- run [f]
  (binding [*clock* (atom 1000) *sent* (atom [])]
    (with-redefs [sync/mono-ms (fn [] @*clock*)
                  sync/broadcast (fn [_ msg] (swap! *sent* conj msg))]
      (f))))

(defn- room [id] (get @sync/rooms* id))

(defn- expected [id] (sc/expected-pos (assoc (room id) :now @*clock*)))

(defn- report-behind! [id secs]
  (sync/status! id "lag" (- (expected id) secs) false))

(deftest tick-slows-a-playing-room-for-a-laggard-and-recovers
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 0.0 "host")
        (at! 11000)
        (report-behind! id 4.0)
        (let [at-tick (expected id)]
          (sync/tick-room! id)
          (is (= 0.97 (:rate (room id))))
          (is (= at-tick (:pos (room id)))))
        (is (= 1 (count (rate-msgs))))
        (at! 13000)
        (report-behind! id 0.5)
        (sync/tick-room! id)
        (is (= 1.0 (:rate (room id))))
        (is (= 2 (count (rate-msgs))))))))

(deftest tick-leaves-a-paused-room-alone
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "pause" 10.0 "host")
        (at! 11000)
        (report-behind! id 4.0)
        (sync/tick-room! id)
        (is (= 1.0 (:rate (room id))))))))

(deftest tick-gives-up-after-the-window
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 0.0 "host")
        (report-behind! id 4.0)
        (at! 11000)
        (report-behind! id 4.0)
        (sync/tick-room! id)
        (is (= 0.97 (:rate (room id))))
        (at! 201000)
        (report-behind! id 4.0)
        (sync/tick-room! id)
        (is (= 1.0 (:rate (room id))))))))

(deftest host-command-resets-the-rate
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 0.0 "host")
        (swap! sync/rooms* assoc-in [id :rate] 0.97)
        (sync/host-cmd! id "seek" 5.0 "host")
        (is (= 1.0 (:rate (room id))))))))

(deftest host-command-is-logged-with-the-nick
  (run
    (fn []
      (let [id (room-id!)]
        (is (.contains (with-out-str (sync/host-cmd! id "seek" 5.0 "Nathan"))
                       "seek 5.0 by Nathan"))))))

(deftest status-logs-a-jump-between-reports
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 0.0 "host")
        (at! 11000)
        (report-behind! id 4.0)
        (at! 13000)
        (is (.contains (with-out-str (report-behind! id 0.5)) "laggard jumped +3.5s"))
        (at! 15000)
        (is (= "" (with-out-str (report-behind! id 0.6))))))))

(deftest status-records-drift-with-behind-meaning-negative
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 0.0 "host")
        (at! 11000)
        (report-behind! id 4.0)
        (is (< (Math/abs (+ 4.0 (get-in (room id) [:clients "lag" :drift]))) 1e-9))
        (is (neg? (:lag (first (filter #(= "laggard" (:nick %))
                                       (sync/peers-list (room id)))))))))))

(deftest give-up-resets-once-the-viewer-is-back-in-band
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 0.0 "host")
        (report-behind! id 4.0)
        (at! 11000)
        (report-behind! id 0.5)
        (at! 201000)
        (report-behind! id 4.0)
        (sync/tick-room! id)
        (is (= 0.97 (:rate (room id))))))))

(deftest pause-without-a-position-uses-the-slowed-clock
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 0.0 "host")
        (at! 11000)
        (swap! sync/rooms* update id sc/reanchor 11000 0.97)
        (at! 111000)
        (sync/host-cmd! id "pause" nil "host")
        (is (< (Math/abs (- 107.0 (:pos (room id)))) 1e-6))
        (is (= 1.0 (:rate (room id))))))))

(defn- video-room! [vid]
  (videos/insert! {:id vid :title "Clip" :original-path (str "/tmp/" vid ".mp4")})
  (:id (rooms/create! vid)))

(deftest room-for-loads-a-room-from-the-db-once
  (let [id (video-room! "v1")
        r  (sync/room-for id)]
    (is (= {:video-id "v1" :duration nil :pos 0.0 :playing? false :rate 1.0 :clients {}}
           (dissoc r :t0)))
    (with-redefs [rooms/get-by-id (fn [_] (throw (ex-info "db touched" {})))]
      (is (= r (sync/room-for id))))))

(deftest room-for-is-nil-for-an-unknown-room
  (is (nil? (sync/room-for "nope")))
  (is (= {} @sync/rooms*)))

(deftest send-to-swallows-a-dead-socket
  (reset! sync/conns* {"c" {:send (fn [_] (throw (java.io.IOException. "closed")))}})
  (is (nil? (sync/send-to "c" {:type "ping"}))))

(deftest host-play-without-a-position-resumes-where-it-was
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "pause" 10.0 "h")
        (sync/host-cmd! id "play" nil "h")
        (is (= 10.0 (:pos (room id))))
        (is (true? (:playing? (room id))))))))

(deftest host-seek-without-a-position-goes-to-zero
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "pause" 7.0 "h")
        (sync/host-cmd! id "seek" nil "h")
        (is (= 0.0 (:pos (room id))))))))

(deftest unknown-op-leaves-the-room-alone
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 5.0 "h")
        (swap! sync/rooms* assoc-in [id :rate] 0.97)
        (reset! *sent* [])
        (let [before (room id)]
          (at! 9000)
          (sync/host-cmd! id "dance" 1.0 "h")
          (is (= (assoc before :rate 1.0) (room id)))
          (is (= 1 (count @*sent*))))))))

(deftest status-ignores-a-room-that-is-not-live
  (is (nil? (sync/status! "nope" "c" 1.0 false)))
  (is (= {} @sync/rooms*)))

(deftest beat-loop-survives-a-broken-room
  (let [sent    (atom [])
        laggard {"lag" {:nick "laggard" :drift -4.0 :at 1000 :behind-since 1000}}]
    (reset! sync/rooms*
            {"broken" {:pos "junk" :t0 1000 :playing? true :rate 1.0 :clients laggard}
             "good"   {:pos 0.0 :t0 1000 :playing? true :rate 1.0
                       :clients {"c" {:nick "c" :drift 0.0 :at 1000}}}})
    (with-redefs [sync/mono-ms (fn [] 2000)
                  sync/broadcast (fn [room-id msg] (swap! sent conj [room-id (:type msg)]))]
      (is (str/includes? (with-out-str (sync/tick-all!)) "beat broken failed:")))
    (is (some #{["good" "beat"]} @sent))))

(deftest live-view-reports-every-client
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 0.0 "host")
        (at! 11000)
        (sync/status! id "host" (expected id) true)
        (report-behind! id 4.0)
        (let [view (sync/live-view id)
              by-nick (into {} (map (juxt :nick identity)) (:clients view))
              lag (by-nick "laggard")]
          (is (= {:pos 0.0 :t0 1000 :playing true :rate 1.0 :now 11000}
                 (dissoc view :clients)))
          (is (= #{"host" "laggard"} (set (keys by-nick))))
          (is (< (Math/abs (+ 4.0 (:drift lag))) 1e-9))
          (is (= 0 (:behind-ms lag)))
          (is (true? (:buffering (by-nick "host")))))
        (at! 13000)
        (let [lag (first (filter #(= "laggard" (:nick %)) (:clients (sync/live-view id))))]
          (is (= 2000 (:behind-ms lag)))
          (is (= 2000 (:age-ms lag))))
        (is (nil? (sync/live-view "nope")))))))

;; ---- queues -----------------------------------------------------------------

(defn- queue!
  "A live room (host and laggard joined) whose queue is the given videos,
  [id duration] each, in order."
  [& specs]
  (doseq [[vid dur] specs]
    (videos/insert! {:id vid :title (str "Clip " vid) :original-path (str "/tmp/" vid ".mp4")})
    (videos/set-duration! vid dur))
  (let [id (:id (rooms/create! (ffirst specs)))]
    (doseq [[vid] (rest specs)] (rooms/add-item! id vid))
    (sync/join! id "host" "host")
    (sync/join! id "lag" "laggard")
    id))

(defn- playback [id] (select-keys (room id) [:video-id :pos :playing? :t0]))

(deftest room-for-carries-the-current-video-and-duration
  (run
    (fn []
      (let [id (queue! ["v1" 42.0])]
        (is (= {:video-id "v1" :duration 42.0} (select-keys (room id) [:video-id :duration])))))))

(deftest tick-advances-at-the-end-of-the-video
  (run
    (fn []
      (let [id (queue! ["v1" 10.0] ["v2" 20.0])]
        (at! 0)
        (sync/host-cmd! id "play" 0.0 "host")
        (reset! *sent* [])
        (at! 10000)
        (sync/tick-room! id)
        (is (= {:video-id "v2" :duration 20.0 :pos 0.0 :t0 10000 :playing? true :rate 1.0}
               (dissoc (room id) :clients)))
        (is (= "v2" (:video-id (rooms/get-by-id id))))
        (is (= ["item"] (map :op @*sent*)))
        (let [m (first @*sent*)]
          (is (= "state" (:type m)))
          (is (= "v2" (:video-id m)))
          (is (= {:id "v2" :title "Clip v2" :duration 20.0 :dash "/dash/v2/manifest.mpd"}
                 (:video m)))
          (is (false? (:ended m))))))))

(deftest tick-ends-the-queue-after-the-last-video
  (run
    (fn []
      (let [id (queue! ["v1" 10.0])]
        (at! 0)
        (sync/host-cmd! id "play" 0.0 "host")
        (reset! *sent* [])
        (at! 12000)
        (sync/tick-room! id)
        (is (false? (:playing? (room id))))
        (is (= 10.0 (:pos (room id))))
        (is (= "v1" (:video-id (room id))))
        (is (= [["pause" true]] (map (juxt :op :ended) @*sent*)))))))

(deftest tick-does-not-advance-a-paused-room
  (run
    (fn []
      (let [id (queue! ["v1" 10.0] ["v2" 20.0])]
        (sync/host-cmd! id "pause" 10.0 "host")
        (reset! *sent* [])
        (at! 30000)
        (let [before (room id)]
          (sync/tick-room! id)
          (is (= before (room id)))
          (is (= "v1" (:video-id (rooms/get-by-id id))))
          (is (= ["beat"] (map :type @*sent*)))
          (is (not-any? #(= "item" (:op %)) @*sent*)))))))

(deftest next-and-prev-move-and-start-playing
  (run
    (fn []
      (let [id (queue! ["v1" 10.0] ["v2" 20.0])]
        (sync/host-cmd! id "pause" 5.0 "host")
        (at! 2000)
        (sync/item-cmd! id "next" nil "host")
        (is (= {:video-id "v2" :pos 0.0 :playing? true :t0 2000} (playback id)))
        (is (= "v2" (:video-id (rooms/get-by-id id))))
        (at! 3000)
        (sync/item-cmd! id "prev" nil "host")
        (is (= {:video-id "v1" :pos 0.0 :playing? true :t0 3000} (playback id)))
        (is (= "v1" (:video-id (rooms/get-by-id id))))
        (reset! *sent* [])
        (let [before (room id)]
          (sync/item-cmd! id "prev" nil "host")
          (is (= before (room id)))
          (is (empty? @*sent*)))))))

(deftest jump-refuses-an-unqueued-video
  (run
    (fn []
      (let [id (queue! ["v1" 10.0] ["v2" 20.0])]
        (reset! *sent* [])
        (let [before (room id)]
          (sync/item-cmd! id "jump" "nope" "host")
          (is (= before (room id)))
          (is (= "v1" (:video-id (rooms/get-by-id id))))
          (is (empty? @*sent*)))
        (sync/item-cmd! id "jump" "v2" "host")
        (is (= "v2" (:video-id (room id))))
        (is (= ["item"] (map :op @*sent*)))))))

(deftest queue-changed-broadcasts-only-to-a-live-room
  (run
    (fn []
      (videos/insert! {:id "v1" :title "Clip" :original-path "/tmp/v1.mp4"})
      (videos/insert! {:id "v2" :title "Clip 2" :original-path "/tmp/v2.mp4"})
      (let [id (:id (rooms/create! "v1"))]
        (rooms/add-item! id "v2")
        (sync/queue-changed! id)
        (is (empty? @*sent*))
        (sync/join! id "c" "viewer")
        (sync/queue-changed! id)
        (is (= 1 (count @*sent*)))
        (let [m (first @*sent*)]
          (is (= "queue" (:type m)))
          (is (= "v1" (:video-id m)))
          (is (= [["v1" "Clip" 0] ["v2" "Clip 2" 1]]
                 (map (juxt :video-id :title :position) (:queue m)))))))))

(deftest host-seek-is-clamped-to-the-video
  (run
    (fn []
      (let [id (queue! ["v1" 42.0])]
        (sync/host-cmd! id "seek" 99.0 "host")
        (is (= 42.0 (:pos (room id))))
        (sync/host-cmd! id "seek" -5.0 "host")
        (is (= 0.0 (:pos (room id))))
        (sync/host-cmd! id "seek" 7.0 "host")
        (is (= 7.0 (:pos (room id))))))))

;; ---- queue edits that move the pointer ---------------------------------------

(defn- ops [] (mapv (fn [m] (or (:op m) (:type m))) @*sent*))

(deftest removing-the-current-item-skips-a-live-room-to-the-next
  (run
    (fn []
      (let [id (queue! ["v1" 10.0] ["v2" 20.0] ["v3" 30.0])]
        (sync/host-cmd! id "play" 0.0 "host")
        (at! 5000)
        (reset! *sent* [])
        (sync/remove-item! id "v1")
        (is (= {:video-id "v2" :duration 20.0 :pos 0.0 :t0 5000 :playing? true :rate 1.0}
               (dissoc (room id) :clients)))
        (is (= "v2" (:video-id (rooms/get-by-id id))))
        (is (= [["v2" 0] ["v3" 1]] (mapv (juxt :video-id :position) (rooms/queue id))))
        (is (= ["item" "queue"] (ops)))
        (let [[item queue] @*sent*]
          (is (= "v2" (get-in item [:video :id])))
          (is (true? (:playing item)))
          (is (= ["v2" "v3"] (mapv :video-id (:queue queue))))
          (is (= "v2" (:video-id queue))))))))

(deftest removing-a-current-middle-item-goes-forward-not-back
  (run
    (fn []
      (let [id (queue! ["v1" 10.0] ["v2" 20.0] ["v3" 30.0])]
        (sync/item-cmd! id "jump" "v2" "host")
        (reset! *sent* [])
        (sync/remove-item! id "v2")
        (is (= "v3" (:video-id (room id))) "next wins over prev")
        (is (= "v3" (:video-id (rooms/get-by-id id))))
        (is (= [["v1" 0] ["v3" 1]] (mapv (juxt :video-id :position) (rooms/queue id))))
        (is (= "v3" (get-in (first @*sent*) [:video :id])))))))

(deftest removing-the-current-last-item-falls-back-to-the-previous
  (run
    (fn []
      (let [id (queue! ["v1" 10.0] ["v2" 20.0])]
        (sync/item-cmd! id "next" nil "host")
        (reset! *sent* [])
        (sync/remove-item! id "v2")
        (is (= "v1" (:video-id (room id))))
        (is (= 10.0 (:duration (room id))))
        (is (= "v1" (:video-id (rooms/get-by-id id))))
        (is (= [["v1" 0]] (mapv (juxt :video-id :position) (rooms/queue id))))
        (is (= ["item" "queue"] (ops)))
        (is (= "v1" (get-in (first @*sent*) [:video :id])))))))

(deftest removing-another-item-leaves-playback-alone
  (run
    (fn []
      (let [id (queue! ["v1" 10.0] ["v2" 20.0])]
        (sync/host-cmd! id "play" 3.0 "host")
        (reset! *sent* [])
        (let [before (room id)]
          (sync/remove-item! id "v2")
          (is (= before (room id)))
          (is (= "v1" (:video-id (rooms/get-by-id id))))
          (is (= ["queue"] (ops)))
          (is (= ["v1"] (mapv :video-id (:queue (first @*sent*))))))))))

(defn- empty-live-room! []
  (let [id (queue! ["v1" 10.0])]
    (sync/host-cmd! id "play" 0.0 "host")
    (at! 4000)
    (reset! *sent* [])
    (sync/remove-item! id "v1")
    id))

(deftest removing-the-only-item-empties-a-live-room
  (run
    (fn []
      (let [id (empty-live-room!)]
        (is (= {:video-id nil :duration nil :pos 0.0 :t0 4000 :playing? false :rate 1.0}
               (dissoc (room id) :clients)))
        (is (nil? (:video-id (rooms/get-by-id id))))
        (is (= [] (rooms/queue id)))
        (is (= ["item" "queue"] (ops)))
        (let [[item queue] @*sent*]
          (is (= "state" (:type item)))
          (is (contains? item :video))
          (is (nil? (:video item)))
          (is (nil? (:video-id item)))
          (is (false? (:playing item)))
          (is (= 0.0 (:pos item)))
          (is (false? (:ended item)))
          (is (= [] (:queue queue)))
          (is (nil? (:video-id queue))))
        (is (some? (sync/room-for id)) "the room stays live")))))

(deftest an-empty-room-refuses-play-seek-and-next
  (run
    (fn []
      (let [id (empty-live-room!)]
        (reset! *sent* [])
        (let [before (room id)
              out    (with-out-str
                       (sync/host-cmd! id "play" 0.0 "host")
                       (sync/host-cmd! id "seek" 5.0 "host")
                       (sync/item-cmd! id "next" nil "host"))]
          (is (= before (room id)))
          (is (empty? @*sent*))
          (is (str/includes? out "play by host refused: nothing queued"))
          (is (str/includes? out "seek by host refused: nothing queued")))
        ;; pause is harmless and still answered
        (sync/host-cmd! id "pause" nil "host")
        (is (= ["pause"] (ops)))
        (is (= 0.0 (:pos (room id))))))))

(deftest the-beat-still-goes-out-to-an-empty-room
  (run
    (fn []
      (let [id (empty-live-room!)]
        (reset! *sent* [])
        (at! 30000)
        (sync/tick-room! id)
        (is (= ["beat"] (ops)))
        (let [m (first @*sent*)]
          (is (nil? (:video-id m)))
          (is (false? (:playing m)))
          (is (= 2 (count (:peers m)))))
        (is (nil? (:video-id (room id))))
        (is (nil? (:video-id (rooms/get-by-id id))))))))

(deftest adding-to-an-empty-live-room-makes-it-current-paused
  (run
    (fn []
      (let [id (empty-live-room!)]
        (videos/insert! {:id "v2" :title "Clip v2" :original-path "/tmp/v2.mp4"})
        (videos/set-duration! "v2" 20.0)
        (reset! *sent* [])
        (at! 9000)
        (sync/add-item! id "v2")
        (is (= {:video-id "v2" :duration 20.0 :pos 0.0 :t0 9000 :playing? false :rate 1.0}
               (dissoc (room id) :clients)))
        (is (= "v2" (:video-id (rooms/get-by-id id))))
        (is (= [["v2" 0]] (mapv (juxt :video-id :position) (rooms/queue id))))
        (is (= ["item" "queue"] (ops)))
        (let [[item queue] @*sent*]
          (is (= {:id "v2" :title "Clip v2" :duration 20.0 :dash "/dash/v2/manifest.mpd"}
                 (:video item)))
          (is (false? (:playing item)))
          (is (= 0.0 (:pos item)))
          (is (= "v2" (:video-id queue))))
        ;; the host starts it as on a fresh room
        (sync/host-cmd! id "play" 0.0 "host")
        (is (true? (:playing? (room id))))))))

(deftest adding-to-a-room-with-a-video-only-appends
  (run
    (fn []
      (let [id (queue! ["v1" 10.0])]
        (videos/insert! {:id "v2" :title "Clip v2" :original-path "/tmp/v2.mp4"})
        (sync/host-cmd! id "play" 2.0 "host")
        (reset! *sent* [])
        (let [before (room id)]
          (sync/add-item! id "v2")
          (is (= before (room id)))
          (is (= "v1" (:video-id (rooms/get-by-id id))))
          (is (= ["queue"] (ops)))
          (is (= ["v1" "v2"] (mapv :video-id (:queue (first @*sent*))))))))))

(defn- cold-queue!
  "A room nobody has joined, queued with the given video ids."
  [& vids]
  (doseq [vid vids]
    (videos/insert! {:id vid :title (str "Clip " vid) :original-path (str "/tmp/" vid ".mp4")}))
  (let [id (:id (rooms/create! (first vids)))]
    (doseq [vid (rest vids)] (rooms/add-item! id vid))
    id))

(deftest removing-items-of-a-cold-room-rewrites-the-pointer
  (run
    (fn []
      (let [id      (cold-queue! "v1" "v2" "v3")
            pointer #(:video-id (rooms/get-by-id id))]
        (rooms/set-current! id "v2")
        (sync/remove-item! id "v2")
        (is (= "v3" (pointer)) "current in the middle: next, not prev")
        (rooms/add-item! id "v2")
        (sync/remove-item! id "v3")
        (is (= "v2" (pointer)) "no next: prev")
        (sync/remove-item! id "v1")
        (is (= "v2" (pointer)) "not current: pointer stays")
        (is (= [["v2" 0]] (mapv (juxt :video-id :position) (rooms/queue id))))
        (rooms/add-item! id "v1")
        (sync/remove-item! id "v2")
        (is (= "v1" (pointer)) "no next: prev")
        (sync/remove-item! id "v1")
        (is (nil? (pointer)) "the only item: empty")
        (is (= [] (rooms/queue id)))
        (is (empty? @*sent*) "nobody to tell")
        (is (= {} @sync/rooms*) "the room was never loaded")
        (is (= {:video-id nil :duration nil :pos 0.0 :playing? false :rate 1.0 :clients {}}
               (dissoc (sync/room-for id) :t0))
            "loading the empty room works")))))

(deftest adding-to-a-cold-empty-room-sets-the-pointer
  (run
    (fn []
      (let [id (cold-queue! "v1")]
        (sync/remove-item! id "v1")
        (videos/insert! {:id "v2" :title "Clip v2" :original-path "/tmp/v2.mp4"})
        (sync/add-item! id "v2")
        (is (= "v2" (:video-id (rooms/get-by-id id))))
        (is (= [["v2" 0]] (mapv (juxt :video-id :position) (rooms/queue id))))
        (is (empty? @*sent*))
        (is (= {} @sync/rooms*))))))
