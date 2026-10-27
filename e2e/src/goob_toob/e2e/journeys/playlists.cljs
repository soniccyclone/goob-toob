(ns goob-toob.e2e.journeys.playlists
  "The room's queue on the watch page: the server switches the item
  (auto-advance, next/prev/jump, an admin edit) and every page swaps its
  source and lands on the new video through the first-load start path.
  The fixture's clip is the first item; the setup's extra clips (clip-b,
  clip-c) are queued after it."
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

(defn- json [x] (js/JSON.stringify (clj->js x)))

(defn- on-video [id]
  (str "goobProbe().vid === " (json id)))

(defn- playing-on
  "Started, unpaused, past the landing stall and moving on video id."
  [id]
  (str "(() => { const p = goobProbe(); return p.vid === " (json id)
       " && p.started && !p.paused && !p.buffering && p.currentTime > 0; })()"))

(def ^:private blank-stage
  "No video: the probe says so and the stage wears its empty state."
  (str "(() => { const p = goobProbe(); return p.vid === null && !p.started"
       " && document.querySelector('.stage-wrap').classList.contains('empty'); })()"))

(defn- rows-expr [n]
  (str "document.querySelectorAll('.queue .qrow').length === " n))

(defn- first-row-expr [title]
  (str "(() => { const r = document.querySelector('.queue .qrow .qtitle');"
       " return !!r && r.textContent === " (json title) "; })()"))

(defn- wait-ms!
  "Waits for expr on page; the ms it took, or nil after timeout."
  [^js page expr timeout]
  (go-try
    (let [t0 (js/Date.now)]
      (try (<p! (party/wait-for! page expr timeout))
           (- (js/Date.now) t0)
           (catch :default _ nil)))))

(defn- queue-rows [^js page]
  (go-try
    (js->clj (<p! (.evaluate page (str "[...document.querySelectorAll('.queue .qrow')]"
                                       ".map(r => ({title: r.querySelector('.qtitle').textContent,"
                                       " now: r.classList.contains('now')}))")))
             :keywordize-keys true)))

(defn- now-title [rows] (:title (first (filter :now rows))))

(defn- queued-room!
  "A room on the fixture's clip with the first (dec n) extra clips queued
  after it; opts: :title."
  [base cookie {:keys [video-id extra]} n & {:keys [title]}]
  (go-try
    (let [room (<p! (api/make-room base cookie video-id {:title title}))]
      (loop [vs (take (dec n) extra)]
        (when-let [v (first vs)]
          (let [{:keys [status data]} (<p! (api/add-item! base cookie (:id room) (:id v)))]
            (when-not (= 201 status)
              (throw (js/Error. (str "add-item failed: " (pr-str data))))))
          (recur (rest vs))))
      room)))

(defn- raw-cmd
  "A bare socket from the page sends one cmd op, as a viewer without
  control could by hand."
  [token op]
  (str "new Promise(res => {"
       " const ws = new WebSocket(`ws://${location.host}/ws/room/" token "?nick=intruder`);"
       " ws.onopen = () => { ws.send(JSON.stringify({type: 'cmd', op: " (json op) "}));"
       "                     setTimeout(() => { ws.close(); res(true); }, 200); };"
       " ws.onerror = () => res(false); })"))

(defn- hold-video!
  "Polls the room row for ms; the distinct current video ids seen."
  [base cookie room-id ms]
  (go-try
    (let [rows (<? (m/sample-until #(api/room base cookie room-id) (constantly false) ms))]
      (vec (distinct (map :video-id rows))))))

(defjourney "the room advances at the end of the video" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie video-id duration extra] :as fx} (api/fixture info)
        second-id (:id (first extra))
        room (<? (queued-room! base-url cookie fx 2))
        {:keys [host viewer]} (<? (party/pair! browser base-url browser-name (assoc fx :room room)))
        hp (:page host) vp (:page viewer)
        lead (:end-lead W)
        _ (<p! (party/seek! hp (- duration lead)))
        ;; the room clock passes the end `lead` seconds after the seek
        due (* 1000 lead)
        series (<? (m/sample vp (:advance-window W) {:live #(api/live base-url cookie (:id room)) :host hp}))
        landed (t browser-name :landed)
        switch-at (m/first-at #(= second-id (:vid %)) series)
        host-switch-at (m/first-at #(= second-id (get-in % [:host :vid])) series)
        playing-at (m/first-at #(and (= second-id (:vid %)) (:started %) (not (:paused %))
                                     (not (:buffering %)) (pos? (:currentTime %)))
                               series)
        settled-at (m/first-at #(and (= second-id (:vid %)) (:started %) (number? (:drift %))
                                     (<= (m/abs* (:drift %)) landed))
                               series)
        before (last (filter #(= video-id (:vid %)) series))
        seeks-across (- (get-in (last series) [:counters :seeks] 0) (get-in before [:counters :seeks] 0))
        row (<p! (api/room base-url cookie (:id room)))
        summary (m/summarize series settled-at)
        end (last series)]
    (<p! (m/record! info summary series
                    {:due due :switch-at switch-at :host-switch-at host-switch-at :playing-at playing-at
                     :settled-at settled-at :seeks-across seeks-across :server-vid (:video-id row)
                     :autostart (:autostart end) :muted (:muted end)}))
    (-> (expect switch-at "viewer on the second video") (.not.toBeNull))
    (-> (expect host-switch-at "host on the second video") (.not.toBeNull))
    (-> (expect (- switch-at due) "ms from the clock passing the end to the viewer's switch")
        (.toBeLessThanOrEqual (t browser-name :advance-slack)))
    (-> (expect (- host-switch-at due) "ms from the clock passing the end to the host's switch")
        (.toBeLessThanOrEqual (t browser-name :advance-slack)))
    (-> (expect playing-at "viewer playing on the new video") (.not.toBeNull))
    (-> (expect (- playing-at switch-at) "ms from the switch to playing on the new video")
        (.toBeLessThanOrEqual (t browser-name :switch-within)))
    (-> (expect settled-at "viewer settled on the new video") (.not.toBeNull))
    (-> (expect (- settled-at switch-at) "ms from the switch to settled")
        (.toBeLessThanOrEqual (t browser-name :sync-within)))
    (-> (expect seeks-across "seeks across the switch") (.toBeLessThanOrEqual (t browser-name :switch-seeks)))
    (-> (expect (:video-id row) "server's current video") (.toBe second-id))))

(defjourney "next and prev switch every viewer" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie video-id extra] :as fx} (api/fixture info)
        second-id (:id (first extra))
        room (<? (queued-room! base-url cookie fx 2))
        {:keys [host viewer]} (<? (party/pair! browser base-url browser-name (assoc fx :room room)))
        hp (:page host) vp (:page viewer)
        within (t browser-name :switch-within)
        ;; a viewer's next without control moves nothing
        sent? (<p! (.evaluate vp (raw-cmd (:token room) "next")))
        held (<? (hold-video! base-url cookie (:id room) (:skip-hold W)))
        ;; host next
        _ (<p! (party/next! hp))
        v-next (<? (wait-ms! vp (on-video second-id) within))
        h-next (<? (wait-ms! hp (on-video second-id) within))
        v-next-playing (<? (wait-ms! vp (playing-on second-id) within))
        first-next (<? (party/probe vp))
        live-next (<p! (api/live base-url cookie (:id room)))
        ;; host prev
        _ (<p! (party/prev! hp))
        v-prev (<? (wait-ms! vp (on-video video-id) within))
        h-prev (<? (wait-ms! hp (on-video video-id) within))
        v-prev-playing (<? (wait-ms! vp (playing-on video-id) within))
        first-prev (<? (party/probe vp))
        live-prev (<p! (api/live base-url cookie (:id room)))
        series (<? (m/sample vp (:switch-window W)))
        settled-at (m/first-at #(and (number? (:drift %)) (<= (m/abs* (:drift %)) (t browser-name :landed))) series)
        summary (m/summarize series settled-at)]
    (<p! (m/record! info summary series
                    {:raw-sent sent? :held held
                     :v-next v-next :h-next h-next :v-next-playing v-next-playing :next-from (:currentTime first-next)
                     :v-prev v-prev :h-prev h-prev :v-prev-playing v-prev-playing :prev-from (:currentTime first-prev)
                     :settled-at settled-at}))
    (-> (expect sent? "raw socket sent its next") (.toBe true))
    (-> (expect (clj->js held) "current video while a viewer without control pressed next") (.toEqual #js [video-id]))
    (doseq [[label ms] [["viewer on the second video after next" v-next]
                        ["host on the second video after next" h-next]
                        ["viewer playing the second video" v-next-playing]
                        ["viewer on the first video after prev" v-prev]
                        ["host on the first video after prev" h-prev]
                        ["viewer playing the first video" v-prev-playing]]]
      (-> (expect ms label) (.not.toBeNull)))
    (-> (expect (:currentTime first-next) "currentTime at the first playing sample after next")
        (.toBeLessThan (t browser-name :from-zero)))
    (-> (expect (:playing live-next) "server playing after next") (.toBe true))
    (-> (expect (:currentTime first-prev) "currentTime at the first playing sample after prev")
        (.toBeLessThan (t browser-name :from-zero)))
    (-> (expect (:playing live-prev) "server playing after prev") (.toBe true))
    (-> (expect settled-at "ms until settled after prev") (.not.toBeNull))))

(defjourney "jump from the queue" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie extra] :as fx} (api/fixture info)
        third (second extra)
        room (<? (queued-room! base-url cookie fx 3))
        _ (<p! (api/set-control! base-url cookie (:id room) true))
        {:keys [host viewer]} (<? (party/pair! browser base-url browser-name (assoc fx :room room)))
        hp (:page host) vp (:page viewer)
        within (t browser-name :switch-within)
        rows-before (<? (queue-rows vp))
        _ (<p! (.click (.nth (.locator vp ".queue .qrow") 2)))
        v-jump (<? (wait-ms! vp (on-video (:id third)) within))
        h-jump (<? (wait-ms! hp (on-video (:id third)) within))
        v-playing (<? (wait-ms! vp (playing-on (:id third)) within))
        rows-v (<? (queue-rows vp))
        rows-h (<? (queue-rows hp))
        live (<p! (api/live base-url cookie (:id room)))
        series (<? (m/sample vp (:switch-window W)))
        settled-at (m/first-at #(and (number? (:drift %)) (<= (m/abs* (:drift %)) (t browser-name :landed))) series)
        summary (m/summarize series settled-at)]
    (<p! (m/record! info summary series
                    {:rows-before (count rows-before) :now-before (now-title rows-before)
                     :v-jump v-jump :h-jump h-jump :v-playing v-playing
                     :now-viewer (now-title rows-v) :now-host (now-title rows-h) :settled-at settled-at}))
    (-> (expect (count rows-before) "queue rows") (.toBe 3))
    (-> (expect (now-title rows-before) "current row before the jump") (.toBe (:title fx)))
    (-> (expect v-jump "viewer on the third video") (.not.toBeNull))
    (-> (expect h-jump "host on the third video") (.not.toBeNull))
    (-> (expect v-playing "viewer playing the third video") (.not.toBeNull))
    (-> (expect (now-title rows-v) "viewer's current row") (.toBe (:title third)))
    (-> (expect (now-title rows-h) "host's current row") (.toBe (:title third)))
    (-> (expect (:playing live) "server playing") (.toBe true))
    (-> (expect settled-at "ms until settled after the jump") (.not.toBeNull))))

(defjourney "end of the queue pauses everyone" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie video-id duration] :as fx} (api/fixture info)
        {:keys [room host viewer]} (<? (party/pair! browser base-url browser-name fx))
        hp (:page host) vp (:page viewer)
        lead (:end-lead W)
        _ (<p! (party/seek! hp (- duration lead)))
        due (* 1000 lead)
        series (<? (m/sample vp (:end-window W) {:live #(api/live base-url cookie (:id room)) :host hp}))
        ended-at (m/first-at #(and (:ended %) (:paused %)) series)
        host-ended-at (m/first-at #(and (get-in % [:host :ended]) (get-in % [:host :paused])) series)
        hint-v (<p! (.isVisible (.getByText vp "that's the end of the queue")))
        hint-h (<p! (.isVisible (.getByText hp "that's the end of the queue")))
        live-end (<p! (api/live base-url cookie (:id room)))
        ;; next at the end moves nothing
        _ (<p! (party/next! hp))
        held (<? (hold-video! base-url cookie (:id room) (:skip-hold W)))
        live-held (<p! (api/live base-url cookie (:id room)))
        ;; a seek and a play resume the room (the play goes out at the
        ;; host's element position, so the seek must have landed first)
        _ (<p! (party/seek! hp (:seek-jump W)))
        seek-landed (<? (wait-ms! hp (str "Math.abs(goobProbe().currentTime - " (:seek-jump W) ") < "
                                          (t browser-name :landed))
                                  (t browser-name :sync-within)))
        _ (<p! (party/play! hp))
        resumed (<? (wait-ms! vp "(() => { const p = goobProbe(); return p.playing && p.started && !p.paused && !p.ended; })()"
                              (t browser-name :sync-within)))
        synced? (try (<p! (party/wait-synced! vp (t browser-name :landed) (t browser-name :sync-within))) true
                     (catch :default _ false))
        end (last series)
        summary (m/summarize series 0)]
    (<p! (m/record! info summary series
                    {:due due :ended-at ended-at :host-ended-at host-ended-at :hint-viewer hint-v :hint-host hint-h
                     :server-playing (:playing live-end) :server-pos (:pos live-end) :duration duration
                     :held held :held-playing (:playing live-held) :seek-landed seek-landed
                     :resumed resumed :synced synced?
                     :vid (:vid end)}))
    (-> (expect ended-at "viewer paused at the end of the queue") (.not.toBeNull))
    (-> (expect host-ended-at "host paused at the end of the queue") (.not.toBeNull))
    (-> (expect (- ended-at due) "ms from the clock passing the end to the viewer's pause")
        (.toBeLessThanOrEqual (t browser-name :advance-slack)))
    (-> (expect (- host-ended-at due) "ms from the clock passing the end to the host's pause")
        (.toBeLessThanOrEqual (t browser-name :advance-slack)))
    (-> (expect (:ended end) "viewer's ended flag at the end of the window") (.toBe true))
    (-> (expect hint-v "viewer's hint says end of the queue") (.toBe true))
    (-> (expect hint-h "host's hint says end of the queue") (.toBe true))
    (-> (expect (:playing live-end) "server playing at the end") (.toBe false))
    (-> (expect (:pos live-end) "server position at the end") (.toBeCloseTo duration 2))
    (-> (expect (clj->js held) "current video while next was pressed at the end") (.toEqual #js [video-id]))
    (-> (expect (:playing live-held) "server playing after next at the end") (.toBe false))
    (-> (expect resumed "viewer resumed after the host's seek and play") (.not.toBeNull))
    (-> (expect synced? "viewer settled after the resume") (.toBe true))))

(defjourney "queue edits reach the room live" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie video-id extra] :as fx} (api/fixture info)
        [b c] extra
        {:keys [room host viewer]} (<? (party/pair! browser base-url browser-name fx))
        hp (:page host) vp (:page viewer)
        within (t browser-name :chat-within)
        both! (fn [expr]
                (go-try
                  (let [v (<? (wait-ms! vp expr within))
                        h (<? (wait-ms! hp expr within))]
                    {:viewer v :host h})))
        _ (<p! (api/add-item! base-url cookie (:id room) (:id b)))
        added (<? (both! (rows-expr 2)))
        _ (<p! (api/add-item! base-url cookie (:id room) (:id c)))
        added-2 (<? (both! (rows-expr 3)))
        _ (<p! (api/remove-item! base-url cookie (:id room) (:id c)))
        removed (<? (both! (rows-expr 2)))
        _ (<p! (api/reorder! base-url cookie (:id room) [(:id b) video-id]))
        reordered (<? (both! (first-row-expr (:title b))))
        rows (<? (queue-rows vp))
        probe (<? (party/probe vp))
        live (<p! (api/live base-url cookie (:id room)))]
    (<p! (m/record! info {} [] {:added added :added-2 added-2 :removed removed :reordered reordered
                                :rows rows :vid (:vid probe) :server-playing (:playing live)}))
    (doseq [[label {:keys [viewer host]}] [["second item added" added] ["third item added" added-2]
                                           ["third item removed" removed] ["queue reordered" reordered]]]
      (-> (expect viewer (str label ": viewer's queue card")) (.not.toBeNull))
      (-> (expect host (str label ": host's queue card")) (.not.toBeNull)))
    (-> (expect (clj->js (mapv :title rows)) "rows after the edits") (.toEqual (clj->js [(:title b) (:title fx)])))
    (-> (expect (now-title rows) "current row after the edits") (.toBe (:title fx)))
    (-> (expect (:vid probe) "viewer still on the first video") (.toBe video-id))
    (-> (expect (:started probe) "viewer still started") (.toBe true))
    (-> (expect (:playing live) "server still playing") (.toBe true))))

(defjourney "removing the current item moves the room" [{:keys [browser browser-name base-url]} info]
  ;; Removing the current item (the room moves to its neighbour) and the
  ;; only item (the room empties) are the queue-removal server change;
  ;; until it lands the server answers 409 and this journey skips itself.
  (let [{:keys [cookie video-id extra] :as fx} (api/fixture info)
        b (first extra)
        room (<? (queued-room! base-url cookie fx 2))
        {:keys [host viewer]} (<? (party/pair! browser base-url browser-name (assoc fx :room room)))
        hp (:page host) vp (:page viewer)
        within (t browser-name :switch-within)
        {:keys [status data]} (<p! (api/remove-item! base-url cookie (:id room) video-id))]
    (.skip test (= 409 status) (str "removing the current item needs the queue-removal server branch: " (:error data)))
    (let [v-moved (<? (wait-ms! vp (on-video (:id b)) within))
          h-moved (<? (wait-ms! hp (on-video (:id b)) within))
          v-playing (<? (wait-ms! vp (playing-on (:id b)) within))
          rows-moved (<? (queue-rows vp))
          row-moved (<p! (api/room base-url cookie (:id room)))
          ;; the only item: a blank stage
          {status-2 :status} (<p! (api/remove-item! base-url cookie (:id room) (:id b)))
          v-blank (<? (wait-ms! vp blank-stage within))
          h-blank (<? (wait-ms! hp blank-stage within))
          title-v (<p! (.textContent (.first (.locator vp "h3"))))
          empty-v (<p! (.isVisible (.getByText vp "the queue is empty")))
          play-disabled (<p! (.isDisabled (.getByRole hp "button" #js {:name "▶ play" :exact true})))
          row-blank (<p! (api/room base-url cookie (:id room)))
          live-blank (<p! (api/live base-url cookie (:id room)))
          ;; an item added back is current and paused; the host starts it
          {status-3 :status} (<p! (api/add-item! base-url cookie (:id room) video-id))
          v-back (<? (wait-ms! vp (on-video video-id) within))
          h-back (<? (wait-ms! hp (on-video video-id) within))
          back (<? (party/probe vp))
          live-back (<p! (api/live base-url cookie (:id room)))
          _ (<p! (party/play! hp))
          v-started (<? (wait-ms! vp (playing-on video-id) (t browser-name :autostart-within)))
          synced? (try (<p! (party/wait-synced! vp (t browser-name :landed) (t browser-name :sync-within))) true
                       (catch :default _ false))]
      (<p! (m/record! info {} [] {:v-moved v-moved :h-moved h-moved :v-playing v-playing
                                  :rows-moved rows-moved :server-vid-moved (:video-id row-moved)
                                  :status-2 status-2 :v-blank v-blank :h-blank h-blank :title title-v
                                  :empty-text empty-v :play-disabled play-disabled
                                  :server-vid-blank (:video-id row-blank) :server-playing-blank (:playing live-blank)
                                  :status-3 status-3 :v-back v-back :h-back h-back
                                  :back-playing (:playing back) :back-paused (:paused back)
                                  :server-playing-back (:playing live-back) :v-started v-started :synced synced?}))
      (-> (expect v-moved "viewer on the next video after the current was removed") (.not.toBeNull))
      (-> (expect h-moved "host on the next video after the current was removed") (.not.toBeNull))
      (-> (expect v-playing "viewer playing the next video") (.not.toBeNull))
      (-> (expect (clj->js (mapv :title rows-moved)) "rows after the removal") (.toEqual (clj->js [(:title b)])))
      (-> (expect (now-title rows-moved) "current row after the removal") (.toBe (:title b)))
      (-> (expect (:video-id row-moved) "server's current video after the removal") (.toBe (:id b)))
      (-> (expect status-2 "removing the only item") (.toBe 200))
      (-> (expect v-blank "viewer on a blank stage") (.not.toBeNull))
      (-> (expect h-blank "host on a blank stage") (.not.toBeNull))
      (-> (expect title-v "viewer's title") (.toBe "nothing queued"))
      (-> (expect empty-v "viewer's queue card says empty") (.toBe true))
      (-> (expect play-disabled "host's play button off") (.toBe true))
      (-> (expect (:video-id row-blank) "server's current video with an empty queue") (.toBeNull))
      (-> (expect (:playing live-blank) "server playing with an empty queue") (.toBe false))
      (-> (expect status-3 "adding an item back") (.toBe 201))
      (-> (expect v-back "viewer on the item added back") (.not.toBeNull))
      (-> (expect h-back "host on the item added back") (.not.toBeNull))
      (-> (expect (:playing back) "room playing after the add") (.toBe false))
      (-> (expect (:paused back) "viewer paused after the add") (.toBe true))
      (-> (expect (:playing live-back) "server playing after the add") (.toBe false))
      (-> (expect v-started "viewer playing after the host's play") (.not.toBeNull))
      (-> (expect synced? "viewer settled after the host's play") (.toBe true)))))

(defjourney "a reconnect across a switch lands on the new video" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie extra] :as fx} (api/fixture info)
        b (first extra)
        room (<? (queued-room! base-url cookie fx 2))
        {:keys [host]} (<? (party/host! browser base-url browser-name (assoc fx :room room)))
        _ (<! (a/timeout (:host-head-start W)))
        viewer (<? (party/open! browser base-url (:token room)
                                {:nick "viewer"
                                 :before-goto (fn [ctx _] (party/droppable! ctx))}))
        hp (:page host) vp (:page viewer)
        ^js vctx (:context viewer)
        _ (<? (party/join! vp (t browser-name :start-within)))
        _ (<p! (party/wait-synced! vp (t browser-name :landed) (t browser-name :sync-within)))
        state (:hook viewer)
        _ (<p! (.setOffline vctx true))
        _ (<p! (party/drop! state))
        _ (<p! (party/next! hp))
        h-next (<? (wait-ms! hp (on-video (:id b)) (t browser-name :switch-within)))
        _ (<! (a/timeout (:offline-for W)))
        offline (<? (party/probe vp))
        _ (party/restore! state)
        _ (<p! (.setOffline vctx false))
        landed (<? (wait-ms! vp (on-video (:id b)) (t browser-name :resync-within)))
        synced (<? (wait-ms! vp (str "(() => { const p = goobProbe(); return p.vid === " (json (:id b))
                                     " && p.started && p.drift !== null && Math.abs(p.drift) <= "
                                     (t browser-name :landed) "; })()")
                             (max 0 (- (t browser-name :resync-within) (or landed 0)))))
        title-shown (<p! (.isVisible (.getByRole vp "heading" #js {:name (:title b) :exact true})))
        series (<? (m/sample vp (:switch-window W) {:live #(api/live base-url cookie (:id room))}))
        summary (m/summarize series 0)
        server-drift (:drift (api/client (<p! (api/live base-url cookie (:id room))) "viewer"))]
    (<p! (m/record! info summary series {:h-next h-next :offline-vid (:vid offline) :landed landed :synced synced
                                         :title-shown title-shown :server-drift server-drift}))
    (-> (expect h-next "host on the second video") (.not.toBeNull))
    (-> (expect (:vid offline) "viewer's video while offline") (.toBe (:video-id fx)))
    (-> (expect landed "ms from online to the second video") (.not.toBeNull))
    (-> (expect synced "ms from landing to settled") (.not.toBeNull))
    (-> (expect (+ landed synced) "ms from online to settled on the second video")
        (.toBeLessThanOrEqual (t browser-name :resync-within)))
    (-> (expect title-shown "the title updated") (.toBe true))
    (-> (expect (:drift-abs-p95 summary) "drift p95 after the resync") (.toBeLessThanOrEqual (t browser-name :landed)))
    (-> (expect (m/abs* server-drift) "server drift at the end") (.toBeLessThanOrEqual (t browser-name :landed)))))

(defjourney "a late joiner lands on the current video" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie extra] :as fx} (api/fixture info)
        b (first extra)
        room (<? (queued-room! base-url cookie fx 2))
        {:keys [host]} (<? (party/host! browser base-url browser-name (assoc fx :room room)))
        hp (:page host)
        _ (<p! (party/next! hp))
        h-next (<? (wait-ms! hp (playing-on (:id b)) (t browser-name :switch-within)))
        _ (<! (a/timeout (:host-head-start W)))
        viewer (<? (party/open! browser base-url (:token room) {:nick "late"}))
        vp (:page viewer)
        first-probe (<? (party/probe vp))
        ;; a parked frame reaches the room (within :park-drift) and then
        ;; sits frozen until the next re-park, so a later sample is only
        ;; bounded by :park-track
        parked-live? (when-not (:started first-probe)
                       (try (<p! (party/wait-live-park! vp (t browser-name :park-drift) (t browser-name :park-live-within))) true
                            (catch :default _ false)))
        parked (when-not (:started first-probe) (<? (party/probe vp)))
        joined (<? (party/join! vp (t browser-name :start-within)))
        t1 (js/Date.now)
        synced? (try (<p! (party/wait-synced! vp (t browser-name :landed) (t browser-name :sync-within))) true
                     (catch :default _ false))
        sync-ms (- (js/Date.now) t1)
        title-shown (<p! (.isVisible (.getByRole vp "heading" #js {:name (:title b) :exact true})))
        row (<p! (api/room base-url cookie (:id room)))
        series (<? (m/sample vp (:switch-window W)))
        summary (m/summarize series 0)]
    (<p! (m/record! info summary series (assoc joined :h-next h-next :welcome-vid (:vid first-probe)
                                               :welcome-started (:started first-probe)
                                               :parked-live parked-live? :parked-drift (:drift parked) :sync-ms sync-ms
                                               :title-shown title-shown :server-vid (:video-id row))))
    (-> (expect h-next "host playing the second video") (.not.toBeNull))
    (-> (expect (:vid first-probe) "video at the welcome") (.toBe (:id b)))
    (when parked
      (-> (expect parked-live? "parked frame reached the room on the second video") (.toBe true))
      (-> (expect (m/abs* (:drift parked)) "parked drift on the second video")
          (.toBeLessThanOrEqual (t browser-name :park-track))))
    (-> (expect synced? "settled after the join") (.toBe true))
    (-> (expect sync-ms "ms from the join to settled") (.toBeLessThanOrEqual (t browser-name :sync-within)))
    (-> (expect (:vid (last series)) "video at the end") (.toBe (:id b)))
    (-> (expect title-shown "the second video's title") (.toBe true))
    (-> (expect (:video-id row) "server's current video") (.toBe (:id b)))))

(defjourney "the lobby shows the party's progress" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie extra] :as fx} (api/fixture info)
        b (first extra)
        room (<? (queued-room! base-url cookie fx 3 :title "Season 1"))
        {:keys [host]} (<? (party/host! browser base-url browser-name (assoc fx :room room)))
        hp (:page host)
        _ (<p! (party/next! hp))
        h-next (<? (wait-ms! hp (on-video (:id b)) (t browser-name :switch-within)))
        {^js lobby :page} (<? (party/open-page! browser base-url "/" {}))
        ;; the newest "Season 1" (rooms persist across journeys; the list is newest first)
        card (.first (.filter (.locator lobby ".card") #js {:hasText "Season 1"}))
        _ (<p! (-> (expect card) (.toBeVisible)))
        line (<p! (.textContent (.locator card "p.muted")))
        expected (str "now: " (:title b) " · 3 in the queue")]
    (<p! (m/record! info {} [] {:h-next h-next :line line :expected expected}))
    (-> (expect h-next "host on the second video") (.not.toBeNull))
    (-> (expect line "the lobby card's progress line") (.toBe expected))))
