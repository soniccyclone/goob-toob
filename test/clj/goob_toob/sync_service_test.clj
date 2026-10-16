(ns goob-toob.sync-service-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [goob-toob.config :as config]
            [goob-toob.db :as db]
            [goob-toob.repos.video :as videos]
            [goob-toob.repos.room :as rooms]
            [goob-toob.services.sync :as sync]
            [goob-toob.sync-core :as sc]))

(defn with-tmp [f]
  (let [tmp (java.nio.file.Files/createTempDirectory "goob-test"
                                                     (into-array java.nio.file.attribute.FileAttribute []))
        dir (.toString tmp)]
    (swap! config/config merge {:storage-dir dir :ladder config/default-ladder
                                :keep-original true :workers 1})
    (db/init! (str dir "/test.db"))
    (reset! sync/rooms* {})
    (f)
    (reset! sync/rooms* {})
    (.delete (io/file dir))))

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
        (sync/host-cmd! id "play" 0.0)
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
        (sync/host-cmd! id "pause" 10.0)
        (at! 11000)
        (report-behind! id 4.0)
        (sync/tick-room! id)
        (is (= 1.0 (:rate (room id))))))))

(deftest tick-gives-up-after-the-window
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 0.0)
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
        (sync/host-cmd! id "play" 0.0)
        (swap! sync/rooms* assoc-in [id :rate] 0.97)
        (sync/host-cmd! id "seek" 5.0)
        (is (= 1.0 (:rate (room id))))))))

(deftest status-records-drift-with-behind-meaning-negative
  (run
    (fn []
      (let [id (room-id!)]
        (sync/host-cmd! id "play" 0.0)
        (at! 11000)
        (report-behind! id 4.0)
        (is (< (Math/abs (+ 4.0 (get-in (room id) [:clients "lag" :drift]))) 1e-9))
        (is (neg? (:lag (first (filter #(= "laggard" (:nick %))
                                       (sync/peers-list (room id)))))))))))
