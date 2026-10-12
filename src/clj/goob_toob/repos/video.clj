(ns goob-toob.repos.video
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.data.json :as json]
            [goob-toob.db :as db]))

(defn- row->video [row]
  (update row :ladder #(some-> % json/read-str)))

(defn insert! [video]
  (let [{:keys [id title original-path]} video]
    (jdbc/execute! (db/db)
      (sql/format {:insert-into :videos
                   :values [{:id id :title title :original-path original-path
                             :dash-dir (str "dash/" id) :status "uploaded"}]}))
    video))

(defn get-by-id [id]
  (some-> (jdbc/execute-one! (db/db)
              (sql/format {:select :* :from [:videos] :where [:= :id id]})
              {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})
          row->video))

(defn list-all []
  (->> (jdbc/execute! (db/db)
          (sql/format {:select :* :from [:videos] :order-by [[:created-at :desc]]})
          {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})
       (map row->video)))

(defn list-ready []
  (->> (jdbc/execute! (db/db)
          (sql/format {:select :* :from [:videos]
                       :where [:= :status "ready"]
                       :order-by [[:created-at :desc]]})
          {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})
       (map row->video)))

(defn set-status! [id status & {:keys [progress error duration]}]
  (jdbc/execute! (db/db)
    (sql/format {:update :videos
                 :set {:status status
                       :progress [:coalesce progress :progress]
                       :error error
                       :duration [:coalesce duration :duration]
                       :updated-at [:now]}
                 :where [:= :id id]}))
  status)

(defn set-progress! [id progress]
  (jdbc/execute! (db/db)
    (sql/format {:update :videos
                 :set {:progress progress
                       :updated-at [:now]}
                 :where [:= :id id]})))

(defn set-duration! [id duration]
  (jdbc/execute! (db/db)
    (sql/format {:update :videos :set {:duration duration} :where [:= :id id]})))

(defn delete! [id]
  (jdbc/execute! (db/db) (sql/format {:delete-from :rooms :where [:= :video-id id]}))
  (jdbc/execute! (db/db) (sql/format {:delete-from :videos :where [:= :id id]})))

(defn requeue-stuck []
  ;; anything left mid-flight by a crash goes back on the queue
  (->> (jdbc/execute! (db/db)
          (sql/format {:select [:id] :from [:videos]
                       :where [:in :status ["uploaded" "transcoding"]]})
          {:return-keys true :builder-fn rs/as-unqualified-lower-maps})
       (map :id)))
