(ns goob-toob.e2e.party
  (:require [clojure.string :as str]
            [cljs.core.async :as a :refer [<!]]
            [goob-toob.e2e.async :refer-macros [go-try <?]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.thresholds :refer [t W]]))

(defonce ^:private contexts (atom []))

(defn close-all! []
  (let [cs @contexts]
    (reset! contexts [])
    (js/Promise.all (clj->js (map (fn [^js c] (.catch (.close c) (fn [_] nil))) cs)))))

(defn- cookie-entry [cookie]
  (let [[k v] (str/split cookie #"=" 2)]
    #js [#js {:name k :value v :domain "127.0.0.1" :path "/"}]))

(defn- json [x] (js/JSON.stringify (clj->js x)))

(defn open!
  "Opens a fresh context on /watch/<token> as nick, with the admin cookie when
  given, and waits for the room's welcome. before-goto receives the context
  and page before navigation and returns a channel; its value comes back
  as :hook."
  [^js browser base-url token {:keys [nick cookie before-goto]}]
  (go-try
    (let [^js ctx (<p! (.newContext browser #js {:baseURL base-url}))]
      (swap! contexts conj ctx)
      (when cookie (<p! (.addCookies ctx (cookie-entry cookie))))
      (<p! (.addInitScript ctx (str "localStorage.setItem('goob-nick', " (json nick) ")")))
      (let [^js page (<p! (.newPage ctx))]
        (let [hook (when before-goto (<? (before-goto ctx page)))]
          (<p! (.goto page (str "/watch/" token)))
          (<p! (.waitForFunction page "window.goobProbe && goobProbe().expected !== null"
                                 nil #js {:timeout (:ready-timeout W)}))
          {:context ctx :page page :nick nick :hook hook})))))

(defn open-page!
  "A fresh context at path, with the admin cookie when given."
  [^js browser base-url path {:keys [cookie]}]
  (go-try
    (let [^js ctx (<p! (.newContext browser #js {:baseURL base-url}))]
      (swap! contexts conj ctx)
      (when cookie (<p! (.addCookies ctx (cookie-entry cookie))))
      (let [^js page (<p! (.newPage ctx))]
        (<p! (.goto page path))
        {:context ctx :page page}))))

(defn probe [^js page]
  (go-try (js->clj (<p! (.evaluate page "goobProbe()")) :keywordize-keys true)))

(defn wait-for! [^js page expr timeout]
  (.waitForFunction page expr nil #js {:timeout timeout :polling 100}))

(defn join!
  "The viewer's gesture: Space on the focused video, then a click on its
  surface if that did not start the page. Returns {:gesture :started-ms}."
  [^js page timeout]
  (go-try
    (let [video (.locator page "video")
          t0 (js/Date.now)]
      (<p! (.focus video))
      (<p! (.press (.-keyboard page) "Space"))
      (let [space? (try (<p! (wait-for! page "goobProbe().started" (:gesture-retry W))) true
                        (catch :default _ false))]
        (when-not space?
          (<p! (.click video)))
        (<p! (wait-for! page "goobProbe().started" timeout))
        {:gesture (if space? "space" "click") :started-ms (- (js/Date.now) t0)}))))

(defn- button [^js page label]
  (.getByRole page "button" #js {:name label :exact true}))

(defn play! [^js page] (.click (button page "▶ play")))
(defn pause! [^js page] (.click (button page "⏸ pause")))

(defn seek! [^js page pos]
  (.evaluate page (str "(() => { const s = document.querySelector('input.seek');"
                       " s.value = " (json pos) ";"
                       " s.dispatchEvent(new Event('change')); })()")))

(defn inject! [^js page delta]
  (.evaluate page (str "(() => { const v = document.querySelector('video');"
                       " v.currentTime = v.currentTime + " (json delta) "; })()")))

(defn wait-parked! [^js page]
  (wait-for! page (str "(() => { const p = goobProbe();"
                       " return p.paused && !p.started && p.counters.seeks > 0 && p.bufferAhead > 0; })()")
             (:ready-timeout W)))

(defn wait-live-park!
  "Waits until the parked frame sits within limit seconds of the room."
  [^js page limit timeout]
  (wait-for! page (str "(() => { const p = goobProbe();"
                       " return p.paused && !p.started && p.drift !== null && Math.abs(p.drift) <= " limit "; })()")
             timeout))

(defn wait-synced! [^js page settled timeout]
  (wait-for! page (str "(() => { const p = goobProbe();"
                       " return p.started && p.drift !== null && Math.abs(p.drift) <= " settled "; })()")
             timeout))

(defn host!
  "A room for the shared clip and a host who has started it."
  [browser base-url engine {:keys [cookie video-id]}]
  (go-try
    (let [room (<p! (api/make-room base-url cookie video-id))
          host (<? (open! browser base-url (:token room) {:nick "host" :cookie cookie}))
          joined (<? (join! (:page host) (t engine :start-within)))]
      {:room room :host host :host-joined joined})))

(defn pair!
  "host! plus, after (:host-head-start W), a viewer who joined and reached
  sync."
  [browser base-url engine fixture]
  (go-try
    (let [{:keys [room] :as scene} (<? (host! browser base-url engine fixture))
          _ (<! (a/timeout (:host-head-start W)))
          viewer (<? (open! browser base-url (:token room) {:nick "viewer"}))
          _ (<p! (wait-parked! (:page viewer)))
          joined (<? (join! (:page viewer) (t engine :start-within)))
          t0 (js/Date.now)]
      (<p! (wait-synced! (:page viewer) (t engine :settled) (t engine :sync-within)))
      (assoc scene :viewer viewer :joined joined :synced-ms (- (js/Date.now) t0)))))

(defn droppable!
  "Routes the context's room sockets through Playwright so drop! can cut
  them; while dropped, reconnects are refused. Returns the state atom."
  [^js ctx]
  (let [state (atom {:down? false :conns []})]
    (go-try
      (<p! (.routeWebSocket ctx #"/ws/room/"
                            (fn [^js ws]
                              (if (:down? @state)
                                (.close ws)
                                (swap! state update :conns conj [ws (.connectToServer ws)])))))
      state)))

(defn drop! [state]
  (let [{:keys [conns]} @state]
    (swap! state assoc :down? true :conns [])
    (js/Promise.all
     (clj->js (mapcat (fn [[^js ws ^js server]] [(.close server) (.close ws)]) conns)))))

(defn restore! [state]
  (swap! state assoc :down? false))

