(ns goob-toob.repos.room
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
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
      ["INSERT INTO rooms (id, token, video_id) VALUES (?, ?, ?)" id token video-id])
    {:id id :token token :video-id video-id}))

(defn get-by-token [token]
  (some-> (jdbc/execute-one! (db/db)
              ["SELECT * FROM rooms WHERE token = ?" token]
              {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})))

(defn get-by-id [id]
  (some-> (jdbc/execute-one! (db/db)
              ["SELECT * FROM rooms WHERE id = ?" id]
              {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})))

(defn list-all []
  (->> (jdbc/execute! (db/db)
          ["SELECT r.id, r.token, r.video_id, v.title, v.status
             FROM rooms r JOIN videos v ON v.id = r.video_id
            ORDER BY r.created_at DESC"]
          {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})))

(defn delete! [id]
  (jdbc/execute! (db/db) ["DELETE FROM rooms WHERE id = ?" id]))
