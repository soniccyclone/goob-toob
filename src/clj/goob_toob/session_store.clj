(ns goob-toob.session-store
  "Ring sessions in the sessions table, so an admin login outlives the
  server process (#71): with the memory store a restart demoted every
  host tab to a viewer. A session is one EDN map (today only {:admin true})
  keyed by the ring-session cookie.

  Expiry: a row lives max-age-days after it was last WRITTEN, and ring
  writes only when a handler sets :session (login, logout) — a request that
  merely reads the cookie does not touch the row. So a login is good for
  30 days, then the cookie is unknown and the admin logs in again; prune!
  runs at startup and before every write."
  (:require [clojure.edn :as edn]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [ring.middleware.session.store :refer [SessionStore]]))

(def max-age-days 30)

(defn- now-ms [] (System/currentTimeMillis))

(defn prune!
  "Deletes every session last written more than max-age-days ago."
  ([db] (prune! db (now-ms)))
  ([db now]
   (jdbc/execute! db (sql/format {:delete-from :sessions
                                  :where [:< :updated-at (- now (* max-age-days 86400000))]}))
   nil))

(deftype SqlStore [db-fn]
  SessionStore
  (read-session [_ key]
    (when key
      (some-> (jdbc/execute-one! (db-fn) (sql/format {:select [:data] :from [:sessions]
                                                      :where [:= :id key]}))
              :sessions/data
              edn/read-string)))
  (write-session [_ key data]
    (let [db  (db-fn)
          key (or key (str (java.util.UUID/randomUUID)))]
      (prune! db)
      (jdbc/execute! db (sql/format {:insert-into :sessions
                                     :values [{:id key :data (pr-str data) :updated-at (now-ms)}]
                                     :on-conflict :id
                                     :do-update-set [:data :updated-at]}))
      key))
  (delete-session [_ key]
    (jdbc/execute! (db-fn) (sql/format {:delete-from :sessions :where [:= :id key]}))
    nil))

(defn sql-store
  "A store over the datasource db-fn returns when called: the datasource is
  looked up per operation, so the store can be made before db/init! and
  follows a re-init (tests simulate a restart that way)."
  [db-fn]
  (->SqlStore db-fn))
