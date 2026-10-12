(ns goob-toob.repos.room
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [goob-toob.db :as db]
            [goob-toob.repos.video :as videos]))

(defn- random-token []
  (let [b (byte-array 24)]
    (.nextBytes (java.security.SecureRandom.) b)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) b)))

(defn- random-id [] (str (java.util.UUID/randomUUID)))

(defn create! [video-id]
  (when-not (videos/get-by-id video-id)
    (throw (ex-info "no such video" {:video-id video-id})))
  (let [id    (random-id)
        token (random-token)]
    (jdbc/execute! (db/db)
      (sql/format {:insert-into :rooms :values [{:id id :token token :video-id video-id}]}))
    {:id id :token token :video-id video-id}))

(defn get-by-token [token]
  (some-> (jdbc/execute-one! (db/db)
              (sql/format {:select :* :from [:rooms] :where [:= :token token]})
              {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})))

(defn get-by-id [id]
  (some-> (jdbc/execute-one! (db/db)
              (sql/format {:select :* :from [:rooms] :where [:= :id id]})
              {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})))

(defn list-all []
  (->> (jdbc/execute! (db/db)
          (sql/format {:select [[:r/id] [:r/token] [:r/video-id] [:r/allow-control]
                                [:v/title] [:v/status]]
                       :from [[:rooms :r]]
                       :join [[:videos :v] [:= :v.id :r.video-id]]
                       :order-by [[:r/created-at :desc]]})
          {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})))

(defn set-control! [id allow?]
  (jdbc/execute! (db/db)
    (sql/format {:update :rooms
                 :set {:allow-control (if allow? 1 0)}
                 :where [:= :id id]})))

(defn delete! [id]
  (jdbc/execute! (db/db) (sql/format {:delete-from :rooms :where [:= :id id]})))
