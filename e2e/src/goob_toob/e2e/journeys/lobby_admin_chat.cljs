(ns goob-toob.e2e.journeys.lobby-admin-chat
  (:refer-clojure :exclude [test])
  (:require ["@playwright/test" :refer [test expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.core.async :as a :refer [<!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.async :refer-macros [<?]]
            [goob-toob.e2e.journey :refer [defjourney]]
            [goob-toob.e2e.metrics :as m]
            [goob-toob.e2e.party :as party]
            [goob-toob.e2e.thresholds :refer [t W]]))

(defn- row-state [^js page title]
  (.evaluate page (str "(() => { const row = [...document.querySelectorAll('tr')]"
                       ".find(r => r.cells[0] && r.cells[0].textContent === " (js/JSON.stringify title) ");"
                       " if (!row) return null;"
                       " const bar = row.cells[1].querySelector('.bar i');"
                       " return {status: row.cells[1].firstChild.textContent,"
                       "         width: bar ? parseFloat(bar.style.width) : null}; })()")))

(defn- in-order? [seen order]
  (let [idx (map #(.indexOf (clj->js order) %) seen)]
    (and (seq idx) (every? #(>= % 0) idx) (apply <= idx))))

(defn- lobby-card [^js page title]
  (.locator page ".card h3" #js {:hasText title}))

(defjourney "upload to lobby" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie upload-clip]} (api/fixture info)
        title (str "upload-" browser-name "-" (.-retry info))
        file (path/join (fs/mkdtempSync (path/join (os/tmpdir) "goob-up-")) (str title ".mp4"))
        _ (fs/copyFileSync upload-clip file)
        {^js admin :page} (<? (party/open-page! browser base-url "/admin" {:cookie cookie}))
        _ (.on admin "dialog" (fn [^js d] (.accept d)))
        _ (<p! (.setInputFiles (.locator admin "input[type=file]") file))
        t0 (js/Date.now)
        rows (loop [out []]
               (let [r (js->clj (<p! (row-state admin title)) :keywordize-keys true)
                     at (- (js/Date.now) t0)
                     out (if r (conj out (assoc r :at at)) out)]
                 (if (or (= "ready" (:status r)) (= "failed" (:status r)) (> at (t browser-name :transcode-within)))
                   out
                   (do (<! (a/timeout (:sample-every W))) (recur out)))))
        statuses (vec (dedupe (map :status rows)))
        widths (vec (distinct (keep :width rows)))
        ready-at (m/first-at #(= "ready" (:status %)) rows)
        {^js lobby :page} (<? (party/open-page! browser base-url "/" {}))
        _ (<p! (-> (expect (.first (.locator lobby ".grid > *"))) (.toBeVisible)))
        card-before (<p! (.count (lobby-card lobby title)))
        row (.filter (.locator admin "tr") #js {:hasText title})
        _ (<p! (.click (.getByRole row "button" #js {:name "make room"})))
        _ (<p! (-> (expect (.filter (.locator admin "tr") #js {:has (.locator admin "a[href^='/watch/']") :hasText title}))
                   (.toHaveCount 1)))
        _ (<p! (.reload lobby))
        _ (<p! (-> (expect (lobby-card lobby title)) (.toBeVisible)))
        room-row (.filter (.locator admin "tr") #js {:has (.locator admin "a[href^='/watch/']") :hasText title})
        _ (<p! (.click (.getByRole room-row "button" #js {:name "delete"})))
        _ (<p! (-> (expect room-row) (.toHaveCount 0)))
        _ (<p! (.reload lobby))
        _ (<p! (-> (expect (.first (.locator lobby ".grid > *"))) (.toBeVisible)))
        card-after (<p! (.count (lobby-card lobby title)))]
    (<p! (m/record! info {} rows {:statuses statuses :widths widths :ready-at ready-at
                                  :card-before card-before :card-after card-after}))
    (-> (expect (last statuses) "final library status") (.toBe "ready"))
    (-> (expect (in-order? statuses ["uploaded" "transcoding" "ready"]) (str "statuses in order: " statuses)) (.toBe true))
    (-> (expect statuses "saw transcoding") (.toContain "transcoding"))
    (-> (expect (count widths) "distinct progress bar widths") (.toBeGreaterThan 1))
    (-> (expect ready-at "ms until ready") (.toBeLessThanOrEqual (t browser-name :transcode-within)))
    (-> (expect card-before "lobby cards before the room") (.toBe 0))
    (-> (expect card-after "lobby cards after the room closed") (.toBe 0))))

(defjourney "chat round trip" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie video-id]} (api/fixture info)
        room (<p! (api/make-room base-url cookie video-id))
        host (<? (party/open! browser base-url (:token room) {:nick "host" :cookie cookie}))
        viewer (<? (party/open! browser base-url (:token room) {:nick "viewer"}))
        ^js hp (:page host) ^js vp (:page viewer)
        input (.getByPlaceholder hp "say something gooey…")
        fill (:chat-fill W)
        _ (loop [i 0]
            (when (< i fill)
              (<p! (.fill input (str "filler " i)))
              (<p! (.press input "Enter"))
              (recur (inc i))))
        bubbles (.locator vp ".chat .log .bubble")
        _ (<p! (-> (expect bubbles) (.toHaveCount fill)))
        _ (<p! (.evaluate vp "document.querySelector('.chat .log').scrollTop = 0"))
        pill (.locator vp ".new-msgs")
        _ (<p! (.fill input "the probe message"))
        t0 (js/Date.now)
        _ (<p! (.press input "Enter"))
        _ (<p! (party/wait-for! vp "[...document.querySelectorAll('.chat .bubble')].some(b => b.textContent.includes('the probe message'))"
                                (:ready-timeout W)))
        chat-ms (- (js/Date.now) t0)
        who (<p! (.textContent (.locator (.last bubbles) ".who")))
        pill-shown (<p! (.isVisible pill))
        _ (<p! (.click pill))
        _ (<p! (-> (expect pill) (.toBeHidden)))
        at-bottom (<p! (.evaluate vp "(() => { const l = document.querySelector('.chat .log'); return l.scrollHeight - l.scrollTop - l.clientHeight < 40; })()"))]
    (<p! (m/record! info {} [] {:chat-ms chat-ms :who who :pill-shown pill-shown :at-bottom at-bottom}))
    (-> (expect chat-ms "ms from send to the viewer's bubble") (.toBeLessThanOrEqual (t browser-name :chat-within)))
    (-> (expect who "bubble nick") (.toBe "host"))
    (-> (expect pill-shown "new-messages pill while scrolled up") (.toBe true))
    (-> (expect at-bottom "log at the bottom after the pill") (.toBe true))))
