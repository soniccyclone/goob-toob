(ns goob-toob.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [goob-toob.config :as config]
            [goob-toob.db :as db]
            [goob-toob.routes :as routes]
            [goob-toob.services.transcode :as transcode]
            [goob-toob.services.sync :as sync])
  (:gen-class))

(defonce server (atom nil))

(defn -main []
  (let [cfg (config/load!)]
    (db/init! (:db-path cfg))
    (transcode/start!)
    (sync/start-beat-loop!)
    (reset! server (run-jetty (routes/app) {:port (:port cfg) :join? false}))
    (println "goob-toob glooping on port" (:port cfg))))
