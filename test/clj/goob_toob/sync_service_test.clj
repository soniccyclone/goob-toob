(ns goob-toob.sync-service-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [cheshire.core :as json]
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
    (is (= {:video-id "v1" :pos 0.0 :playing? false :rate 1.0 :clients {}}
           (dissoc r :t0)))
    (with-redefs [rooms/get-by-id (fn [_] (throw (ex-info "db touched" {})))]
      (is (= r (sync/room-for id))))))

(deftest room-for-is-nil-for-an-unknown-room
  (is (nil? (sync/room-for "nope")))
  (is (= {} @sync/rooms*)))

(deftest join-and-leave-maintain-the-client-map
  (let [id (video-room! "v1")]
    (sync/join! id "c1" "alice")
    (sync/join! id "c2" "bob")
    (is (= #{{:nick "alice" :lag 0.0 :buffering false}
             {:nick "bob" :lag 0.0 :buffering false}}
           (set (sync/peers-list (get @sync/rooms* id)))))
    (sync/leave! id "c1")
    (is (= [{:nick "bob" :lag 0.0 :buffering false}]
           (sync/peers-list (get @sync/rooms* id))))))

(deftest broadcast-reaches-only-the-rooms-clients
  (let [a   (video-room! "v1")
        b   (video-room! "v2")
        got (atom [])
        msg {:type "chat" :text "hi"}]
    (sync/join! a "ca" "alice")
    (sync/join! b "cb" "bob")
    (reset! sync/conns* {"ca" {:send #(swap! got conj ["ca" %])}
                         "cb" {:send #(swap! got conj ["cb" %])}})
    (sync/broadcast a msg)
    (is (= [["ca" (json/generate-string msg)]] @got))))

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

(deftest host-command-broadcasts-state-with-the-op
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 5.0 "h")
        (is (= 1 (count @*sent*)))
        (let [m (first @*sent*)]
          (is (= {:type "state" :op "play" :pos 5.0 :playing true :rate 1.0}
                 (select-keys m [:type :op :pos :playing :rate])))
          (is (number? (:t0 m)))
          (is (number? (:now m))))))))

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

(defn- beats [] (filter #(= "beat" (:type %)) @*sent*))

(deftest beat-carries-peers-and-the-slow-note
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 0.0 "host")
        (at! 11000)
        (report-behind! id 4.0)
        (sync/tick-room! id)
        (let [b   (last (beats))
              lag (:lag (first (filter #(= "laggard" (:nick %)) (:peers b))))]
          (is (< (Math/abs (+ 4.0 lag)) 1e-6))
          (is (= "laggard" (:slow-for b))))
        (sync/host-cmd! id "pause" nil "host")
        (at! 13000)
        (report-behind! id 4.0)
        (sync/tick-room! id)
        (is (nil? (:slow-for (last (beats)))))))))

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
