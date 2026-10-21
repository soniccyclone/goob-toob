(ns goob-toob.e2e.journeys.host-and-viewer
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

(defjourney "host starts the party" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie] :as fx} (api/fixture info)
        {:keys [room host host-joined]} (<? (party/host! browser base-url browser-name fx))
        series (<? (m/sample (:page host) (:host-window W)))
        server (<p! (api/live base-url cookie (:id room)))
        advance (- (:currentTime (last series)) (:currentTime (first series)))
        summary (m/summarize series 0)]
    (<p! (m/record! info summary series (assoc host-joined :advance advance :server-playing (:playing server))))
    (-> (expect (:started-ms host-joined) "ms from gesture to started")
        (.toBeLessThanOrEqual (t browser-name :start-within)))
    (-> (expect advance "currentTime advance over the window")
        (.toBeGreaterThanOrEqual (t browser-name :min-advance)))
    (-> (expect (:playing server) "server playing") (.toBe true))
    (-> (expect (:stalls summary) "stalls in the window") (.toBe 0))))

(defjourney "viewer joins a playing room in sync" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie] :as fx} (api/fixture info)
        {:keys [room]} (<? (party/host! browser base-url browser-name fx))
        _ (<! (a/timeout (:host-head-start W)))
        viewer (<? (party/open! browser base-url (:token room) {:nick "viewer"}))
        page (:page viewer)
        _ (<p! (party/wait-parked! page))
        first-park (<? (party/probe page))
        t0 (js/Date.now)
        _ (<p! (party/wait-live-park! page (t browser-name :park-drift) (t browser-name :park-live-within)))
        live-ms (- (js/Date.now) t0)
        parked (<? (party/probe page))
        joined (<? (party/join! page (t browser-name :start-within)))
        series (<? (m/sample page (:join-window W)
                             {:live #(api/live base-url cookie (:id room))}))
        settled (t browser-name :settled)
        sync-at (m/first-at #(and (:started %) (number? (:drift %)) (<= (m/abs* (:drift %)) settled)) series)
        summary (m/summarize series sync-at)
        server-drift (:drift (api/client (<p! (api/live base-url cookie (:id room))) "viewer"))
        parked-drift (m/abs* (:drift parked))]
    (<p! (m/record! info summary series
                    (assoc joined :parked-drift parked-drift :first-park-drift (:drift first-park)
                           :live-ms live-ms :sync-at sync-at :server-drift server-drift)))
    (-> (expect parked-drift "parked drift") (.toBeLessThanOrEqual (t browser-name :park-drift)))
    (-> (expect sync-at "ms from join to settled") (.toBeLessThanOrEqual (t browser-name :sync-within)))
    (-> (expect (:drift-abs-p95 summary) "drift p95 after settle")
        (.toBeLessThanOrEqual (t browser-name :steady-p95)))
    (-> (expect (m/abs* server-drift) "server drift at the end") (.toBeLessThanOrEqual settled))
    (-> (expect (:seeks summary) "seeks after join") (.toBe 0))
    (-> (expect (:seekbacks summary) "seekbacks") (.toBe 0))))

(defn- both-at [pos tol]
  (str "(() => { const p = goobProbe(); return p.paused && Math.abs(p.currentTime - " pos ") <= " tol "; })()"))

(defjourney "pause, seek and resume agree" [{:keys [browser browser-name base-url]} info]
  (let [fx (api/fixture info)
        {:keys [host viewer]} (<? (party/pair! browser base-url browser-name fx))
        hp (:page host) vp (:page viewer)
        tol (t browser-name :pause-agreement)
        within (t browser-name :sync-within)
        _ (<p! (party/pause! hp))
        _ (<p! (party/wait-for! hp "goobProbe().paused" within))
        _ (<p! (party/wait-for! vp "goobProbe().paused" within))
        hpause (<? (party/probe hp))
        vpause (<? (party/probe vp))
        pause-gap (m/abs* (- (:currentTime hpause) (:currentTime vpause)))
        target (+ (:currentTime hpause) (:seek-jump W))
        _ (<p! (party/seek! hp target))
        host-seeked (try (<p! (party/wait-for! hp (both-at target tol) within)) true (catch :default _ false))
        viewer-seeked (try (<p! (party/wait-for! vp (both-at target tol) within)) true (catch :default _ false))
        hseek (<? (party/probe hp))
        vseek (<? (party/probe vp))
        _ (<p! (party/play! hp))
        series (<? (m/sample vp (:resume-window W)))
        sync-at (m/first-at #(and (number? (:drift %)) (<= (m/abs* (:drift %)) (t browser-name :settled))) series)
        summary (m/summarize series sync-at)
        extra {:pause-gap pause-gap :target target
               :host-seek-gap (m/abs* (- (:currentTime hseek) target))
               :viewer-seek-gap (m/abs* (- (:currentTime vseek) target))
               :host-seeked host-seeked :viewer-seeked viewer-seeked :sync-at sync-at}]
    (<p! (m/record! info summary series extra))
    (-> (expect (:paused vpause) "viewer paused") (.toBe true))
    (-> (expect pause-gap "currentTime gap while paused") (.toBeLessThanOrEqual tol))
    (-> (expect (:host-seek-gap extra) "host distance from the seek target") (.toBeLessThanOrEqual tol))
    (-> (expect (:viewer-seek-gap extra) "viewer distance from the seek target") (.toBeLessThanOrEqual tol))
    (-> (expect sync-at "ms from resume to settled") (.not.toBeNull))
    (-> (expect (:drift-abs-p95 summary) "viewer drift p95 after resume")
        (.toBeLessThanOrEqual (t browser-name :steady-p95)))))
