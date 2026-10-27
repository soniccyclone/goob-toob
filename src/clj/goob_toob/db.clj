(ns goob-toob.db
  "SQLite datasource + a tiny SQL-file migration runner."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [goob-toob.log :as log]))

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
  ["001-init.sql" "002-room-control.sql" "003-room-items.sql" "004-room-empty.sql"
   "005-sessions.sql" "006-settings.sql"])

(defn- migrate!
  "Runs the pending migrations on ONE connection with foreign keys off, as
  the SQLite docs prescribe for schema changes (the pragma is per connection
  and a no-op inside a transaction, so it has to be set here, before BEGIN).
  Afterwards foreign_key_check must come back empty: a violation is a bug in
  a migration, not a condition to limp through."
  [db]
  (with-open [conn (jdbc/get-connection db)]
    (jdbc/execute! conn ["PRAGMA foreign_keys=OFF"])
    (jdbc/execute! conn
      ["CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT (datetime('now')))"])
    (let [applied (->> (jdbc/execute! conn (sql/format {:select [:name] :from [:schema_migrations]}))
                       (map :schema_migrations/name) set)]
      (doseq [name migrations
              :when (not (applied name))]
        (let [ddl (some-> (io/resource (str "migrations/" name)) slurp)
              _   (when (nil? ddl)
                    (throw (ex-info (str "missing migration on classpath: migrations/" name) {})))]
          (jdbc/with-transaction [tx conn]
            (doseq [stmt (->> (str/split ddl #";\s*\n")
                              (map str/trimr)
                              (remove str/blank?))]
              (jdbc/execute! tx [stmt]))
            (jdbc/execute! tx (sql/format {:insert-into :schema_migrations
                                           :values [{:name name}]}))
            (log/info "migrated" name)))))
    (when-let [bad (seq (jdbc/execute! conn ["PRAGMA foreign_key_check"]))]
      (throw (ex-info "foreign key violations after migration" {:rows (vec bad)})))))

(defn init! [db-path]
  (io/make-parents db-path)
  ;; :foreign_keys rides along as a driver property, so every connection
  ;; next.jdbc opens (one per execute!) has the pragma on, not just the
  ;; first one. migrate! turns it off again on its own connection.
  (let [db (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path :foreign_keys true})]
    (jdbc/execute! db ["PRAGMA journal_mode=WAL"])
    (migrate! db)
    (reset! ds db)))

(defn db [] @ds)
