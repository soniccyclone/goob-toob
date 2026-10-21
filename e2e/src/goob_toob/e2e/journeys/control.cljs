(ns goob-toob.e2e.journeys.control
  (:refer-clojure :exclude [test])
  (:require ["@playwright/test" :refer [test expect]]
            [cljs.core.async :as a :refer [<!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.async :refer-macros [<?]]
            [goob-toob.e2e.journey :refer [defjourney]]
            [goob-toob.e2e.metrics :as m]
            [goob-toob.e2e.party :as party]
            [goob-toob.e2e.thresholds :refer [t W]]))

(defn- raw-cmd [token]
  (str "new Promise(res => {"
       " const ws = new WebSocket(`ws://${location.host}/ws/room/" token "?nick=intruder`);"
       " ws.onopen = () => { ws.send(JSON.stringify({type: 'cmd', op: 'pause', pos: 0}));"
       "                     setTimeout(() => { ws.close(); res(true); }, 200); };"
       " ws.onerror = () => res(false); })"))

(defn- poll-live [base cookie room-id ms]
  (m/sample-fn #(api/live base cookie room-id) ms))

(defjourney "a viewer cannot drive until the host allows it" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie] :as fx} (api/fixture info)
        {:keys [room host]} (<? (party/host! browser base-url browser-name fx))
        viewer (<? (party/open! browser base-url (:token room) {:nick "viewer"}))
        ^js vp (:page viewer)
        play-btn (.getByRole vp "button" #js {:name "▶ play" :exact true})
        _ (<p! (-> (expect play-btn) (.toBeHidden)))
        sent? (<p! (.evaluate vp (raw-cmd (:token room))))
        held (<? (poll-live base-url cookie (:id room) (t browser-name :control-hold)))
        _ (<p! (api/set-control! base-url cookie (:id room) true))
        _ (<p! (.reload vp))
        _ (<p! (party/wait-for! vp "window.goobProbe && goobProbe().expected !== null" (:ready-timeout W)))
        _ (<p! (-> (expect play-btn) (.toBeVisible)))
        _ (<p! (-> (expect (.getByText vp "co-hosting" #js {:exact true})) (.toBeVisible)))
        t0 (js/Date.now)
        _ (<p! (party/pause! vp))
        after (<? (m/sample-until #(api/live base-url cookie (:id room)) #(false? (:playing %))
                                  (t browser-name :control-within)))
        stopped-ms (when (false? (:playing (last after))) (- (js/Date.now) t0))
        host-paused (try (<p! (party/wait-for! (:page host) "goobProbe().paused" (t browser-name :control-within))) true
                         (catch :default _ false))
        series (into held after)]
    (<p! (m/record! info {} series {:raw-sent sent? :held (every? :playing held)
                                    :stopped-ms stopped-ms :host-paused host-paused}))
    (-> (expect sent? "raw socket sent its cmd") (.toBe true))
    (-> (expect (every? :playing held) "room kept playing after the viewer's raw cmd") (.toBe true))
    (-> (expect stopped-ms "ms from co-host pause to server paused") (.not.toBeNull))
    (-> (expect stopped-ms "ms from co-host pause to server paused")
        (.toBeLessThanOrEqual (t browser-name :control-within)))
    (-> (expect host-paused "host's element paused") (.toBe true))))
