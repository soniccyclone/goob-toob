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

(deftest deleting-an-unqueued-video-just-deletes-it
  (video! "v1")
  (videos/delete! "v1")
  (is (nil? (videos/get-by-id "v1"))))

;; ---- queues -----------------------------------------------------------------

(defn- ex-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- positions [room-id]
  (mapv (juxt :video-id :position) (rooms/queue room-id)))

(deftest create-room-queues-its-first-video
  (video! "v1" :title "S01E01")
  (let [id (:id (rooms/create! "v1"))
        row (first (rooms/list-all))]
    (is (= [["v1" 0]] (positions id)))
    (is (= "S01E01" (:title (first (rooms/queue id)))))
    (is (= {:id id :title "S01E01" :items 1 :video-id "v1"}
           (select-keys row [:id :title :items :video-id])))))

(deftest create-room-takes-a-title
  (video! "v1" :title "S01E01")
  (rooms/create! "v1" :title "Season 1")
  (is (= "Season 1" (:title (first (rooms/list-all))))))

(deftest add-item-appends-in-order
  (doseq [v ["v1" "v2" "v3"]] (video! v))
  (let [id (:id (rooms/create! "v1"))]
    (rooms/add-item! id "v2")
    (rooms/add-item! id "v3")
    (is (= [["v1" 0] ["v2" 1] ["v3" 2]] (positions id)))
    (is (= 3 (:items (first (rooms/list-all)))))))

(deftest add-item-refuses-a-duplicate
  (video! "v1")
  (let [id (:id (rooms/create! "v1"))]
    (is (= :conflict (ex-type #(rooms/add-item! id "v1"))))
    (is (= :bad-request (ex-type #(rooms/add-item! id "nope"))))
    (is (= [["v1" 0]] (positions id)))))

(deftest remove-item-drops-the-current-video-too
  ;; the repo only edits the queue; moving the pointer off a removed
  ;; current item is services.sync/remove-item!'s job
  (doseq [v ["v1" "v2"]] (video! v))
  (let [id (:id (rooms/create! "v1"))]
    (rooms/add-item! id "v2")
    (is (nil? (ex-type #(rooms/remove-item! id "v1"))))
    (is (= [["v2" 0]] (positions id)))
    (is (= "v1" (:video-id (rooms/get-by-id id))))))

(deftest set-current-nil-empties-the-stage
  (video! "v1" :title "S01E01")
  (let [id (:id (rooms/create! "v1"))]
    (rooms/set-current! id nil)
    (is (nil? (:video-id (rooms/get-by-id id))))
    (is (= [["v1" 0]] (positions id)) "the queue is untouched")
    (rooms/set-current! id "v1")
    (is (= "v1" (:video-id (rooms/get-by-id id))))))

(deftest an-empty-room-is-still-listed
  (video! "v1" :title "S01E01")
  (let [id (:id (rooms/create! "v1"))]
    (rooms/remove-item! id "v1")
    (rooms/set-current! id nil)
    (is (= [] (rooms/queue id)))
    (let [row (first (rooms/list-all))]
      (is (= {:id id :title "S01E01" :video-id nil :video-title nil :status nil :items 0}
             (select-keys row [:id :title :video-id :video-title :status :items]))
          "the title create! stored stays; the video fields are nil"))
    (rooms/set-title! id "  ")
    (is (= [{:title nil :video-id nil}]
           (map #(select-keys % [:title :video-id]) (rooms/list-all)))
        "a cleared title has no video title to fall back on")
    (rooms/set-title! id "Night 2")
    (is (= "Night 2" (:title (first (rooms/list-all)))))
    (is (= [] (rooms/rooms-using "v1")) "nothing queues v1 any more")))

(deftest rooms-using-survives-a-room-without-a-pointer
  (doseq [v ["v1" "v2"]] (video! v :title (str "Clip " v)))
  (let [id (:id (rooms/create! "v1"))]
    (rooms/add-item! id "v2")
    (rooms/set-current! id nil)
    (is (= ["Clip v1"] (rooms/rooms-using "v2")) "the title stored at create! time")
    (rooms/set-title! id "  ")
    (is (= [nil] (rooms/rooms-using "v2")) "cleared, with no video to fall back on")
    (rooms/set-title! id "A")
    (is (= ["A"] (rooms/rooms-using "v2")))))

(deftest create-still-requires-a-video
  (is (thrown? Exception (rooms/create! nil)))
  (is (empty? (rooms/list-all))))

(deftest remove-item-renumbers
  (doseq [v ["v1" "v2" "v3"]] (video! v))
  (let [id (:id (rooms/create! "v1"))]
    (rooms/add-item! id "v2")
    (rooms/add-item! id "v3")
    (rooms/remove-item! id "v2")
    (is (= [["v1" 0] ["v3" 1]] (positions id)))))

(deftest reorder-rewrites-positions
  (doseq [v ["v1" "v2" "v3"]] (video! v))
  (let [id (:id (rooms/create! "v1"))]
    (rooms/add-item! id "v2")
    (rooms/add-item! id "v3")
    (rooms/reorder! id ["v3" "v1" "v2"])
    (is (= [["v3" 0] ["v1" 1] ["v2" 2]] (positions id)))
    (is (= :bad-request (ex-type #(rooms/reorder! id ["v3" "v1"]))))
    (is (= :bad-request (ex-type #(rooms/reorder! id ["v3" "v1" "v1"]))))
    (is (= :bad-request (ex-type #(rooms/reorder! id ["v3" "v1" "v2" "nope"]))))
    (is (= [["v3" 0] ["v1" 1] ["v2" 2]] (positions id)))))

(deftest set-current-requires-a-queued-video
  (doseq [v ["v1" "v2" "v3"]] (video! v))
  (let [id (:id (rooms/create! "v1"))]
    (rooms/add-item! id "v2")
    (is (= :bad-request (ex-type #(rooms/set-current! id "v3"))))
    (is (= "v1" (:video-id (rooms/get-by-id id))))
    (rooms/set-current! id "v2")
    (is (= "v2" (:video-id (rooms/get-by-id id))))))

(deftest set-title-blank-clears-it
  (video! "v1" :title "S01E01")
  (let [id (:id (rooms/create! "v1" :title "Season 1"))]
    (rooms/set-title! id "Night 2")
    (is (= "Night 2" (:title (first (rooms/list-all)))))
    (rooms/set-title! id "   ")
    (is (nil? (:title (rooms/get-by-id id))))
    (is (= "S01E01" (:title (first (rooms/list-all)))))))

(deftest deleting-a-queued-video-is-refused
  (doseq [v ["v1" "v2"]] (video! v))
  (let [id (:id (rooms/create! "v1" :title "Season 1"))]
    (rooms/add-item! id "v2")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Season 1" (videos/delete! "v2")))
    (is (= :conflict (ex-type #(videos/delete! "v2"))))
    (is (some? (videos/get-by-id "v2")))
    (is (some? (rooms/get-by-id id)))
    (is (= [["v1" 0] ["v2" 1]] (positions id)))))

(deftest rooms-using-names-the-rooms
  (doseq [v ["v1" "v2"]] (video! v :title (str "Clip " v)))
  (let [a (:id (rooms/create! "v1" :title "A"))
        b (:id (rooms/create! "v2" :title "B"))]
    (rooms/add-item! a "v2")
    (is (= #{"A" "B"} (set (rooms/rooms-using "v2"))))
    (is (= ["A"] (rooms/rooms-using "v1")))
    (is (= [] (rooms/rooms-using "nope")))
    (rooms/delete! b)
    (is (= ["A"] (rooms/rooms-using "v2")))))

(deftest deleting-a-room-cascades-its-items
  (video! "v1")
  (let [id (:id (rooms/create! "v1"))]
    (rooms/delete! id)
    (is (empty? (rooms/queue id)))
    (is (empty? (jdbc/execute! (db/db) (sql/format {:select [:*] :from [:room-items]}))))))

(defn- tmp-file [content]
  (let [f (java.io.File/createTempFile "goob-upload" ".tmp")]
    (spit f content)
    f))

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
