(ns goob-toob.db-test
  "Migrations must be found on the CLASSPATH, not the filesystem: the packaged
  desktop app runs java from a CWD with no resources/ dir, and a filesystem
  lookup there silently migrates zero tables (the v0.1.0 Windows crash)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
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
      (is (every? tables ["videos" "rooms" "room_items" "schema_migrations"])))))

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
