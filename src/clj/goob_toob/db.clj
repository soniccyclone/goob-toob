(ns goob-toob.db
  "SQLite datasource + a tiny SQL-file migration runner."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce ds (atom nil))

(defn- migration-files []
  (->> (io/file "resources/migrations")
       (.listFiles)
       (filter #(.endsWith (.getName ^java.io.File %) ".sql"))
       (sort-by #(.getName ^java.io.File %))))

(defn- migrate! [db]
  (jdbc/execute! db
    ["CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT (datetime('now')))"])
  (let [applied (->> (jdbc/execute! db (sql/format {:select [:name] :from [:schema_migrations]}))
                     (map :schema_migrations/name) set)]
    (doseq [f (migration-files)
            :let [name (.getName ^java.io.File f)]
            :when (not (applied name))]
      (let [ddl (slurp f)]
        (jdbc/with-transaction [tx db]
          (doseq [stmt (->> (str/split ddl #";\s*\n")
                            (map str/trimr)
                            (remove str/blank?))]
            (jdbc/execute! tx [stmt]))
          (jdbc/execute! tx (sql/format {:insert-into :schema_migrations
                                         :values [{:name name}]}))
          (println "migrated" name))))))

(defn init! [db-path]
  (io/make-parents db-path)
  (let [db (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path})]
    (jdbc/execute! db ["PRAGMA journal_mode=WAL"])
    (jdbc/execute! db ["PRAGMA foreign_keys=ON"])
    (migrate! db)
    (reset! ds db)))

(defn db [] @ds)
