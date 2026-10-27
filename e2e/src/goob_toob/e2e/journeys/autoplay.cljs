(ns goob-toob.e2e.journeys.autoplay
  (:refer-clojure :exclude [test])
  (:require ["@playwright/test" :refer [test expect]]
            [cljs.core.async :as a :refer [<!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.async :refer-macros [go-try <?]]
            [goob-toob.e2e.journey :refer [defjourney]]
            [goob-toob.e2e.metrics :as m]
            [goob-toob.e2e.party :as party]
            [goob-toob.e2e.thresholds :refer [t W]]))

(def ^:private audible-log? (party/log-line? ":autostart {"))
(def ^:private blocked-log? (party/log-line? ":autostart-blocked"))

(defn- settled-at [series settled]
  (m/first-at #(and (:started %) (number? (:drift %)) (<= (m/abs* (:drift %)) settled)) series))

(defn- outcome [series]
  (let [end (last series)]
    {:autostart (:autostart end) :muted (:muted end) :started (:started end) :paused (:paused end)
     :rejected (get-in end [:counters :rejected]) :autostarts (get-in end [:counters :autostarts])}))

(defn- expect-audible [end]
  (-> (expect (:started end) "started") (.toBe true))
  (-> (expect (:autostart end) "autostart outcome") (.toBe "audible"))
  (-> (expect (:muted end) "element muted") (.toBe false)))

(defjourney "a returning viewer in a strict browser stays parked until they touch the page"
  [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie] :as fx} (api/fixture info)
        {:keys [room]} (<? (party/host! browser base-url browser-name fx))
        policy (party/policy info)
        strict? (not= :lenient policy)
        t0 (js/Date.now)
        viewer (<? (party/open! browser base-url (:token room) {:nick "viewer" :hands-off? true}))
        page (:page viewer)
        seen (:seen viewer)
        blocked (when strict?
                  (<? (party/wait-seen! seen (some-fn blocked-log? audible-log?) (t browser-name :autostart-within))))
        _ (when strict? (<! (a/timeout (:parked-hold W))))
        early-audible (some audible-log? @seen)
        parked (when strict? (<? (party/probe page)))
        t-touch (js/Date.now)
        _ (when strict? (<p! (party/touch! page)))
        auto (<? (party/wait-seen! seen audible-log?
                                   (if strict? (party/touch-start-within) (t browser-name :autostart-within))))
        started-ms (when auto (- (:at auto) (if strict? t-touch t0)))
        ts (js/Date.now)
        series (<? (m/sample page (:autostart-window W) {:live #(api/live base-url cookie (:id room))}))
        settled (t browser-name :landed)
        sync-at (settled-at series settled)
        sync-ms (when (and auto sync-at) (- (+ ts sync-at) (:at auto)))
        summary (m/summarize series sync-at)
        end (outcome series)
        server-drift (:drift (api/client (<p! (api/live base-url cookie (:id room))) "viewer"))]
    (<p! (m/record! info summary series
                    (assoc end :policy (name policy) :blocked-log (:log blocked) :early-audible (boolean early-audible)
                           :parked-started (:started parked) :parked-paused (:paused parked)
                           :parked-autostart (:autostart parked) :parked-drift (:drift parked)
                           :started-ms started-ms :sync-ms sync-ms :server-drift server-drift)))
    (when strict?
      (-> (expect (blocked-log? blocked) "first autostart blocked") (.toBe true))
      (-> (expect (boolean early-audible) "audible start before the touch") (.toBe false))
      (-> (expect (:started parked) "started before the touch") (.toBe false))
      (-> (expect (:paused parked) "paused before the touch") (.toBe true))
      (-> (expect (:autostart parked) "autostart outcome before the touch") (.toBe "blocked"))
      (-> (expect (m/abs* (:drift parked)) "parked drift before the touch")
          (.toBeLessThanOrEqual (t browser-name :park-track))))
    (expect-audible end)
    (-> (expect started-ms (if strict? "ms from touch to audible start" "ms from open to audible start, no gesture"))
        (.toBeLessThanOrEqual (if strict? (party/touch-start-within) (t browser-name :autostart-within))))
    (-> (expect (:rejected end) "rejected plays") (.toBe 0))
    (-> (expect sync-ms "ms from started to settled") (.toBeLessThanOrEqual (t browser-name :sync-within)))
    (-> (expect (m/abs* server-drift) "server drift at the end") (.toBeLessThanOrEqual settled))))

(def ^:private doc-marker "window.goobDoc = Math.random();")

(defjourney "a first-time viewer's join click gives sound" [{:keys [browser browser-name base-url]} info]
  (let [fx (api/fixture info)
        {:keys [room]} (<? (party/host! browser base-url browser-name fx))
        viewer (<? (party/open! browser base-url (:token room)
                                {:nick nil
                                 :before-goto (fn [^js ctx _] (go-try (<p! (.addInitScript ctx doc-marker))))}))
        page (:page viewer)
        doc-before (<p! (.evaluate page "window.goobDoc"))
        _ (<? (party/enter-nick! page "newcomer"))
        series (<? (m/sample page (:autostart-window W)))
        doc-after (<p! (.evaluate page "window.goobDoc"))
        summary (m/summarize series (settled-at series (t browser-name :landed)))
        end (outcome series)
        same-doc? (and (number? doc-before) (= doc-before doc-after))
        policy (party/policy info)]
    (<p! (m/record! info summary series (assoc end :same-document same-doc? :policy (name policy))))
    (-> (expect same-doc? "same document after the join, no reload") (.toBe true))
    (when (not= :record policy)
      (expect-audible end))))

(defjourney "a parked viewer starts when the host presses play" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie video-id]} (api/fixture info)
        room (<p! (api/make-room base-url cookie video-id))
        host (<? (party/open! browser base-url (:token room) {:nick "host" :cookie cookie}))
        viewer (<? (party/open! browser base-url (:token room) {:nick "viewer" :hands-off? true}))
        page (:page viewer)
        seen (:seen viewer)
        policy (party/policy info)
        _ (<? (party/wait-seen! seen (party/log-line? ":stream-init") (:ready-timeout W)))
        _ (<? (party/wait-seen! seen (party/frame? "pong") (:ready-timeout W)))
        _ (when (not= :lenient policy) (<p! (party/touch! page)))
        parked-started? (boolean (some (some-fn audible-log? blocked-log? (party/log-line? ":started")) @seen))
        t0 (js/Date.now)
        host-joined (<? (party/join! (:page host) (t browser-name :start-within)))
        auto (<? (party/wait-seen! seen audible-log? (t browser-name :autostart-within)))
        started-ms (when auto (- (:at auto) t0))
        ts (js/Date.now)
        series (<? (m/sample page (:autostart-window W) {:live #(api/live base-url cookie (:id room))}))
        settled (t browser-name :landed)
        sync-at (settled-at series settled)
        sync-ms (when (and auto sync-at) (- (+ ts sync-at) (:at auto)))
        summary (m/summarize series sync-at)
        end (outcome series)]
    (<p! (m/record! info summary series (assoc end :host-gesture (:gesture host-joined)
                                               :parked-started parked-started? :started-ms started-ms
                                               :sync-ms sync-ms :log (:log auto) :policy (name policy))))
    (-> (expect parked-started? "viewer started before the host's play") (.toBe false))
    (-> (expect (:rejected end) "rejected plays") (.toBe 0))
    (when (not= :record policy)
      (-> (expect started-ms "ms from the host's play to the audible start")
          (.toBeLessThanOrEqual (t browser-name :autostart-within)))
      (expect-audible end)
      (-> (expect sync-ms "ms from started to settled")
          (.toBeLessThanOrEqual (t browser-name :sync-within))))))

(defjourney "a controller still has to start a paused room" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie video-id]} (api/fixture info)
        room (<p! (api/make-room base-url cookie video-id))
        _ (<p! (api/set-control! base-url cookie (:id room) true))
        host (<? (party/open! browser base-url (:token room) {:nick "host" :cookie cookie}))
        viewer (<? (party/open! browser base-url (:token room) {:nick "cohost"}))
        page (:page viewer)
        _ (<p! (party/wait-parked! page))
        idle (<? (m/sample page (:control-idle W) {:live #(api/live base-url cookie (:id room))}))
        joined (<? (party/join! page (t browser-name :start-within)))
        t0 (js/Date.now)
        after (<? (m/sample-until #(api/live base-url cookie (:id room)) #(true? (:playing %))
                                  (t browser-name :control-within)))
        room-ms (when (true? (:playing (last after))) (- (js/Date.now) t0))
        host-playing? (try (<p! (party/wait-for! (:page host) "goobProbe().paused === false"
                                                 (t browser-name :autostart-within)))
                           true
                           (catch :default _ false))
        server-idle (keep #(get-in % [:server :playing]) idle)]
    (<p! (m/record! info (m/summarize idle 0) idle
                    (assoc joined :idle-started (boolean (some :started idle))
                           :idle-server-playing (vec (distinct server-idle))
                           :room-ms room-ms :host-playing host-playing?)))
    (-> (expect (some :started idle) "viewer started while the room was paused") (.toBeFalsy))
    (-> (expect (boolean (seq server-idle)) "server polled while idle") (.toBe true))
    (-> (expect (every? false? server-idle) "server paused while idle") (.toBe true))
    (-> (expect room-ms "ms from the co-host's play to server playing")
        (.toBeLessThanOrEqual (t browser-name :control-within)))
    (-> (expect host-playing? "host's element playing") (.toBe true))))

(defn- toggle!
  "Space on the focused video; a click on its surface when Space did not
  fire ev within :gesture-retry."
  [^js page ev]
  (go-try
    (let [video (.locator page "video")
          before (<p! (.evaluate page (str "window.goobEv." ev)))]
      (<p! (.focus video))
      (<p! (.press (.-keyboard page) "Space"))
      (let [fired? (try (<p! (.waitForFunction page (str "window.goobEv." ev " > " before) nil
                                               #js {:timeout (:gesture-retry W)}))
                        true
                        (catch :default _ false))]
        (when-not fired? (<p! (.click video)))
        (<p! (.waitForFunction page (str "window.goobEv." ev " > " before) nil
                               #js {:timeout (:gesture-retry W)}))
        (if fired? "space" "click")))))

(def ^:private count-events
  (str "(() => { window.goobEv = {pause: 0, play: 0};"
       " const v = document.querySelector('video');"
       " v.addEventListener('pause', () => window.goobEv.pause++);"
       " v.addEventListener('play', () => window.goobEv.play++); })()"))

(defjourney "solo pause and play keep the join" [{:keys [browser browser-name base-url]} info]
  (let [fx (api/fixture info)
        {:keys [viewer joined]} (<? (party/pair! browser base-url browser-name fx))
        page (:page viewer)
        _ (<p! (.evaluate page count-events))
        before (<? (party/probe page))
        pause-gesture (<? (toggle! page "pause"))
        play-gesture (<? (toggle! page "play"))
        series (<? (m/sample page (:autostart-window W)))
        settled (t browser-name :landed)
        sync-at (m/first-at #(and (number? (:drift %)) (<= (m/abs* (:drift %)) settled)) series)
        summary (m/summarize series sync-at)
        end (last series)]
    (<p! (m/record! info summary series (assoc (outcome series) :join-gesture (:gesture joined)
                                               :pause-gesture pause-gesture :play-gesture play-gesture
                                               :sync-at sync-at :autostart-before (:autostart before))))
    (-> (expect (every? :started series) "started on every sample") (.toBe true))
    (-> (expect (:autostart end) "autostart outcome") (.toBe (:autostart before)))
    (-> (expect (get-in end [:counters :autostarts]) "autostart attempts")
        (.toBe (get-in before [:counters :autostarts])))
    (-> (expect (get-in end [:counters :rejected]) "rejected plays")
        (.toBe (get-in before [:counters :rejected])))
    (-> (expect (:muted end) "element muted") (.toBe false))
    (-> (expect sync-at "ms from the solo play to settled") (.toBeLessThanOrEqual (t browser-name :sync-within)))))
