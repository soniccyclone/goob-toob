(ns goob-toob.repos-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [goob-toob.config :as config]
            [goob-toob.db :as db]
            [goob-toob.repos.video :as videos]
            [goob-toob.repos.room :as rooms]
            [goob-toob.repos.storage :as storage]
            [goob-toob.test-util :refer [with-tmp]]))

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

(defn- video! [id & {:keys [title status] :or {title "Clip"}}]
  (videos/insert! {:id id :title title :original-path (str "/tmp/" id ".mp4")})
  (when status (videos/set-status! id status))
  id)

(deftest requeue-stuck-returns-only-mid-flight-videos
  (video! "a-up")
  (video! "b-tr" :status "transcoding")
  (video! "c-ok" :status "ready")
  (video! "d-no" :status "failed")
  (is (= ["a-up" "b-tr"] (sort (videos/requeue-stuck)))))

(deftest set-status-ready-clears-a-previous-error
  (video! "v1")
  (videos/set-status! "v1" "transcoding" :progress 0.4)
  (videos/set-status! "v1" "failed" :error "boom")
  (is (= "boom" (:error (videos/get-by-id "v1"))))
  (videos/set-status! "v1" "ready" :error nil)
  (let [v (videos/get-by-id "v1")]
    (is (nil? (:error v)))
    (is (= 0.4 (:progress v)))))

(deftest list-all-is-newest-first
  (doseq [[id at] [["a" "2026-01-01 00:00:00"] ["b" "2026-01-02 00:00:00"]]]
    (jdbc/execute! (db/db)
      (sql/format {:insert-into :videos
                   :values [{:id id :title id :status "uploaded" :created-at at}]})))
  (is (= ["b" "a"] (map :id (videos/list-all)))))

(deftest rooms-list-all-joins-the-video-title-and-status
  (video! "v1" :title "T")
  (rooms/create! "v1")
  (is (= {:title "T" :status "uploaded" :allow-control 0}
         (select-keys (first (rooms/list-all)) [:title :status :allow-control]))))

(deftest set-control-flips-the-flag
  (video! "v1")
  (let [id (:id (rooms/create! "v1"))]
    (rooms/set-control! id true)
    (is (= 1 (:allow-control (rooms/get-by-id id))))
    (rooms/set-control! id false)
    (is (= 0 (:allow-control (rooms/get-by-id id))))))

(deftest deleting-a-video-closes-its-rooms
  (video! "v1")
  (let [id (:id (rooms/create! "v1"))]
    (videos/delete! "v1")
    (is (nil? (rooms/get-by-id id)))))

(defn- tmp-file [content]
  (let [f (java.io.File/createTempFile "goob-upload" ".tmp")]
    (spit f content)
    f))

(deftest save-upload-moves-the-file-under-uploads
  (let [tmp  (tmp-file "data")
        dest (storage/save-upload! tmp "v9" "mkv")]
    (is (= (.getCanonicalFile (io/file (config/storage-dir) "uploads" "v9.mkv"))
           (.getCanonicalFile dest)))
    (is (not (.exists tmp)))
    (is (= "data" (slurp (storage/find-original "v9"))))))

(deftest save-upload-rejects-an-id-that-escapes-the-root
  (let [tmp (tmp-file "data")]
    (try
      (is (thrown? Exception (storage/save-upload! tmp "../../x" "mkv")))
      (finally (.delete tmp)))))

(deftest delete-video-files-removes-dash-and-original
  (spit (io/file (storage/dash-dir "v1") "a.m4s") "seg")
  (spit (io/file (storage/uploads-dir) "v1.mkv") "orig")
  (storage/delete-video-files! "v1")
  (is (not (.exists (io/file (config/storage-dir) "dash" "v1"))))
  (is (not (.exists (io/file (config/storage-dir) "uploads" "v1.mkv"))))
  (is (.isDirectory (io/file (config/storage-dir) "uploads"))))
