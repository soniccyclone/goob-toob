(ns goob-toob.e2e.party
  (:require [clojure.string :as str]
            [cljs.core.async :as a :refer [<!]]
            [goob-toob.e2e.async :refer-macros [go-try <?]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.thresholds :refer [t T W]]))

(defonce ^:private contexts (atom []))

(defn close-all! []
  (let [cs @contexts]
    (reset! contexts [])
    (js/Promise.all (clj->js (map (fn [^js c] (.catch (.close c) (fn [_] nil))) cs)))))

(defn- cookie-entry [cookie]
  (let [[k v] (str/split cookie #"=" 2)]
    #js [#js {:name k :value v :domain "127.0.0.1" :path "/"}]))

(defn- json [x] (js/JSON.stringify (clj->js x)))

(def ^:private welcomed "window.goobProbe && goobProbe().expected !== null")

(defn- nick-input [^js page]
  (.getByPlaceholder page "your name"))

(defn- parse-json [s]
  (try (js/JSON.parse s) (catch :default _ nil)))

(defn- watch-traffic!
  [^js page]
  (let [seen (atom [])]
    (.on page "request"
         (fn [^js req]
           (when (str/includes? (.url req) "/api/clientlog")
             (when-let [^js body (some-> (.postData req) parse-json)]
               (swap! seen conj {:at (js/Date.now) :log (.-msg body)})))))
    (.on page "websocket"
         (fn [^js ws]
           (.on ws "framereceived"
                (fn [^js frame]
                  (when-let [^js msg (some-> (.-payload frame) str parse-json)]
                    (swap! seen conj {:at (js/Date.now) :frame (.-type msg)}))))))
    seen))

(defn wait-seen!
  "Waits, without evaluating anything in the page, until pred holds on an
  entry of seen (open!'s :seen: {:at ms :log msg} for each clientlog line,
  {:at ms :frame type} for each socket frame received). Returns the first
  matching entry, or nil after timeout ms."
  [seen pred timeout]
  (go-try
    (let [deadline (+ (js/Date.now) timeout)]
      (loop []
        (if-let [e (first (filter pred @seen))]
          e
          (when (< (js/Date.now) deadline)
            (<! (a/timeout (:sample-every W)))
            (recur)))))))

(defn log-line? [prefix]
  (fn [{:keys [log]}] (boolean (and log (str/starts-with? log prefix)))))

(defn frame? [type]
  (fn [{:keys [frame]}] (= type frame)))

(defn open!
  "Opens a fresh context on /watch/<token> as nick, with the admin cookie when
  given, and waits for the room's welcome. With :nick nil localStorage stays
  empty, so the page shows the nick prompt; open! then waits for the prompt
  instead and enter-nick! joins. With :hands-off? the welcome is awaited
  from the socket frames, not by evaluating in the page: Chromium's
  evaluate runs with userGesture, which hands the page sticky activation
  and would let an autostart play with sound under the strict policy.
  before-goto receives the context and page before navigation and returns
  a channel; its value comes back as :hook. :seen is the page's traffic
  for wait-seen!."
  [^js browser base-url token {:keys [nick cookie before-goto hands-off?]}]
  (go-try
    (let [^js ctx (<p! (.newContext browser #js {:baseURL base-url}))]
      (swap! contexts conj ctx)
      (when cookie (<p! (.addCookies ctx (cookie-entry cookie))))
      (when nick
        (<p! (.addInitScript ctx (str "localStorage.setItem('goob-nick', " (json nick) ")"))))
      (let [^js page (<p! (.newPage ctx))
            seen (watch-traffic! page)]
        (let [hook (when before-goto (<? (before-goto ctx page)))]
          (<p! (.goto page (str "/watch/" token)))
          (cond
            (not nick) (<p! (.waitFor (nick-input page) #js {:timeout (:ready-timeout W)}))
            hands-off? (when-not (<? (wait-seen! seen (frame? "welcome") (:ready-timeout W)))
                         (throw (js/Error. "no welcome frame")))
            :else (<p! (.waitForFunction page welcomed nil #js {:timeout (:ready-timeout W)})))
          {:context ctx :page page :nick nick :hook hook :seen seen})))))

(defn enter-nick!
  "Types nick into the prompt, clicks \"join the goo\" and waits for the
  room's welcome."
  [^js page nick]
  (go-try
    (<p! (.fill (nick-input page) nick))
    (<p! (.click (.getByRole page "button" #js {:name "join the goo" :exact true})))
    (<p! (.waitForFunction page welcomed nil #js {:timeout (:ready-timeout W)}))
    nick))

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

(defn policy
  "The project's autoplay policy: :strict or :lenient where the launch
  forces one, :record for WebKit, whose outcome is only recorded."
  [^js info]
  (let [project (.. info -project -name)]
    (cond (str/ends-with? project "-lenient") :lenient
          (str/starts-with? project "webkit") :record
          :else :strict)))

(defn probe [^js page]
  (go-try (js->clj (<p! (.evaluate page "goobProbe()")) :keywordize-keys true)))

(defn wait-for! [^js page expr timeout]
  (.waitForFunction page expr nil #js {:timeout timeout :polling 100}))

(def ^:private start-settled
  "Started with no autostart attempt still in flight: begin! sets started
  before play() resolves, and the outcome only lands when it does."
  "(() => { const p = goobProbe(); return (p.started && !p.starting) || p.autostart === 'blocked' || !p.playing; })()")

(defn touch!
  "A harmless real click (the chat input): gives the document sticky
  activation without pressing play."
  [^js page]
  (.click (.getByPlaceholder page "say something gooey…")))

(defn touch-start-within []
  (+ (:pong-every W) (:autostart-within T)))

(defn join!
  "The viewer joins. In a playing room the page autostarts on its own when
  the browser allows sound; join! waits up to :autostart-within for that
  ({:gesture \"autostart\"}). When the autostart was blocked it touches
  the page and waits for the next update to start it ({:gesture
  \"touch\"}). Otherwise, or if that did not start it, the gesture: Space
  on the focused video, then a click on its surface. Returns {:gesture
  :autostart :started-ms}."
  [^js page timeout]
  (go-try
    (let [video (.locator page "video")
          t0 (js/Date.now)
          started? #(go-try (try (<p! (wait-for! page "goobProbe().started" %)) true
                                 (catch :default _ false)))
          _ (try (<p! (wait-for! page start-settled (:autostart-within T))) (catch :default _ nil))
          auto (<? (probe page))
          touched? (and (not (:started auto)) (= "blocked" (:autostart auto))
                        (do (<p! (touch! page))
                            (<? (started? (touch-start-within)))))]
      (cond
        (:started auto) {:gesture "autostart" :autostart (:autostart auto) :started-ms (- (js/Date.now) t0)}
        touched? {:gesture "touch" :autostart (:autostart (<? (probe page))) :started-ms (- (js/Date.now) t0)}
        :else
        (do
          (<p! (.focus video))
          (<p! (.press (.-keyboard page) "Space"))
          (let [space? (<? (started? (:gesture-retry W)))]
            (when-not space?
              (<p! (.click video)))
            (<p! (wait-for! page "goobProbe().started" timeout))
            {:gesture (if space? "space" "click") :autostart (:autostart auto)
             :started-ms (- (js/Date.now) t0)}))))))

(defn- button [^js page label]
  (.getByRole page "button" #js {:name label :exact true}))

(defn play! [^js page] (.click (button page "▶ play")))
(defn pause! [^js page] (.click (button page "⏸ pause")))
(defn next! [^js page] (.click (button page "next ⏭")))
(defn prev! [^js page] (.click (button page "⏮ prev")))

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

(defn wait-landed!
  "Waits until the element has landed after a start: started, not paused and
  past the landing stall (see thresholds :landed for what a landing is)."
  [^js page timeout]
  (wait-for! page (str "(() => { const p = goobProbe();"
                       " return p.started && !p.paused && !p.buffering; })()")
             timeout))

(defn wait-synced! [^js page settled timeout]
  (wait-for! page (str "(() => { const p = goobProbe();"
                       " return p.started && p.drift !== null && Math.abs(p.drift) <= " settled "; })()")
             timeout))

(defn host!
  "A room for the shared clip (or :room, one already made, for a queue)
  and a host who has started it."
  [browser base-url engine {:keys [cookie video-id room]}]
  (go-try
    (let [room (or room (<p! (api/make-room base-url cookie video-id)))
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
          joined (<? (join! (:page viewer) (t engine :start-within)))
          t0 (js/Date.now)]
      (<p! (wait-synced! (:page viewer) (t engine :landed) (t engine :sync-within)))
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

