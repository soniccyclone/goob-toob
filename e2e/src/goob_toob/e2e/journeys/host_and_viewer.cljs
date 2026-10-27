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
        ;; the window opens once the landing is over (started, playing, past
        ;; the landing stall): the stall count below is about steady playback
        t1 (js/Date.now)
        landed? (try (<p! (party/wait-landed! (:page host) (t browser-name :sync-within))) true
                     (catch :default _ false))
        landed-ms (- (js/Date.now) t1)
        series (<? (m/sample (:page host) (:host-window W)))
        server (<p! (api/live base-url cookie (:id room)))
        advance (- (:currentTime (last series)) (:currentTime (first series)))
        summary (m/summarize series 0)]
    (<p! (m/record! info summary series (assoc host-joined :landed landed? :landed-ms landed-ms
                                               :advance advance :server-playing (:playing server))))
    (-> (expect (:started-ms host-joined) "ms from gesture to started")
        (.toBeLessThanOrEqual (t browser-name :start-within)))
    (-> (expect landed? "landed: started, playing, past the landing stall") (.toBe true))
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
        joined (<? (party/join! page (t browser-name :start-within)))
        series (<? (m/sample page (:join-window W)
                             {:live #(api/live base-url cookie (:id room))}))
        settled (t browser-name :landed)
        sync-at (m/first-at #(and (:started %) (number? (:drift %)) (<= (m/abs* (:drift %)) settled)) series)
        summary (m/summarize series sync-at)
        server-drift (:drift (api/client (<p! (api/live base-url cookie (:id room))) "viewer"))]
    (<p! (m/record! info summary series (assoc joined :sync-at sync-at :server-drift server-drift)))
    (-> (expect sync-at "ms from join to settled") (.toBeLessThanOrEqual (t browser-name :sync-within)))
    (-> (expect (:drift-abs-p95 summary) "drift p95 after settle")
        (.toBeLessThanOrEqual (t browser-name :landed)))
    (-> (expect (m/abs* server-drift) "server drift at the end") (.toBeLessThanOrEqual settled))
    (-> (expect (:seeks summary) "seeks after join") (.toBe 0))))

(def ^:private room-plays-unpaused
  "The room plays, this client has started and its element is not paused."
  "(() => { const p = goobProbe(); return p.playing && p.started && !p.paused; })()")

(defjourney "a held Space does not pause the host" [{:keys [browser browser-name base-url]} info]
  ;; Chromium's native controls toggle play on Space keyup, while the room's
  ;; play command goes out on the keydown. When the state lands before the
  ;; release (a LAN round trip fits inside a human keypress) the release used
  ;; to pause the host's own element while the room played on. Space is held
  ;; until the room plays, then released: the host must stay playing.
  (let [{:keys [cookie video-id]} (api/fixture info)
        room (<p! (api/make-room base-url cookie video-id))
        host (<? (party/open! browser base-url (:token room) {:nick "host" :cookie cookie}))
        page (:page host)
        _ (<p! (.focus (.locator page "video")))
        _ (<p! (.down (.-keyboard page) "Space"))
        playing-held? (try (<p! (party/wait-for! page room-plays-unpaused (t browser-name :start-within))) true
                           (catch :default _ false))
        _ (<p! (.up (.-keyboard page) "Space"))
        landed? (try (<p! (party/wait-landed! page (t browser-name :sync-within))) true
                     (catch :default _ false))
        series (<? (m/sample page (:host-window W)))
        summary (m/summarize series 0)
        advance (- (:currentTime (last series)) (:currentTime (first series)))
        paused-samples (count (filter :paused series))]
    (<p! (m/record! info summary series {:playing-held playing-held? :landed landed?
                                         :paused-samples paused-samples :advance advance}))
    (-> (expect playing-held? "room playing, host unpaused, with Space held") (.toBe true))
    (-> (expect landed? "landed after the release") (.toBe true))
    (-> (expect paused-samples "samples with the host element paused") (.toBe 0))
    (-> (expect advance "currentTime advance after the release")
        (.toBeGreaterThanOrEqual (t browser-name :min-advance)))))

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
        sync-at (m/first-at #(and (number? (:drift %)) (<= (m/abs* (:drift %)) (t browser-name :landed))) series)
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
        (.toBeLessThanOrEqual (t browser-name :landed)))))
