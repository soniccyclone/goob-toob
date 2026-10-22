(ns goob-toob.e2e.journeys.autoplay
  (:refer-clojure :exclude [test])
  (:require ["@playwright/test" :refer [test expect]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.async :refer-macros [go-try <?]]
            [goob-toob.e2e.journey :refer [defjourney]]
            [goob-toob.e2e.metrics :as m]
            [goob-toob.e2e.party :as party]
            [goob-toob.e2e.thresholds :refer [t W]]))

(defn- unmute-button [^js page]
  (.getByRole page "button" #js {:name "tap for sound" :exact true}))

(defn- settled-at [series settled]
  (m/first-at #(and (:started %) (number? (:drift %)) (<= (m/abs* (:drift %)) settled)) series))

(defn- outcome [series]
  (let [end (last series)]
    {:autostart (:autostart end) :muted (:muted end) :started (:started end) :paused (:paused end)
     :rejected (get-in end [:counters :rejected]) :autostarts (get-in end [:counters :autostarts])}))

(def ^:private autostart-log? (party/log-line? ":autostart"))

(defn- autostarted!
  "host! plus a viewer with a saved nick whose page autostarted, untouched
  until then, and settled."
  [browser base-url engine fx]
  (go-try
    (let [{:keys [room] :as scene} (<? (party/host! browser base-url engine fx))
          viewer (<? (party/open! browser base-url (:token room) {:nick "viewer" :hands-off? true}))
          page (:page viewer)]
      (<? (party/wait-seen! (:seen viewer) autostart-log? (t engine :autostart-within)))
      (<p! (party/wait-synced! page (t engine :settled) (t engine :sync-within)))
      (assoc scene :viewer viewer))))

(defn- expect-outcome [policy end]
  (case policy
    :strict  (do (-> (expect (:autostart end) "autostart outcome") (.toBe "muted"))
                 (-> (expect (:muted end) "element muted") (.toBe true)))
    :lenient (do (-> (expect (:autostart end) "autostart outcome") (.toBe "audible"))
                 (-> (expect (:muted end) "element muted") (.toBe false)))
    nil))

(defjourney "a returning viewer autostarts into a playing room" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie] :as fx} (api/fixture info)
        {:keys [room]} (<? (party/host! browser base-url browser-name fx))
        t0 (js/Date.now)
        viewer (<? (party/open! browser base-url (:token room) {:nick "viewer" :hands-off? true}))
        page (:page viewer)
        auto (<? (party/wait-seen! (:seen viewer) autostart-log? (t browser-name :autostart-within)))
        started-ms (when auto (- (:at auto) t0))
        ts (js/Date.now)
        series (<? (m/sample page (:autostart-window W) {:live #(api/live base-url cookie (:id room))}))
        settled (t browser-name :settled)
        sync-at (settled-at series settled)
        sync-ms (when (and auto sync-at) (- (+ ts sync-at) (:at auto)))
        summary (m/summarize series sync-at)
        end (outcome series)
        button? (<p! (.isVisible (unmute-button page)))
        server-drift (:drift (api/client (<p! (api/live base-url cookie (:id room))) "viewer"))
        policy (party/policy info)]
    (<p! (m/record! info summary series (assoc end :button button? :started-ms started-ms :sync-ms sync-ms
                                               :log (:log auto) :server-drift server-drift
                                               :policy (name policy))))
    (-> (expect (:rejected end) "rejected plays") (.toBe 0))
    (if (and (= :record policy) (= "blocked" (:autostart end)))
      (do (-> (expect (:paused end) "blocked viewer parked") (.toBe true))
          (-> (expect (m/abs* (:drift-final summary)) "blocked viewer's parked drift")
              (.toBeLessThanOrEqual (t browser-name :park-track))))
      (do (-> (expect (:started end) "started") (.toBe true))
          (-> (expect started-ms "ms from open to started, no gesture")
              (.toBeLessThanOrEqual (t browser-name :autostart-within)))
          (-> (expect sync-ms "ms from started to settled")
              (.toBeLessThanOrEqual (t browser-name :sync-within)))
          (-> (expect (m/abs* server-drift) "server drift at the end") (.toBeLessThanOrEqual settled))))
    (expect-outcome policy end)
    (case policy
      :strict  (-> (expect button? "tap for sound shown") (.toBe true))
      :lenient (-> (expect button? "tap for sound shown") (.toBe false))
      nil)))

(defjourney "tap for sound unmutes without a seek" [{:keys [browser browser-name base-url]} info]
  (.skip test (= :lenient (party/policy info)) "a lenient policy starts audible; there is no button")
  (let [fx (api/fixture info)
        {:keys [viewer]} (<? (autostarted! browser base-url browser-name fx))
        page (:page viewer)
        before (<? (party/probe page))
        button (unmute-button page)
        shown? (<p! (.isVisible button))
        _ (when shown? (<p! (.click button)))
        t0 (js/Date.now)
        unmuted? (try (<p! (party/wait-for! page "goobProbe().muted === false" (t browser-name :unmute-within))) true
                      (catch :default _ false))
        unmute-ms (- (js/Date.now) t0)
        series (<? (m/sample page (:unmute-window W)))
        summary (m/summarize series 0)
        hidden? (not (<p! (.isVisible button)))
        seeks (- (get-in (last series) [:counters :seeks]) (get-in before [:counters :seeks]))]
    (<p! (m/record! info summary series (assoc (outcome series) :autostart-before (:autostart before)
                                               :shown shown? :unmute-ms unmute-ms :hidden hidden?
                                               :seeks-since-click seeks)))
    (when (= :strict (party/policy info))
      (-> (expect shown? "tap for sound shown") (.toBe true))
      (-> (expect unmuted? "unmuted after the tap") (.toBe true))
      (-> (expect unmute-ms "ms from tap to unmuted") (.toBeLessThanOrEqual (t browser-name :unmute-within)))
      (-> (expect hidden? "button hidden after the tap") (.toBe true))
      (-> (expect seeks "seeks since the tap") (.toBe 0))
      (-> (expect (:drift-abs-p95 summary) "drift p95 after the tap")
          (.toBeLessThanOrEqual (t browser-name :steady-p95))))))

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
        summary (m/summarize series (settled-at series (t browser-name :settled)))
        end (outcome series)
        same-doc? (and (number? doc-before) (= doc-before doc-after))
        policy (party/policy info)]
    (<p! (m/record! info summary series (assoc end :same-document same-doc? :policy (name policy))))
    (-> (expect same-doc? "same document after the join, no reload") (.toBe true))
    (when (not= :record policy)
      (-> (expect (:started end) "started") (.toBe true))
      (-> (expect (:autostart end) "autostart outcome") (.toBe "audible"))
      (-> (expect (:muted end) "element muted") (.toBe false)))))

(defjourney "a parked viewer starts when the host presses play" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie video-id]} (api/fixture info)
        room (<p! (api/make-room base-url cookie video-id))
        host (<? (party/open! browser base-url (:token room) {:nick "host" :cookie cookie}))
        viewer (<? (party/open! browser base-url (:token room) {:nick "viewer" :hands-off? true}))
        page (:page viewer)
        seen (:seen viewer)
        _ (<? (party/wait-seen! seen (party/log-line? ":stream-init") (:ready-timeout W)))
        _ (<? (party/wait-seen! seen (party/frame? "pong") (:ready-timeout W)))
        parked-started? (boolean (some (some-fn autostart-log? (party/log-line? ":started")) @seen))
        t0 (js/Date.now)
        host-joined (<? (party/join! (:page host) (t browser-name :start-within)))
        auto (<? (party/wait-seen! seen autostart-log? (t browser-name :autostart-within)))
        started-ms (when auto (- (:at auto) t0))
        ts (js/Date.now)
        series (<? (m/sample page (:autostart-window W) {:live #(api/live base-url cookie (:id room))}))
        settled (t browser-name :settled)
        sync-at (settled-at series settled)
        sync-ms (when (and auto sync-at) (- (+ ts sync-at) (:at auto)))
        summary (m/summarize series sync-at)
        end (outcome series)
        button? (<p! (.isVisible (unmute-button page)))
        policy (party/policy info)]
    (<p! (m/record! info summary series (assoc end :button button? :host-gesture (:gesture host-joined)
                                               :parked-started parked-started? :started-ms started-ms
                                               :sync-ms sync-ms :log (:log auto) :policy (name policy))))
    (-> (expect parked-started? "viewer started before the host's play") (.toBe false))
    (-> (expect (:rejected end) "rejected plays") (.toBe 0))
    (when (not= :record policy)
      (-> (expect (:started end) "started") (.toBe true))
      (-> (expect started-ms "ms from the host's play to started, no gesture")
          (.toBeLessThanOrEqual (t browser-name :autostart-within)))
      (-> (expect sync-ms "ms from started to settled")
          (.toBeLessThanOrEqual (t browser-name :sync-within))))
    (expect-outcome policy end)
    (case policy
      :strict  (-> (expect button? "tap for sound shown") (.toBe true))
      :lenient (-> (expect button? "tap for sound shown") (.toBe false))
      nil)))

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
        {:keys [viewer]} (<? (autostarted! browser base-url browser-name fx))
        page (:page viewer)
        _ (<p! (.evaluate page count-events))
        before (<? (party/probe page))
        button-before (<p! (.isVisible (unmute-button page)))
        pause-gesture (<? (toggle! page "pause"))
        play-gesture (<? (toggle! page "play"))
        series (<? (m/sample page (:autostart-window W)))
        settled (t browser-name :settled)
        sync-at (m/first-at #(and (number? (:drift %)) (<= (m/abs* (:drift %)) settled)) series)
        summary (m/summarize series sync-at)
        end (last series)
        button-after (<p! (.isVisible (unmute-button page)))]
    (<p! (m/record! info summary series (assoc (outcome series) :pause-gesture pause-gesture
                                               :play-gesture play-gesture :sync-at sync-at
                                               :autostart-before (:autostart before)
                                               :button-before button-before :button-after button-after)))
    (-> (expect (every? :started series) "started on every sample") (.toBe true))
    (-> (expect (:autostart end) "autostart outcome") (.toBe (:autostart before)))
    (-> (expect (get-in end [:counters :autostarts]) "autostart attempts")
        (.toBe (get-in before [:counters :autostarts])))
    (-> (expect (get-in end [:counters :rejected]) "rejected plays")
        (.toBe (get-in before [:counters :rejected])))
    (-> (expect button-after "tap for sound visibility") (.toBe button-before))
    (-> (expect sync-at "ms from the solo play to settled") (.toBeLessThanOrEqual (t browser-name :sync-within)))))
