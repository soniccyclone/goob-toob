(ns goob-toob.e2e.party
  (:require [clojure.string :as str]
            [cljs.core.async :as a :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.thresholds :refer [W]]))

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
  and page before navigation (network shaping hooks in there)."
  [^js browser base-url token {:keys [nick cookie before-goto]}]
  (go
    (let [^js ctx (<p! (.newContext browser #js {:baseURL base-url}))]
      (swap! contexts conj ctx)
      (when cookie (<p! (.addCookies ctx (cookie-entry cookie))))
      (<p! (.addInitScript ctx (str "localStorage.setItem('goob-nick', " (json nick) ")")))
      (let [^js page (<p! (.newPage ctx))]
        (when before-goto (a/<! (before-goto ctx page)))
        (<p! (.goto page (str "/watch/" token)))
        (<p! (.waitForFunction page "window.goobProbe && goobProbe().expected !== null"
                               nil #js {:timeout (:ready-timeout W)}))
        {:context ctx :page page :nick nick}))))

(defn open-page!
  "A fresh context at path, with the admin cookie when given."
  [^js browser base-url path {:keys [cookie]}]
  (go
    (let [^js ctx (<p! (.newContext browser #js {:baseURL base-url}))]
      (swap! contexts conj ctx)
      (when cookie (<p! (.addCookies ctx (cookie-entry cookie))))
      (let [^js page (<p! (.newPage ctx))]
        (<p! (.goto page path))
        {:context ctx :page page}))))

(defn probe [^js page]
  (go (js->clj (<p! (.evaluate page "goobProbe()")) :keywordize-keys true)))

(defn wait-for! [^js page expr timeout]
  (.waitForFunction page expr nil #js {:timeout timeout :polling 100}))

(defn join!
  "The viewer's gesture: Space on the focused video, then a click on its
  surface if that did not start the page. Returns {:gesture :started-ms}."
  [^js page timeout]
  (go
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
