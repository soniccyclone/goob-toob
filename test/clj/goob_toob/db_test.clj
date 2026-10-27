(ns goob-toob.db-test
  "Migrations must be found on the CLASSPATH, not the filesystem: the packaged
  desktop app runs java from a CWD with no resources/ dir, and a filesystem
  lookup there silently migrates zero tables (the v0.1.0 Windows crash)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [goob-toob.db :as db]
            [goob-toob.test-util :refer [tmp-dir]]))

(deftest migrations-are-on-the-classpath
  (doseq [m db/migrations]
    (is (io/resource (str "migrations/" m))
        (str m " must be packaged on the classpath"))))

(deftest init-creates-the-schema
  (let [dir  (java.nio.file.Files/createTempDirectory
               "goob-db-test" (make-array java.nio.file.attribute.FileAttribute 0))
        path (str dir "/goob-toob.db")]
    (db/init! path)
    (let [tables (->> (jdbc/execute! (db/db)
                          (sql/format {:select [:name] :from [:sqlite_master]
                                       :where [:= :type "table"]}))
                      (mapcat vals) (map name) set)]
      (is (every? tables ["videos" "rooms" "room_items" "sessions" "settings" "schema_migrations"])))))

(deftest init-is-idempotent
  (let [path (str (tmp-dir) "/goob-toob.db")]
    (db/init! path)
    (is (= (count db/migrations)
           (count (jdbc/execute! (db/db) (sql/format {:select [:name] :from [:schema_migrations]})))))
    (is (= "" (with-out-str (db/init! path))))))

(deftest a-listed-migration-missing-from-the-classpath-fails-fast
  (with-redefs [db/migrations ["999-nope.sql"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"999-nope\.sql"
                          (db/init! (str (tmp-dir) "/goob-toob.db"))))))

;; ---- foreign keys ----------------------------------------------------------
;; The pragma is per connection and next.jdbc opens one per execute!, so a
;; standalone PRAGMA after init! only ever covered the connection it ran on.

(defn- fk-pragma []
  (-> (jdbc/execute-one! (db/db) ["PRAGMA foreign_keys"]) vals first))

(deftest foreign-keys-are-enforced-on-every-connection
  (db/init! (str (tmp-dir) "/goob-toob.db"))
  ;; two execute!s = two fresh connections; both must read 1
  (is (= 1 (fk-pragma)))
  (is (= 1 (fk-pragma)))
  (is (thrown? Exception
               (jdbc/execute! (db/db)
                 (sql/format {:insert-into :rooms
                              :values [{:id "r" :token "t" :video-id "no-such-video"}]})))))

(defn- pre-003-db!
  "A database as 001+002 left it, with a video and a room on it. Returns
  the path; the caller re-runs init! on it with the full migration list."
  [room-video-id]
  (let [path (str (tmp-dir) "/goob-toob.db")]
    (with-redefs [db/migrations ["001-init.sql" "002-room-control.sql"]]
      (db/init! path))
    (jdbc/execute! (db/db)
      (sql/format {:insert-into :videos
                   :values [{:id "v" :title "Clip" :status "ready"}]}))
    ;; the FK-off era: rows went in without the reference being checked
    (with-open [conn (jdbc/get-connection (db/db))]
      (jdbc/execute! conn ["PRAGMA foreign_keys=OFF"])
      (jdbc/execute! conn
        (sql/format {:insert-into :rooms
                     :values [{:id "r" :token "tok" :video-id room-video-id}]})))
    path))

(deftest migration-003-turns-existing-rooms-into-queues
  (let [path (pre-003-db! "v")]
    (db/init! path)
    (is (= [{:room_items/room_id "r" :room_items/video_id "v" :room_items/position 0}]
           (jdbc/execute! (db/db) (sql/format {:select [:room-id :video-id :position]
                                               :from [:room-items]}))))
    (is (= [{:rooms/title nil}]
           (jdbc/execute! (db/db) (sql/format {:select [:title] :from [:rooms]})))
        "rooms.title exists and migrated rooms have none")))

(deftest migration-003-drops-orphan-rooms
  (let [path (pre-003-db! "gone")]
    (db/init! path)
    (is (empty? (jdbc/execute! (db/db) (sql/format {:select [:id] :from [:rooms]}))))
    (is (empty? (jdbc/execute! (db/db) (sql/format {:select [:*] :from [:room-items]}))))))

;; ---- 004: the pointer goes nullable ------------------------------------------
;; SQLite cannot drop NOT NULL from a column, so 004 rebuilds rooms. The
;; rebuild must keep every row and column, keep room_items pointing at the
;; rebuilt table, and leave foreign_key_check empty.

(defn- pre-004-db!
  "A database as 001..003 left it: a video, a room on it (titled, with
  co-host control on) and its single queue item. Returns the path."
  []
  (let [path (str (tmp-dir) "/goob-toob.db")]
    (with-redefs [db/migrations ["001-init.sql" "002-room-control.sql" "003-room-items.sql"]]
      (db/init! path))
    (jdbc/execute! (db/db)
      (sql/format {:insert-into :videos
                   :values [{:id "v" :title "Clip" :status "ready"}]}))
    (jdbc/execute! (db/db)
      (sql/format {:insert-into :rooms
                   :values [{:id "r" :token "tok" :video-id "v" :allow-control 1 :title "Night"}]}))
    (jdbc/execute! (db/db)
      (sql/format {:insert-into :room-items
                   :values [{:room-id "r" :video-id "v" :position 0}]}))
    path))

(defn- room-columns
  "rooms' column name -> notnull flag (1 = NOT NULL)."
  []
  (->> (jdbc/execute! (db/db) ["PRAGMA table_info(rooms)"]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (map (juxt :name :notnull))
       (into {})))

(deftest migration-004-makes-the-room-pointer-nullable
  (let [path (pre-004-db!)]
    (is (= 1 (get (room-columns) "video_id")) "before 004 the pointer is NOT NULL")
    (is (thrown? Exception
                 (jdbc/execute! (db/db)
                   (sql/format {:update :rooms :set {:video-id nil} :where [:= :id "r"]}))))
    (db/init! path)
    (is (= 0 (get (room-columns) "video_id")))
    (is (= {"id" 0 "token" 1 "video_id" 0 "created_at" 1 "allow_control" 1 "title" 0}
           (room-columns))
        "every column survives the rebuild with its constraint")
    (jdbc/execute! (db/db)
      (sql/format {:update :rooms :set {:video-id nil} :where [:= :id "r"]}))
    (is (= [{:rooms/video_id nil}]
           (jdbc/execute! (db/db) (sql/format {:select [:video-id] :from [:rooms]}))))))

(deftest migration-004-keeps-rows-items-and-foreign-keys
  (let [path (pre-004-db!)]
    (db/init! path)
    (is (= [{:rooms/id "r" :rooms/token "tok" :rooms/video_id "v"
             :rooms/allow_control 1 :rooms/title "Night"}]
           (jdbc/execute! (db/db)
             (sql/format {:select [:id :token :video-id :allow-control :title] :from [:rooms]}))))
    (is (= [{:room_items/room_id "r" :room_items/video_id "v" :room_items/position 0}]
           (jdbc/execute! (db/db) (sql/format {:select [:room-id :video-id :position]
                                               :from [:room-items]}))))
    (is (empty? (jdbc/execute! (db/db) ["PRAGMA foreign_key_check"])))
    ;; the rebuilt table is still what room_items references: a room's
    ;; items cascade when it goes, and a bogus pointer is still refused
    (is (thrown? Exception
                 (jdbc/execute! (db/db)
                   (sql/format {:update :rooms :set {:video-id "nope"} :where [:= :id "r"]}))))
    (jdbc/execute! (db/db) (sql/format {:delete-from :rooms :where [:= :id "r"]}))
    (is (empty? (jdbc/execute! (db/db) (sql/format {:select [:*] :from [:room-items]}))))))

;; ---- 005: sessions -----------------------------------------------------------

(deftest migration-005-adds-the-sessions-table
  (let [path (str (tmp-dir) "/goob-toob.db")]
    (with-redefs [db/migrations (vec (remove #{"005-sessions.sql"} db/migrations))]
      (db/init! path))
    (is (thrown? Exception
                 (jdbc/execute! (db/db) (sql/format {:select [:*] :from [:sessions]})))
        "no sessions table before 005")
    (db/init! path)
    ;; [notnull pk]: a TEXT PRIMARY KEY does not read as NOT NULL in SQLite
    (is (= {"id" [0 1] "data" [1 0] "updated_at" [1 0]}
           (->> (jdbc/execute! (db/db) ["PRAGMA table_info(sessions)"]
                               {:builder-fn rs/as-unqualified-lower-maps})
                (map (juxt :name (juxt :notnull :pk)))
                (into {}))))
    (jdbc/execute! (db/db)
      (sql/format {:insert-into :sessions
                   :values [{:id "k" :data "{:admin true}" :updated-at 1}]}))
    (is (thrown? Exception
                 (jdbc/execute! (db/db)
                   (sql/format {:insert-into :sessions
                                :values [{:id "k" :data "{}" :updated-at 2}]})))
        "id is the primary key")))
