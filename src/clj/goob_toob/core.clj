(ns goob-toob.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [goob-toob.config :as config]
            [goob-toob.db :as db]
            [goob-toob.routes :as routes]
            [goob-toob.services.transcode :as transcode]
            [goob-toob.services.sync :as sync])
  (:gen-class))

(defonce server (atom nil))
(defonce admin-server (atom nil))

(defn -main []
  (let [cfg (config/load!)
        desktop? (= :desktop (:mode cfg))]
    (db/init! (:db-path cfg))
    (transcode/start!)
    (sync/start-beat-loop!)
    (if desktop?
      ;; public port: no admin routes at all (this is what cloudflared tunnels);
      ;; admin port: loopback-only, always-admin, never exposed.
      (do (reset! server
                  (run-jetty (routes/app {:admin? false})
                             {:port (:port cfg) :join? false}))
          (reset! admin-server
                  (run-jetty (routes/wrap-desktop-admin (routes/app {:admin? true}))
                             {:port (:admin-port cfg) :host "127.0.0.1"
                              :join? false}))
          (println "goob-toob glooping — public :" (:port cfg)
                   " admin(loopback) :" (:admin-port cfg)))
      (do (reset! server
                  (run-jetty (routes/app {:admin? true :login? true})
                             {:port (:port cfg) :join? false}))
          (println "goob-toob glooping on port" (:port cfg))))))
