(ns goob-toob.repos.video
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.data.json :as json]
            [goob-toob.db :as db]))

(defn- row->video [row]
  (update row :ladder #(some-> % json/read-str)))

(defn insert! [video]
  (let [{:keys [id title original-path]} video]
    (jdbc/execute! (db/db)
      ["INSERT INTO videos (id, title, original_path, dash_dir, status) VALUES (?, ?, ?, ?, 'uploaded')"
       id title original-path (str "dash/" id)])
    video))

(defn get-by-id [id]
  (some-> (jdbc/execute-one! (db/db)
              ["SELECT * FROM videos WHERE id = ?" id]
              {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})
          row->video))

(defn list-all []
  (->> (jdbc/execute! (db/db)
          ["SELECT * FROM videos ORDER BY created_at DESC"]
          {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})
       (map row->video)))

(defn list-ready []
  (->> (jdbc/execute! (db/db)
          ["SELECT * FROM videos WHERE status = 'ready' ORDER BY created_at DESC"]
          {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})
       (map row->video)))

(defn set-status! [id status & {:keys [progress error duration]}]
  (jdbc/execute! (db/db)
    ["UPDATE videos SET status = ?, progress = COALESCE(?, progress), error = ?, duration = COALESCE(?, duration), updated_at = datetime('now') WHERE id = ?"
     status progress error duration id])
  status)

(defn set-progress! [id progress]
  (jdbc/execute! (db/db)
    ["UPDATE videos SET progress = ?, updated_at = datetime('now') WHERE id = ?" progress id]))

(defn set-duration! [id duration]
  (jdbc/execute! (db/db)
    ["UPDATE videos SET duration = ? WHERE id = ?" duration id]))

(defn delete! [id]
  (jdbc/execute! (db/db) ["DELETE FROM rooms WHERE video_id = ?" id])
  (jdbc/execute! (db/db) ["DELETE FROM videos WHERE id = ?" id]))

(defn requeue-stuck []
  ;; anything left mid-flight by a crash goes back on the queue
  (->> (jdbc/execute! (db/db)
          ["SELECT id FROM videos WHERE status IN ('uploaded', 'transcoding')"]
          {:return-keys true :builder-fn rs/as-unqualified-lower-maps})
       (map :id)))
