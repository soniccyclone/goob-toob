(ns goob-toob.e2e.api
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

(defn e2e-dir [^js full-config]
  (path/dirname (path/dirname (.-configFile full-config))))

(defn fixture-path [dir] (path/join dir ".fixture.json"))

(defn fixture [^js info]
  (-> (fs/readFileSync (fixture-path (e2e-dir (.-config info))) "utf8")
      js/JSON.parse
      (js->clj :keywordize-keys true)))

(defn- parse [^js res]
  (-> (.text res)
      (.then (fn [txt]
               {:status (.-status res)
                :headers (.-headers res)
                :data (try (js->clj (js/JSON.parse txt) :keywordize-keys true)
                           (catch :default _ txt))}))))

(defn req
  ([base cookie method path] (req base cookie method path nil))
  ([base cookie method path body]
   (-> (js/fetch (str base path)
                 (clj->js (cond-> {:method method :headers (cond-> {}
                                                              cookie (assoc "Cookie" cookie)
                                                              body (assoc "Content-Type" "application/json"))}
                            body (assoc :body (js/JSON.stringify (clj->js body))))))
       (.then parse))))

(defn login [base password]
  (-> (req base nil "POST" "/api/login" {:password password})
      (.then (fn [{:keys [status ^js headers] :as r}]
               (when-not (= 200 status)
                 (throw (js/Error. (str "login failed: " (pr-str (dissoc r :headers))))))
               (let [c (some #(re-find #"^ring-session=[^;]+" %) (array-seq (.getSetCookie headers)))]
                 (or c (throw (js/Error. "login set no ring-session cookie"))))))))

(defn upload [base cookie file]
  (let [fd (js/FormData.)]
    (.append fd "file" (js/Blob. #js [(fs/readFileSync file)]) (path/basename file))
    (-> (js/fetch (str base "/api/admin/upload")
                  #js {:method "POST" :headers #js {"Cookie" cookie} :body fd})
        (.then parse))))

(defn videos [base cookie]
  (.then (req base cookie "GET" "/api/admin/videos") #(:data %)))

(defn make-room
  "A room on video-id (its first and current item); opts: :title."
  ([base cookie video-id] (make-room base cookie video-id {}))
  ([base cookie video-id {:keys [title]}]
   (-> (req base cookie "POST" "/api/admin/rooms" (cond-> {:video-id video-id} title (assoc :title title)))
       (.then (fn [{:keys [status data]}]
                (when-not (= 201 status)
                  (throw (js/Error. (str "make-room failed: " (pr-str data)))))
                (-> (req base cookie "GET" "/api/admin/rooms")
                    (.then (fn [{rooms :data}]
                             (let [row (first (filter #(= (:token data) (:token %)) rooms))]
                               {:token (:token data) :id (:id row)})))))))))

(defn room
  "The admin row for room-id: :title :video-id (the persisted current
  item) :queue and the rest."
  [base cookie room-id]
  (.then (req base cookie "GET" "/api/admin/rooms")
         (fn [{rooms :data}] (first (filter #(= room-id (:id %)) rooms)))))

;; the queue editor's calls, as the admin console makes them; each resolves
;; to {:status :data} with the queue in :data on success
(defn add-item! [base cookie room-id video-id]
  (req base cookie "POST" (str "/api/admin/rooms/" room-id "/items") {:video-id video-id}))

(defn remove-item! [base cookie room-id video-id]
  (req base cookie "DELETE" (str "/api/admin/rooms/" room-id "/items/" video-id)))

(defn reorder! [base cookie room-id order]
  (req base cookie "PUT" (str "/api/admin/rooms/" room-id "/items") {:order order}))

(defn set-control! [base cookie room-id allow?]
  (req base cookie "PATCH" (str "/api/admin/rooms/" room-id) {:allow-control allow?}))

(defn live [base cookie room-id]
  (.then (req base cookie "GET" (str "/api/admin/rooms/" room-id "/live")) #(:data %)))

(defn client [live-view nick]
  (first (filter #(= nick (:nick %)) (:clients live-view))))

;; #57 telemetry: the admin listing, a file as text and the analysis of a
;; room's day
(defn telemetry [base cookie]
  (.then (req base cookie "GET" "/api/admin/telemetry") #(:data %)))

(defn telemetry-file
  "The file's text (parse would read a one-line jsonl as JSON)."
  [base cookie day room-id name]
  (-> (js/fetch (str base "/api/admin/telemetry/" day "/" room-id "/" name)
                #js {:headers #js {"Cookie" cookie}})
      (.then (fn [^js res] (.text res)))))

(defn telemetry-analysis [base cookie day room-id]
  (req base cookie "GET" (str "/api/admin/telemetry/" day "/" room-id "/analysis")))
