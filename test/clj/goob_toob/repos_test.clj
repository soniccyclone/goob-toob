(ns goob-toob.repos-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [goob-toob.config :as config]
            [goob-toob.db :as db]
            [goob-toob.repos.video :as videos]
            [goob-toob.repos.room :as rooms]
            [goob-toob.repos.storage :as storage]))

(defn with-tmp [f]
  (let [tmp (java.nio.file.Files/createTempDirectory "goob-test"
                                                     (into-array java.nio.file.attribute.FileAttribute []))
        dir (.toString tmp)]
    (swap! config/config merge {:storage-dir dir :ladder config/default-ladder
                                :keep-original true :workers 1})
    (db/init! (str dir "/test.db"))
    (f)
    (.delete (io/file dir))))

(use-fixtures :each with-tmp)

(deftest video-lifecycle
  (videos/insert! {:id "v1" :title "Test clip" :original-path "/tmp/v1.mp4"})
  (let [v (videos/get-by-id "v1")]
    (is (= "uploaded" (:status v)))
    (is (= "Test clip" (:title v))))
  (videos/set-status! "v1" "transcoding" :progress 0.0)
  (videos/set-progress! "v1" 0.5)
  (is (= "transcoding" (:status (videos/get-by-id "v1"))))
  (is (= 0.5 (:progress (videos/get-by-id "v1"))))
  (videos/set-status! "v1" "ready" :progress 1.0 :duration 42.0)
  (let [v (videos/get-by-id "v1")]
    (is (= "ready" (:status v)))
    (is (= 42.0 (:duration v))))
  (is (= ["v1"] (map :id (videos/list-ready))))
  (videos/delete! "v1")
  (is (nil? (videos/get-by-id "v1"))))

(deftest room-tokens-are-secret-and-unique
  (videos/insert! {:id "v2" :title "Room video" :original-path "/tmp/v2.mp4"})
  (let [r1 (rooms/create! "v2")
        r2 (rooms/create! "v2")]
    (is (not= (:token r1) (:token r2)))
    (is (<= 32 (count (:token r1))))
    (is (= (:id r1) (:id (rooms/get-by-token (:token r1)))))
    (is (nil? (rooms/get-by-token "guess-me")))
    (is (= #{"v2"} (set (map :video-id (rooms/list-all)))))))

(deftest room-requires-existing-video
  (is (thrown? Exception (rooms/create! "nope"))))

(deftest dash-path-traversal-is-blocked
  (storage/dash-file "v3" "manifest.mpd") ; creates the dir
  (spit (str (storage/dash-dir "v3") "/ok.m4s") "hi")
  (is (some? (storage/dash-file "v3" "ok.m4s")))
  (is (nil? (storage/dash-file "v3" "../../uploads/secret")))
  (is (nil? (storage/dash-file "v3" "/etc/passwd"))))
