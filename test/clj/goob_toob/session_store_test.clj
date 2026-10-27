(ns goob-toob.session-store-test
  "Sessions live in the database (#71): a login must survive the process,
  and a row older than max-age-days must not."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [ring.middleware.session.store :as store]
            [goob-toob.config :as config]
            [goob-toob.db :as db]
            [goob-toob.session-store :as ss]
            [goob-toob.test-util :refer [with-tmp]]))

(use-fixtures :each with-tmp)

(def ^:private day-ms 86400000)

(defn- rows []
  (jdbc/execute! (db/db) (sql/format {:select [:id :data :updated-at] :from [:sessions]
                                      :order-by [:id]})))

(deftest write-then-read-round-trips
  (let [s   (ss/sql-store db/db)
        key (store/write-session s "k1" {:admin true})]
    (is (= "k1" key) "an existing key is kept, or the cookie would change on every write")
    (is (= {:admin true} (store/read-session s "k1")))
    (store/write-session s "k1" {})
    (is (= {} (store/read-session s "k1")) "a rewrite replaces the data")
    (is (= 1 (count (rows))) "...in place")))

(deftest a-nil-key-gets-a-fresh-one
  (let [s  (ss/sql-store db/db)
        k1 (store/write-session s nil {:admin true})
        k2 (store/write-session s nil {:admin true})]
    (is (string? k1))
    (is (<= 32 (count k1)))
    (is (not= k1 k2))
    (is (= {:admin true} (store/read-session s k1)))))

(deftest unknown-and-nil-keys-read-as-nothing
  (let [s (ss/sql-store db/db)]
    (is (nil? (store/read-session s "nope")))
    (is (nil? (store/read-session s nil)))))

(deftest delete-forgets-the-session
  (let [s (ss/sql-store db/db)]
    (store/write-session s "k1" {:admin true})
    (store/delete-session s "k1")
    (is (nil? (store/read-session s "k1")))
    (is (empty? (rows)))
    (is (nil? (store/delete-session s "k1")) "deleting twice is fine")))

(defn- aged! [id days-ago]
  (jdbc/execute! (db/db)
    (sql/format {:insert-into :sessions
                 :values [{:id id :data (pr-str {:admin true})
                           :updated-at (- (System/currentTimeMillis) (* days-ago day-ms))}]})))

(deftest prune-drops-rows-older-than-max-age
  (aged! "old" (inc ss/max-age-days))
  (aged! "young" (dec ss/max-age-days))
  (ss/prune! (db/db))
  (is (= ["young"] (map :sessions/id (rows)))))

(deftest a-write-prunes-expired-rows
  (let [s (ss/sql-store db/db)]
    (aged! "old" (inc ss/max-age-days))
    (aged! "young" (dec ss/max-age-days))
    (store/write-session s "new" {:admin true})
    (is (= ["new" "young"] (map :sessions/id (rows))))
    (is (nil? (store/read-session s "old")))
    (is (= {:admin true} (store/read-session s "young")))))

(deftest sessions-survive-a-fresh-datasource
  ;; db/init! on the same path is what a restart does: a new datasource,
  ;; migrations already applied, the rows still there
  (let [path (str (config/storage-dir) "/test.db")
        s1   (ss/sql-store db/db)]
    (store/write-session s1 "k1" {:admin true})
    (db/init! path)
    (let [s2 (ss/sql-store db/db)]
      (is (= {:admin true} (store/read-session s2 "k1"))))))
