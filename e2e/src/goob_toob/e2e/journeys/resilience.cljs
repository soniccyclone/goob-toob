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
  (.fail test true (str "product bug: a reconnect applies the room clock but not its position,"
                        " so a seek missed while offline leaves the viewer behind"))
  (let [{:keys [cookie] :as fx} (api/fixture info)
        {:keys [room host]} (<? (party/host! browser base-url browser-name fx))
        _ (<! (a/timeout (:host-head-start W)))
        viewer (<? (party/open! browser base-url (:token room)
                                {:nick "viewer"
                                 :before-goto (fn [ctx _] (party/droppable! ctx))}))
        hp (:page host) vp (:page viewer)
        ^js vctx (:context viewer)
        _ (<p! (party/wait-parked! vp))
        _ (<? (party/join! vp (t browser-name :start-within)))
        _ (<p! (party/wait-synced! vp (t browser-name :settled) (t browser-name :sync-within)))
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
        settled (t browser-name :settled)
        resync-at (m/first-at #(and (number? (:drift %)) (<= (m/abs* (:drift %)) settled)) series)
        summary (m/summarize series resync-at)
        seeks-after (- (get-in (last series) [:counters :seeks]) (get-in before [:counters :seeks]))]
    (<p! (m/record! info summary series {:listed-before listed-before :dropped dropped?
                                         :listed-after listed-after :reconnect-ms reconnect-ms
                                         :resync-at resync-at
                                         :seeks-after seeks-after :drift-before (:drift before)}))
    (-> (expect listed-before "viewer listed on the host before") (.toBe true))
    (-> (expect dropped? "viewer dropped from the host's peers while offline") (.toBe true))
    (-> (expect listed-after "viewer listed on the host after") (.toBe true))
    (-> (expect resync-at "ms from online to settled") (.not.toBeNull))
    (-> (expect (+ reconnect-ms resync-at) "ms from online to settled")
        (.toBeLessThanOrEqual (t browser-name :resync-within)))
    (-> (expect seeks-after "seeks after reconnect") (.toBeLessThanOrEqual (t browser-name :reconnect-seeks)))))

(defjourney "a late joiner parks on the live frame" [{:keys [browser browser-name base-url]} info]
  (let [fx (api/fixture info)
        {:keys [room]} (<? (party/host! browser base-url browser-name fx))
        _ (<! (a/timeout (:late-head-start W)))
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
