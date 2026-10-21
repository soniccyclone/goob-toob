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
      (is (every? tables ["videos" "rooms" "schema_migrations"])))))

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
