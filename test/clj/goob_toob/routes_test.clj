(ns goob-toob.routes-test
  "The two-port security invariant: whatever the tunnel can reach (public
  port) must have NO admin surface; the loopback admin port is always-admin."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [goob-toob.config :as config]
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

(deftest login-sets-a-session-that-admin-routes-accept
  (let [app    (container-app)
        resp   (call app :post "/api/login" :body {:password "pw"})
        cookie (session-cookie resp)]
    (is (= 200 (:status resp)))
    (is (some? cookie))
    (is (true? (:admin (:body (call app :get "/api/me" :cookie cookie)))))
    (is (= 200 (:status (call app :get "/api/admin/videos" :cookie cookie))))
    (call app :post "/api/logout" :cookie cookie)
    (is (false? (:admin (:body (call app :get "/api/me" :cookie cookie)))))))

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

(deftest public-rooms-lists-only-ready-videos
  (video! "ok" :title "Ready one" :status "ready")
  (video! "wip" :title "Still uploading")
  (let [ready (rooms/create! "ok")]
    (rooms/create! "wip")
    (is (= [{:token (:token ready) :title "Ready one"}]
           (:body (call (container-app) :get "/api/rooms"))))))

(deftest create-room-returns-a-watch-url
  (video! "v1")
  (let [app    (container-app)
        cookie (login! app)
        resp   (call app :post "/api/admin/rooms" :cookie cookie :body {:video-id "v1"})
        token  (get-in resp [:body :token])]
    (is (= 201 (:status resp)))
    (is (= "v1" (:video-id (rooms/get-by-token token))))
    (is (= (str "/watch/" token) (get-in resp [:body :url])))
    (is (= 400 (:status (call app :post "/api/admin/rooms" :cookie cookie
                              :body {:video-id "nope"}))))))

(deftest update-room-flips-co-host-control
  (video! "v1")
  (let [app    (container-app)
        cookie (login! app)
        id     (:id (rooms/create! "v1"))]
    (is (= 200 (:status (call app :patch (str "/api/admin/rooms/" id) :cookie cookie
                              :body {:allow-control true}))))
    (is (= 1 (:allow-control (rooms/get-by-id id))))))

(deftest upload-stores-the-file-and-queues-it
  (let [f    (doto (java.io.File/createTempFile "goob-upload" ".tmp") (spit "twelve bytes"))
        resp (routes/upload {:params {:file {:tempfile f :filename "My.Show.S01E01.MKV" :size 12}}})
        id   (get-in resp [:body :id])
        v    (videos/get-by-id id)]
    (is (= 202 (:status resp)))
    (is (= "queued" (get-in resp [:body :status])))
    (is (= "My.Show.S01E01" (:title v)))
    (is (= "uploaded" (:status v)))
    (is (.isFile (io/file (config/storage-dir) "uploads" (str id ".mkv"))))))

(deftest upload-without-a-file-is-400
  (is (= 400 (:status (routes/upload {:params {}})))))

(deftest delete-video-removes-rows-and-files
  (video! "v1")
  (spit (io/file (storage/dash-dir "v1") "manifest.mpd") "x")
  (let [app    (container-app)
        cookie (login! app)
        room   (:id (rooms/create! "v1"))]
    (is (= 200 (:status (call app :delete "/api/admin/videos/v1" :cookie cookie))))
    (is (nil? (videos/get-by-id "v1")))
    (is (nil? (rooms/get-by-id room)))
    (is (not (.exists (io/file (config/storage-dir) "dash" "v1"))))))

(deftest watch-pages-are-public
  (let [app   (routes/app {:admin? false})
        watch (call app :get "/watch/anything")]
    (is (= 200 (:status watch)))
    (is (str/starts-with? (get-in watch [:headers "Content-Type"]) "text/html"))
    (is (= 200 (:status (call app :get "/"))))))

(deftest clientlog-is-rate-limited-per-token
  (reset! routes/clientlog-hits {})
  (is (every? true? (repeatedly 120 #(#'routes/clientlog-allow? "t" 0))))
  (is (false? (#'routes/clientlog-allow? "t" 0)))
  (is (true? (#'routes/clientlog-allow? "t" 60000))))

(deftest clientlog-clips-and-logs
  (reset! routes/clientlog-hits {})
  (let [resp (atom nil)
        out  (with-out-str
               (reset! resp (call (routes/app {:admin? false}) :post "/api/clientlog"
                                  :body {:token "abcdefghijk" :msg "hi"})))]
    (is (= 200 (:status @resp)))
    (is (str/includes? out "client abcdefgh : hi"))))

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
