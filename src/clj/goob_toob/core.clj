(ns goob-toob.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [goob-toob.config :as config]
            [goob-toob.db :as db]
            [goob-toob.log :as log]
            [goob-toob.routes :as routes]
            [goob-toob.services.transcode :as transcode]
            [goob-toob.services.sync :as sync])
  (:gen-class))

(defonce server (atom nil))
(defonce admin-server (atom nil))

;; stamped into the jar by build.clj; GOOB_VERSION lets the Electron shell
;; (which knows its own package.json version) override; dev runs say "dev".
(defn- version []
  (or (some-> (System/getenv "GOOB_VERSION") (not-empty))
      (some-> (io/resource "goob-toob.version") slurp str/trim (not-empty))
      "dev"))

(defn -main []
  (let [cfg (config/load!)
        desktop? (= :desktop (:mode cfg))]
    (log/info "goob-toob" (version))
    (db/init! (:db-path cfg))
    (transcode/start!)
    ;; SIGTERM (the desktop shell's kill on window close) lands here: end any
    ;; ffmpeg mid-encode instead of orphaning it (#49)
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable transcode/stop! "transcode-stop"))
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
          (log/info "goob-toob glooping — public :" (:port cfg)
                   " admin(loopback) :" (:admin-port cfg)))
      (do (reset! server
                  (run-jetty (routes/app {:admin? true :login? true})
                             {:port (:port cfg) :join? false}))
          (log/info "goob-toob glooping on port" (:port cfg))))))
