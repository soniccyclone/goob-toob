(ns goob-toob.repos.room
  "Rooms and their queues. A room is an ordered queue of videos
  (room_items) with a current pointer (rooms.video_id); the queue is never
  empty because the pointer is NOT NULL.

  Looks videos up with its own query rather than repos.video so that
  repos.video can require this ns for rooms-using (video deletion refuses
  a queued video) without a cycle."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.string :as str]
            [goob-toob.db :as db]))

(def ^:private kebab {:return-keys true :builder-fn rs/as-unqualified-kebab-maps})

(defn- random-token []
  (let [b (byte-array 24)]
    (.nextBytes (java.security.SecureRandom.) b)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) b)))

(defn- random-id [] (str (java.util.UUID/randomUUID)))

(defn- video-row
  "The video's id and title, or throws (:type :bad-request) when there is
  no such video. db is a datasource or an open transaction."
  [db video-id]
  (or (jdbc/execute-one! db
        (sql/format {:select [:id :title] :from [:videos] :where [:= :id video-id]})
        kebab)
      (throw (ex-info "no such video" {:type :bad-request :video-id video-id}))))

(defn- queue-ids
  "The queued video ids in position order."
  [db room-id]
  (->> (jdbc/execute! db
         (sql/format {:select [:video-id] :from [:room-items]
                      :where [:= :room-id room-id]
                      :order-by [:position]})
         kebab)
       (mapv :video-id)))

(defn- renumber!
  "Rewrites positions so that ids sit at 0, 1, 2 ... in that order."
  [tx room-id ids]
  (doseq [[i video-id] (map-indexed vector ids)]
    (jdbc/execute! tx
      (sql/format {:update :room-items
                   :set {:position i}
                   :where [:and [:= :room-id room-id] [:= :video-id video-id]]}))))

(defn create!
  "A room on video-id, which becomes its first (and current) item. The
  title defaults to the video's. Throws when the video does not exist."
  [video-id & {:keys [title]}]
  (let [id    (random-id)
        token (random-token)
        title (jdbc/with-transaction [tx (db/db)]
                (let [v     (video-row tx video-id)
                      title (or (some-> title str/trim not-empty) (:title v))]
                  (jdbc/execute! tx
                    (sql/format {:insert-into :rooms
                                 :values [{:id id :token token :video-id video-id :title title}]}))
                  (jdbc/execute! tx
                    (sql/format {:insert-into :room-items
                                 :values [{:room-id id :video-id video-id :position 0}]}))
                  title))]
    {:id id :token token :video-id video-id :title title}))

(defn get-by-token [token]
  (some-> (jdbc/execute-one! (db/db)
              (sql/format {:select :* :from [:rooms] :where [:= :token token]})
              kebab)))

(defn get-by-id [id]
  (some-> (jdbc/execute-one! (db/db)
              (sql/format {:select :* :from [:rooms] :where [:= :id id]})
              kebab)))

(defn queue
  "The room's items in order: {:video-id :title :duration :status :position}."
  [room-id]
  (->> (jdbc/execute! (db/db)
         (sql/format {:select [[:ri/video-id] [:v/title] [:v/duration] [:v/status] [:ri/position]]
                      :from [[:room-items :ri]]
                      :join [[:videos :v] [:= :v.id :ri.video-id]]
                      :where [:= :ri.room-id room-id]
                      :order-by [[:ri/position]]})
         kebab)
       vec))

(defn add-item!
  "Appends video-id to the room's queue. Throws :bad-request when there is
  no such video and :conflict when it is already queued."
  [room-id video-id]
  (jdbc/with-transaction [tx (db/db)]
    (video-row tx video-id)
    (let [ids (queue-ids tx room-id)]
      (when (some #{video-id} ids)
        (throw (ex-info "already queued" {:type :conflict :room-id room-id :video-id video-id})))
      (jdbc/execute! tx
        (sql/format {:insert-into :room-items
                     :values [{:room-id room-id :video-id video-id :position (count ids)}]})))))

(defn remove-item!
  "Drops video-id from the room's queue and closes the gap in the
  positions. Removing a video that is not queued is a no-op."
  [room-id video-id]
  (jdbc/with-transaction [tx (db/db)]
    ;; Open decision (plan: "the current item cannot be removed; skip first").
    ;; Nathan has questioned refusing the host here; this branch is the whole
    ;; refusal, so dropping it is a matter of deleting the `when`.
    (when (= video-id (:video-id (jdbc/execute-one! tx
                                   (sql/format {:select [:video-id] :from [:rooms]
                                                :where [:= :id room-id]})
                                   kebab)))
      (throw (ex-info "that's the current video; skip first"
                      {:type :conflict :room-id room-id :video-id video-id})))
    (jdbc/execute! tx
      (sql/format {:delete-from :room-items
                   :where [:and [:= :room-id room-id] [:= :video-id video-id]]}))
    (renumber! tx room-id (queue-ids tx room-id))))

(defn reorder!
  "Rewrites the queue's positions to follow order, which must list every
  queued video id exactly once (else :bad-request)."
  [room-id order]
  (jdbc/with-transaction [tx (db/db)]
    (let [ids (queue-ids tx room-id)]
      (when-not (and (= (count order) (count ids))
                     (= (set order) (set ids)))
        (throw (ex-info "order must list every queued video exactly once"
                        {:type :bad-request :room-id room-id :order order :queue ids})))
      (renumber! tx room-id order))))

(defn set-current!
  "Points the room at video-id, which must be in its queue (else
  :bad-request). Playback position lives in memory, not here."
  [room-id video-id]
  (jdbc/with-transaction [tx (db/db)]
    (when-not (some #{video-id} (queue-ids tx room-id))
      (throw (ex-info "not in the queue" {:type :bad-request :room-id room-id :video-id video-id})))
    (jdbc/execute! tx
      (sql/format {:update :rooms :set {:video-id video-id} :where [:= :id room-id]}))))

(defn set-title!
  "Renames the room; a blank title clears it (list-all then shows the
  current video's)."
  [room-id title]
  (jdbc/execute! (db/db)
    (sql/format {:update :rooms
                 :set {:title (some-> title str/trim not-empty)}
                 :where [:= :id room-id]})))

(defn list-all
  "Every room, newest first: :id :token :allow-control :title (the room's,
  else its current video's) :video-id :status (the current video's) :items."
  []
  (->> (jdbc/execute! (db/db)
         (sql/format {:select [[:r/id] [:r/token] [:r/video-id] [:r/allow-control]
                               [[:coalesce :r.title :v.title] :title]
                               [:v/status]
                               [{:select [[[:count :*]]] :from [[:room-items :ri]]
                                 :where [:= :ri.room-id :r.id]} :items]]
                      :from [[:rooms :r]]
                      :join [[:videos :v] [:= :v.id :r.video-id]]
                      :order-by [[:r/created-at :desc]]})
         kebab)))

(defn rooms-using
  "Titles of the rooms whose queue contains video-id."
  [video-id]
  (->> (jdbc/execute! (db/db)
         (sql/format {:select [[[:coalesce :r.title :v.title] :title]]
                      :from [[:room-items :ri]]
                      :join [[:rooms :r] [:= :r.id :ri.room-id]
                             [:videos :v] [:= :v.id :r.video-id]]
                      :where [:= :ri.video-id video-id]
                      :order-by [[:r/created-at :desc]]})
         kebab)
       (mapv :title)))

(defn set-control! [id allow?]
  (jdbc/execute! (db/db)
    (sql/format {:update :rooms
                 :set {:allow-control (if allow? 1 0)}
                 :where [:= :id id]})))

(defn delete!
  "Closes the room; its items cascade."
  [id]
  (jdbc/execute! (db/db) (sql/format {:delete-from :rooms :where [:= :id id]})))
