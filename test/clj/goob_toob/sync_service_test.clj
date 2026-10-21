(ns goob-toob.sync-service-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
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
