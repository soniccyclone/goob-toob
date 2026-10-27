(ns goob-toob.e2e.journeys.resilience
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

(defn- peer-listed-expr [nick]
  (str "[...document.querySelectorAll('.peer')].some(p => p.firstChild.textContent.trim() === "
       (js/JSON.stringify nick) ")"))

(defn- peer-listed? [^js page nick]
  (.evaluate page (peer-listed-expr nick)))

(defjourney "a dropped socket resyncs" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie] :as fx} (api/fixture info)
        {:keys [room host]} (<? (party/host! browser base-url browser-name fx))
        _ (<! (a/timeout (:host-head-start W)))
        viewer (<? (party/open! browser base-url (:token room)
                                {:nick "viewer"
                                 :before-goto (fn [ctx _] (party/droppable! ctx))}))
        hp (:page host) vp (:page viewer)
        ^js vctx (:context viewer)
        _ (<? (party/join! vp (t browser-name :start-within)))
        _ (<p! (party/wait-synced! vp (t browser-name :landed) (t browser-name :sync-within)))
        listed-before (<p! (peer-listed? hp "viewer"))
        state (:hook viewer)
        _ (<p! (.setOffline vctx true))
        _ (<p! (party/drop! state))
        t-off (js/Date.now)
        offline (loop [seen [] seeked? false]
                  (let [el (- (js/Date.now) t-off)]
                    (if (> el (:offline-for W))
                      seen
                      (let [seek-now? (and (not seeked?) (> el (/ (:offline-for W) 3)))]
                        (when seek-now?
                          (let [hpr (<? (party/probe hp))]
                            (<p! (party/seek! hp (+ (:currentTime hpr) (:offline-seek W))))))
                        (<! (a/timeout (:sample-every W)))
                        (recur (conj seen {:at el :listed (<p! (peer-listed? hp "viewer"))})
                               (or seeked? seek-now?))))))
        dropped? (boolean (some (complement :listed) offline))
        _ (party/restore! state)
        _ (<p! (.setOffline vctx false))
        t-on (js/Date.now)
        before (<? (party/probe vp))
        listed-after (try (<p! (party/wait-for! hp (peer-listed-expr "viewer") (t browser-name :resync-within))) true
                          (catch :default _ false))
        reconnect-ms (- (js/Date.now) t-on)
        series (<? (m/sample vp (max 0 (- (t browser-name :resync-within) reconnect-ms))
                             {:live #(api/live base-url cookie (:id room))}))
        settled (t browser-name :landed)
        resync-at (m/first-at #(and (number? (:drift %)) (<= (m/abs* (:drift %)) settled)) series)
        summary (m/summarize series resync-at)
        ;; the resync is a landing (a seek plus a buffering stall), so the
        ;; stall count starts at the sample where the viewer is back inside
        ;; the dead band, not at the moment the socket came back
        at-resync (or (some #(when (>= (:at %) (or resync-at 0)) %) series) (first series))
        stalls-after (- (get-in (last series) [:counters :stalls] 0) (get-in at-resync [:counters :stalls] 0))
        seeks-after (- (get-in (last series) [:counters :seeks]) (get-in before [:counters :seeks]))]
    (<p! (m/record! info summary series {:listed-before listed-before :dropped dropped?
                                         :listed-after listed-after :reconnect-ms reconnect-ms
                                         :resync-at resync-at :stalls-after stalls-after
                                         :seeks-after seeks-after :drift-before (:drift before)}))
    (-> (expect listed-before "viewer listed on the host before") (.toBe true))
    (-> (expect dropped? "viewer dropped from the host's peers while offline") (.toBe true))
    (-> (expect listed-after "viewer listed on the host after") (.toBe true))
    (-> (expect resync-at "ms from online to settled") (.not.toBeNull))
    (-> (expect (+ reconnect-ms resync-at) "ms from online to settled")
        (.toBeLessThanOrEqual (t browser-name :resync-within)))
    (-> (expect seeks-after "seeks after reconnect") (.toBeLessThanOrEqual (t browser-name :reconnect-seeks)))
    (-> (expect (:drift-abs-p95 summary) "drift p95 after the resync") (.toBeLessThanOrEqual (t browser-name :landed)))
    (-> (expect stalls-after "stalls after the resync") (.toBe 0))))

(defjourney "a late joiner lands on the live frame" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie] :as fx} (api/fixture info)
        {:keys [room]} (<? (party/host! browser base-url browser-name fx))
        _ (<! (a/timeout (:late-head-start W)))
        viewer (<? (party/open! browser base-url (:token room) {:nick "late"}))
        page (:page viewer)
        joined (<? (party/join! page (t browser-name :start-within)))
        t1 (js/Date.now)
        synced? (try (<p! (party/wait-synced! page (t browser-name :landed) (t browser-name :sync-within))) true
                     (catch :default _ false))
        sync-ms (- (js/Date.now) t1)
        series (<? (m/sample page (:parked-window W)))
        summary (m/summarize series 0)
        end (last series)
        server-drift (:drift (api/client (<p! (api/live base-url cookie (:id room))) "late"))]
    (<p! (m/record! info summary series (assoc joined :sync-ms sync-ms :autostart (:autostart end)
                                               :muted (:muted end) :server-drift server-drift)))
    (-> (expect (:gesture joined) "joined without pressing play") (.toMatch #"^(autostart|touch)$"))
    (-> (expect (:started-ms joined) "ms from open to started")
        (.toBeLessThanOrEqual (party/touch-start-within)))
    (-> (expect (:autostart end) "autostart outcome") (.toBe "audible"))
    (-> (expect (:muted end) "element muted") (.toBe false))
    (-> (expect synced? "settled after the start") (.toBe true))
    (-> (expect sync-ms "ms from started to settled") (.toBeLessThanOrEqual (t browser-name :sync-within)))
    (-> (expect (get-in end [:counters :rejected]) "rejected plays") (.toBe 0))
    (-> (expect (m/abs* server-drift) "server drift at the end") (.toBeLessThanOrEqual (t browser-name :landed)))))

(defjourney "a viewer parks on a paused room" [{:keys [browser browser-name base-url]} info]
  (let [fx (api/fixture info)
        {:keys [room host]} (<? (party/host! browser base-url browser-name fx))
        _ (<! (a/timeout (:late-head-start W)))
        _ (<p! (party/pause! (:page host)))
        _ (<p! (party/wait-for! (:page host) "goobProbe().paused && goobProbe().playing === false"
                                (t browser-name :sync-within)))
        viewer (<? (party/open! browser base-url (:token room) {:nick "late"}))
        page (:page viewer)
        _ (<p! (party/wait-parked! page))
        t0 (js/Date.now)
        live? (try (<p! (party/wait-live-park! page (t browser-name :park-drift) (t browser-name :park-live-within))) true
                   (catch :default _ false))
        live-ms (- (js/Date.now) t0)
        series (<? (m/sample page (:parked-window W)))
        summary (m/summarize series 0)]
    (<p! (m/record! info summary series {:live-ms live-ms :current-times (vec (dedupe (map :currentTime series)))}))
    (-> (expect live? "parked frame reached the room") (.toBe true))
    (-> (expect live-ms "ms from parked to the live frame") (.toBeLessThanOrEqual (t browser-name :park-live-within)))
    (-> (expect (some :started series) "started before any gesture") (.toBeFalsy))
    (-> (expect (every? :paused series) "paused on every sample") (.toBe true))
    (-> (expect (:drift-abs-max summary) "parked drift, worst sample") (.toBeLessThanOrEqual (t browser-name :park-track)))))
