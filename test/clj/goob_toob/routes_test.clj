(ns goob-toob.routes-test
  "The two-port security invariant: whatever the tunnel can reach (public
  port) must have NO admin surface; the loopback admin port is always-admin."
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [goob-toob.routes :as routes]))

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
