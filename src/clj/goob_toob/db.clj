(ns goob-toob.db
  "SQLite datasource + a tiny SQL-file migration runner."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce ds (atom nil))

;; Project DSL extension: [:now] is SQLite's current timestamp. Registered
;; here (the ns that owns the dialect) so query code never needs raw SQL —
;; if the DSL lacks a form, extend it here, don't escape it.
(sql/register-fn! :now (fn [_ _] ["datetime('now')"]))

(def migrations
  "Migration resources (under migrations/ on the classpath), applied in order.
  Add a .sql file under resources/migrations/ and list its name here. Loaded
  via the classpath on purpose: the packaged desktop app runs from a CWD that
  has no resources/ dir, and a filesystem path there silently migrates zero
  tables (the v0.1.0 Windows crash)."
  ["001-init.sql" "002-room-control.sql"])

(defn- migrate! [db]
  (jdbc/execute! db
    ["CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT (datetime('now')))"])
  (let [applied (->> (jdbc/execute! db (sql/format {:select [:name] :from [:schema_migrations]}))
                     (map :schema_migrations/name) set)]
    (doseq [name migrations
            :when (not (applied name))]
      (let [ddl (some-> (io/resource (str "migrations/" name)) slurp)
            _   (when (nil? ddl)
                  (throw (ex-info (str "missing migration on classpath: migrations/" name) {})))]
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
