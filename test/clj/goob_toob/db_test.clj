(ns goob-toob.db-test
  "Migrations must be found on the CLASSPATH, not the filesystem: the packaged
  desktop app runs java from a CWD with no resources/ dir, and a filesystem
  lookup there silently migrates zero tables (the v0.1.0 Windows crash)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [goob-toob.db :as db]))

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
