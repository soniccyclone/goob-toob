(ns goob-toob.e2e.journeys.host-and-viewer
  (:refer-clojure :exclude [test])
  (:require ["@playwright/test" :refer [test expect]]
            [cljs.core.async :as a :refer [<!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.journey :refer [defjourney]]
            [goob-toob.e2e.metrics :as m]
            [goob-toob.e2e.party :as party]
            [goob-toob.e2e.thresholds :refer [t W]]))

(defjourney "host starts the party" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie video-id]} (api/fixture info)
        room (<p! (api/make-room base-url cookie video-id))
        host (<! (party/open! browser base-url (:token room) {:nick "host" :cookie cookie}))
        joined (<! (party/join! (:page host) (t browser-name :start-within)))
        series (<! (m/sample (:page host) (:host-window W)))
        server (<p! (api/live base-url cookie (:id room)))
        advance (- (:currentTime (last series)) (:currentTime (first series)))
        summary (m/summarize series 0)]
    (<p! (m/record! info summary series (assoc joined :advance advance :server-playing (:playing server))))
    (-> (expect (:started-ms joined) "ms from gesture to started")
        (.toBeLessThanOrEqual (t browser-name :start-within)))
    (-> (expect advance "currentTime advance over the window") (.toBeGreaterThanOrEqual (t browser-name :min-advance)))
    (-> (expect (:playing server) "server playing") (.toBe true))
    (-> (expect (:stalls summary) "stalls in the window") (.toBe 0))))
