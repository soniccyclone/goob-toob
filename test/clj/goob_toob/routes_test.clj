(ns goob-toob.routes-test
  "The two-port security invariant: whatever the tunnel can reach (public
  port) must have NO admin surface; the loopback admin port is always-admin."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [goob-toob.config :as config]
            [goob-toob.db :as db]
            [goob-toob.repos.room :as rooms]
            [goob-toob.repos.storage :as storage]
            [goob-toob.repos.video :as videos]
            [goob-toob.routes :as routes]
            [goob-toob.services.sync :as sync]
            [goob-toob.test-util :refer [with-tmp]]))

(use-fixtures :each with-tmp)

(defn- req [method path]
  {:request-method method :uri path :headers {} :body nil
   :query-string nil :server-name "localhost" :server-port 80
   :scheme :http :remote-addr "127.0.0.1" :protocol "HTTP/1.1"})

(defn- body [resp] (json/parse-string (:body resp) true))

(deftest public-port-has-no-admin-surface
  (let [app (routes/app {:admin? false})]
    (is (= 404 (:status (app (req :get "/api/admin/videos")))))
    (is (= 404 (:status (app (req :get "/api/admin/rooms")))))
    (is (= 404 (:status (app (req :post "/api/login")))))
    (is (= 404 (:status (app (req :post "/api/logout")))))
    (is (= 404 (:status (app (req :get "/admin")))))))

(deftest public-whoami-is-never-admin
  (let [app  (routes/app {:admin? false})
        resp (app (req :get "/api/me"))]
    (is (= 200 (:status resp)))
    (is (false? (:admin (body resp))))))

(deftest container-admin-routes-stay-session-gated
  (let [app (routes/app {:admin? true :login? true})]
    (is (= 401 (:status (app (req :get "/api/admin/videos")))))
    (is (= 200 (:status (app (req :get "/api/me")))))
    (is (false? (:admin (body (app (req :get "/api/me"))))))))

(deftest desktop-admin-port-is-always-admin
  (let [app  (routes/wrap-desktop-admin (routes/app {:admin? true}))
        resp (app (req :get "/api/admin/me"))]
    (is (= 200 (:status resp)))
    (is (true? (:admin (body resp))))
    ;; no password on the desktop admin port -> no login surface
    (is (= 404 (:status (app (req :post "/api/login")))))
    (is (= 404 (:status (app (req :post "/api/logout")))))))

(defn- json-response? [resp]
  (some-> (get-in resp [:headers "Content-Type"]) (str/includes? "json")))

(defn- call [app method path & {:keys [body cookie headers]}]
  (let [hs   (cond-> (or headers {})
               body   (assoc "content-type" "application/json")
               cookie (assoc "cookie" cookie))
        resp (app (cond-> (assoc (req method path) :headers hs)
                    body (assoc :content-type "application/json"
                                :body (java.io.ByteArrayInputStream.
                                        (.getBytes (json/generate-string body) "UTF-8")))))]
    (cond-> resp
      (and (string? (:body resp)) (json-response? resp))
      (update :body json/parse-string true))))

(defn- session-cookie [resp]
  (some-> (get-in resp [:headers "Set-Cookie"]) first (str/split #";") first))

(defn- container-app [] (routes/app {:admin? true :login? true}))

(defn- login! [app]
  (session-cookie (call app :post "/api/login" :body {:password "pw"})))

(defn- video! [id & {:keys [title status] :or {title "Clip"}}]
  (videos/insert! {:id id :title title :original-path (str "/tmp/" id ".mp4")})
  (when status (videos/set-status! id status))
  id)

(deftest wrong-password-is-401
  (let [app  (container-app)
        resp (call app :post "/api/login" :body {:password "no"})]
    (is (= 401 (:status resp)))
    (is (false? (:admin (:body (call app :get "/api/me" :cookie (session-cookie resp))))))))

(deftest five-failed-logins-per-minute-then-429
  (let [app (container-app)]
    (dotimes [_ 5]
      (is (= 401 (:status (call app :post "/api/login" :body {:password "no"})))))
    (is (= 429 (:status (call app :post "/api/login" :body {:password "pw"}))))
    (is (= 5 (count (get @routes/login-attempts "127.0.0.1"))))))

(deftest login-attempts-expire-after-a-minute
  (let [app   (container-app)
        stale (- (System/currentTimeMillis) 61000)]
    (reset! routes/login-attempts {"127.0.0.1" (vec (repeat 5 stale))})
    (is (= 200 (:status (call app :post "/api/login" :body {:password "pw"}))))
    (is (= [true] (map #(< stale %) (get @routes/login-attempts "127.0.0.1"))))))

(defn- restart!
  "What a server restart does to the app: a fresh datasource on the same
  database file and a fresh handler (with its own session store)."
  []
  (db/init! (str (config/storage-dir) "/test.db"))
  (container-app))

(deftest an-admin-login-survives-a-restart
  (let [cookie (login! (container-app))
        app    (restart!)]
    (is (string? cookie))
    (is (= 200 (:status (call app :get "/api/admin/me" :cookie cookie))))
    (is (true? (:admin (:body (call app :get "/api/me" :cookie cookie)))))
    (is (= 401 (:status (call app :get "/api/admin/me"))) "...only with the cookie")))

(deftest a-logout-survives-a-restart-too
  (let [app    (container-app)
        cookie (login! app)]
    (is (= 200 (:status (call app :post "/api/logout" :cookie cookie))))
    (let [app (restart!)]
      (is (= 401 (:status (call app :get "/api/admin/me" :cookie cookie))))
      (is (false? (:admin (:body (call app :get "/api/me" :cookie cookie))))))))

(deftest parse-range-forms
  (let [pr #(#'routes/parse-range % 1000)]
    (is (= [0 99] (pr "bytes=0-99")))
    (is (= [900 999] (pr "bytes=900-")))
    (is (= [900 999] (pr "bytes=-100")))
    (is (= [0 999] (pr "bytes=0-5000")))
    (is (nil? (pr "bytes=5-2")))
    (is (nil? (pr "junk")))
    (is (nil? (pr "bytes=-0")))))

(deftest dash-files-serve-whole-and-ranged
  (spit (io/file (storage/dash-dir "v1") "manifest.mpd") "hello world")
  (let [app   (container-app)
        whole (call app :get "/dash/v1/manifest.mpd")
        part  (call app :get "/dash/v1/manifest.mpd" :headers {"range" "bytes=6-10"})]
    (is (= 200 (:status whole)))
    (is (= "application/dash+xml" (get-in whole [:headers "Content-Type"])))
    (is (= "11" (get-in whole [:headers "Content-Length"])))
    (is (= "hello world" (slurp (:body whole))))
    (is (= 206 (:status part)))
    (is (= "world" (slurp (:body part))))
    (is (= "bytes 6-10/11" (get-in part [:headers "Content-Range"])))))

(deftest dash-files-outside-the-dir-are-404
  (storage/dash-dir "v1")
  (let [app (container-app)]
    (is (= 404 (:status (call app :get "/dash/v1/../../test.db"))))
    (is (= 404 (:status (call app :get "/dash/v1/nope.m4s"))))))

(deftest upload-without-a-file-is-400
  (is (= 400 (:status (routes/upload {:params {}})))))

(deftest delete-video-removes-rows-and-files
  (video! "v1")
  (spit (io/file (storage/dash-dir "v1") "manifest.mpd") "x")
  (let [app    (container-app)
        cookie (login! app)]
    (is (= 200 (:status (call app :delete "/api/admin/videos/v1" :cookie cookie))))
    (is (nil? (videos/get-by-id "v1")))
    (is (not (.exists (io/file (config/storage-dir) "dash" "v1"))))))

(deftest clientlog-is-rate-limited-per-token
  (reset! routes/clientlog-hits {})
  (is (every? true? (repeatedly 120 #(#'routes/clientlog-allow? "t" 0))))
  (is (false? (#'routes/clientlog-allow? "t" 0)))
  (is (true? (#'routes/clientlog-allow? "t" 60000))))

(deftest room-live-is-admin-only
  (video! "v1")
  (let [app    (container-app)
        id     (:id (rooms/create! "v1"))
        path   (str "/api/admin/rooms/" id "/live")
        _      (sync/join! id "c1" "viewer")
        cookie (login! app)
        resp   (call app :get path :cookie cookie)]
    (is (= 401 (:status (call app :get path))))
    (is (= 200 (:status resp)))
    (is (= ["viewer"] (map :nick (get-in resp [:body :clients]))))
    (is (= 404 (:status (call app :get "/api/admin/rooms/nope/live" :cookie cookie))))))

;; ---- queues -----------------------------------------------------------------

(defn- queue-ids [resp] (mapv :video-id (get-in resp [:body :queue])))

(deftest queue-endpoints-add-remove-reorder
  (video! "v1")
  (video! "v2")
  (let [app    (container-app)
        cookie (login! app)
        id     (:id (rooms/create! "v1"))
        items  (str "/api/admin/rooms/" id "/items")
        added  (call app :post items :cookie cookie :body {:video-id "v2"})]
    (is (= 201 (:status added)))
    (is (= ["v1" "v2"] (queue-ids added)))
    (is (= "v1" (get-in added [:body :video-id])))
    (is (= ["v1" "v2"]
           (->> (:body (call app :get "/api/admin/rooms" :cookie cookie))
                (filter #(= id (:id %))) first :queue (mapv :video-id))))
    (let [re (call app :put items :cookie cookie :body {:order ["v2" "v1"]})]
      (is (= 200 (:status re)))
      (is (= ["v2" "v1"] (queue-ids re)))
      (is (= [0 1] (mapv :position (get-in re [:body :queue])))))
    (let [rm (call app :delete (str items "/v2") :cookie cookie)]
      (is (= 200 (:status rm)))
      (is (= ["v1"] (queue-ids rm))))
    (is (= [["v1" 0]] (mapv (juxt :video-id :position) (rooms/queue id))))))

(deftest queue-conflicts-are-409
  (video! "v1")
  (video! "v2")
  (let [app    (container-app)
        cookie (login! app)
        id     (:id (rooms/create! "v1"))
        items  (str "/api/admin/rooms/" id "/items")
        dup    (call app :post items :cookie cookie :body {:video-id "v1"})
        bad    (call app :put items :cookie cookie :body {:order ["v1" "v2"]})
        none   (call app :post items :cookie cookie :body {:video-id "nope"})]
    (is (= 409 (:status dup)))
    (is (= "already queued" (get-in dup [:body :error])))
    (is (= 400 (:status bad)))
    (is (= 400 (:status none)))
    (is (= "no such video" (get-in none [:body :error])))
    (is (= [["v1" 0]] (mapv (juxt :video-id :position) (rooms/queue id))))))

(deftest deleting-the-current-item-skips-the-room
  (video! "v1")
  (video! "v2")
  (let [app    (container-app)
        cookie (login! app)
        id     (:id (rooms/create! "v1"))
        _      (rooms/add-item! id "v2")
        rm     (call app :delete (str "/api/admin/rooms/" id "/items/v1") :cookie cookie)]
    (is (= 200 (:status rm)))
    (is (= ["v2"] (queue-ids rm)))
    (is (= "v2" (get-in rm [:body :video-id])))
    (is (= "v2" (:video-id (rooms/get-by-id id))))
    (is (= [["v2" 0]] (mapv (juxt :video-id :position) (rooms/queue id))))))

(deftest deleting-the-only-item-empties-the-room
  (video! "v1" :title "S01E01" :status "ready")
  (video! "v2" :title "S01E02" :status "ready")
  (let [app    (container-app)
        cookie (login! app)
        id     (:id (rooms/create! "v1" :title "Season 1"))
        items  (str "/api/admin/rooms/" id "/items")
        rm     (call app :delete (str items "/v1") :cookie cookie)
        admin  (first (filter #(= id (:id %))
                              (:body (call app :get "/api/admin/rooms" :cookie cookie))))
        lobby  (:body (call app :get "/api/rooms"))]
    (is (= 200 (:status rm)))
    (is (= {:queue [] :video-id nil} (:body rm)))
    (is (some? (rooms/get-by-id id)) "the room stands")
    (is (= {:id id :title "Season 1" :video-id nil :video-title nil :status nil :items 0 :queue []}
           (select-keys admin [:id :title :video-id :video-title :status :items :queue])))
    (is (= [{:title "Season 1" :now nil :items 0}] (mapv #(dissoc % :token) lobby))
        "the lobby still lists it")
    (let [added (call app :post items :cookie cookie :body {:video-id "v2"})]
      (is (= 201 (:status added)))
      (is (= ["v2"] (queue-ids added)))
      (is (= "v2" (get-in added [:body :video-id])) "the first item of an empty room is current"))))

(deftest queue-routes-404-an-unknown-room
  (video! "v1")
  (let [app    (container-app)
        cookie (login! app)
        items  "/api/admin/rooms/nope/items"
        added  (call app :post items :cookie cookie :body {:video-id "v1"})]
    (is (= 404 (:status added)))
    (is (= "no such room" (get-in added [:body :error])))
    (is (= 404 (:status (call app :put items :cookie cookie :body {:order []}))))
    (is (= 404 (:status (call app :delete (str items "/v1") :cookie cookie))))
    (is (= 404 (:status (call app :patch "/api/admin/rooms/nope" :cookie cookie :body {:title "x"}))))))

(deftest deleting-a-queued-video-is-409
  (video! "v1" :status "ready")
  (video! "v2" :status "ready")
  (let [app    (container-app)
        cookie (login! app)
        id     (:id (rooms/create! "v1" :title "Season 1"))
        _      (rooms/add-item! id "v2")
        resp   (call app :delete "/api/admin/videos/v2" :cookie cookie)]
    (is (= 409 (:status resp)))
    (is (= "queued in: Season 1" (get-in resp [:body :error])))
    (is (some #{"v2"} (map :id (:body (call app :get "/api/admin/videos" :cookie cookie)))))
    (is (= [["v1" 0] ["v2" 1]] (mapv (juxt :video-id :position) (rooms/queue id))))))

(deftest public-rooms-show-the-current-video-and-count
  (video! "v1" :title "S01E01" :status "ready")
  (video! "v2" :title "S01E02" :status "ready")
  (video! "v3" :title "Raw")
  (let [app  (container-app)
        id   (:id (rooms/create! "v1" :title "Season 1"))
        _    (rooms/add-item! id "v2")
        _    (rooms/create! "v3")
        resp (call app :get "/api/rooms")]
    (is (= 200 (:status resp)))
    (is (= [{:title "Season 1" :now "S01E01" :items 2}]
           (mapv #(dissoc % :token) (:body resp))))
    (is (string? (:token (first (:body resp)))))))

(deftest patch-room-title
  (video! "v1" :title "S01E01")
  (let [app    (container-app)
        cookie (login! app)
        id     (:id (rooms/create! "v1"))
        path   (str "/api/admin/rooms/" id)
        title  #(->> (:body (call app :get "/api/admin/rooms" :cookie cookie))
                     (filter (fn [r] (= id (:id r)))) first :title)]
    (is (= 200 (:status (call app :patch path :cookie cookie :body {:title "Night 2"}))))
    (is (= "Night 2" (title)))
    (is (= 200 (:status (call app :patch path :cookie cookie :body {:allow-control true}))))
    (is (= "Night 2" (title)))
    (is (= 1 (:allow-control (rooms/get-by-id id))))
    (call app :patch path :cookie cookie :body {:title "  "})
    (is (= "S01E01" (title)))
    (is (= 1 (:allow-control (rooms/get-by-id id))))))
