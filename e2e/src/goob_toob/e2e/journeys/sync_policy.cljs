(ns goob-toob.e2e.journeys.sync-policy
  (:refer-clojure :exclude [test])
  (:require ["@playwright/test" :refer [test expect]]
            [cljs.core.async :as a :refer [<!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.async :refer-macros [<?]]
            [goob-toob.e2e.journey :refer [defjourney only-engine!]]
            [goob-toob.e2e.metrics :as m]
            [goob-toob.e2e.net :as net]
            [goob-toob.e2e.party :as party]
            [goob-toob.e2e.thresholds :refer [t W]]))

(defn- in-range? [[lo hi] x] (and (number? x) (<= lo x hi)))

(defn closing-rate
  "Seconds of drift recovered per second across the samples in catch-up."
  [series]
  (let [catching (filter #(= "catch-up" (:mode %)) series)
        a (first catching) b (last catching)
        secs (when a (/ (- (:at b) (:at a)) 1000))]
    (if (and secs (pos? secs))
      (/ (- (:drift b) (:drift a)) secs)
      0)))

(defjourney "a laggard catches up without seeking" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie] :as fx} (api/fixture info)
        {:keys [room host viewer]} (<? (party/pair! browser base-url browser-name fx))
        vp (:page viewer) ^js hp (:page host)
        window (:laggard-window W)
        note (-> (.waitForFunction hp "document.body.innerText.includes('x for viewer')"
                                   nil #js {:timeout window :polling 100})
                 (.then (constantly true) (constantly false)))
        _ (<p! (.evaluate vp (str "(() => { window.goobMediaErrors = [];"
                                  " document.addEventListener('error', e => { const v = e.target;"
                                  " if (v.tagName === 'VIDEO') window.goobMediaErrors.push(v.error && v.error.code); }, true); })()")))
        _ (<p! (party/inject! vp (:laggard-inject W)))
        series (<? (m/sample vp window {:live #(api/live base-url cookie (:id room))}))
        named? (<p! note)
        decode-error? (<p! (.evaluate vp "(() => { const e = document.querySelector('video').error; return (!!e && e.code === 3) || window.goobMediaErrors.includes(3); })()"))
        summary (m/summarize series 0)
        catch-at (m/first-at #(in-range? (t browser-name :catch-rate) (:rate %)) series)
        slow-at (m/first-at #(= (t browser-name :room-slow-rate) (get-in % [:server :rate])) series)
        closing (closing-rate series)
        extra {:decode-error decode-error? :catch-at catch-at :slow-at slow-at :closing closing :note-named named?
               :drift-start (:drift (first series))
               :server-rates (vec (distinct (keep #(get-in % [:server :rate]) series)))}]
    (<p! (m/record! info summary series extra))
    (.skip test decode-error? "#42 WebKit build: backward seek ended in MEDIA_ERR_DECODE")
    (-> (expect closing "drift recovered per second while catching up")
        (.toBeGreaterThanOrEqual (t browser-name :closing-min)))
    (-> (expect catch-at "ms until the catch-up rate") (.toBeLessThanOrEqual (t browser-name :catch-within)))
    (-> (expect (:seekbacks summary) "seekbacks") (.toBe 0))
    (-> (expect (:seeks summary) "seeks") (.toBe 0))
    (-> (expect slow-at "ms until the server slows the room")
        (.toBeLessThanOrEqual (t browser-name :room-slow-within)))
    (-> (expect named? "host's slow note names the viewer") (.toBe true))))

(defjourney "a far-ahead viewer is slowed, never seeked" [{:keys [browser browser-name base-url]} info]
  (let [fx (api/fixture info)
        {:keys [viewer]} (<? (party/pair! browser base-url browser-name fx))
        vp (:page viewer)
        _ (<p! (party/inject! vp (:early-inject W)))
        series (<? (m/sample vp (:early-window W)))
        summary (m/summarize series 0)
        drift-start (:drift (first series))
        nudge-at (m/first-at #(in-range? (t browser-name :nudge-rate) (:rate %)) series)]
    (<p! (m/record! info summary series {:nudge-at nudge-at :drift-start drift-start}))
    (-> (expect (:seeks summary) "policy seeks") (.toBe 0))
    (-> (expect nudge-at "ms until the nudge rate") (.not.toBeNull))
    (-> (expect (:drift-final summary) "drift at the end, closing")
        (.toBeLessThan (- drift-start 0.2)))))

(defjourney "a slightly early viewer is nudged, not seeked" [{:keys [browser browser-name base-url]} info]
  (let [fx (api/fixture info)
        {:keys [viewer]} (<? (party/pair! browser base-url browser-name fx))
        vp (:page viewer)
        _ (<p! (party/inject! vp (:nudge-inject W)))
        series (<? (m/sample vp (:nudge-window W)))
        summary (m/summarize series 0)
        nudge-at (m/first-at #(in-range? (t browser-name :nudge-rate) (:rate %)) series)]
    (<p! (m/record! info summary series {:nudge-at nudge-at :drift-start (:drift (first series))}))
    (-> (expect (:seeks summary) "policy seeks") (.toBe 0))
    (-> (expect nudge-at "ms until the nudge rate") (.not.toBeNull))
    (-> (expect (m/abs* (:drift-final summary)) "drift at the end") (.toBeLessThanOrEqual (t browser-name :settled)))))

(defjourney "throttled viewer steps the quality down" [{:keys [browser browser-name base-url]} info]
  (only-engine! browser-name "chromium")
  (let [{:keys [cookie] :as fx} (api/fixture info)
        {:keys [room]} (<? (party/host! browser base-url browser-name fx))
        viewer (<? (party/open! browser base-url (:token room)
                                {:nick "throttled"
                                 :before-goto #(net/throttle! %1 %2 (:throttle-kbps W))}))
        vp (:page viewer)
        joined (<? (party/join! vp (t browser-name :start-within)))
        series (<? (m/sample vp (:throttle-window W) {:live #(api/live base-url cookie (:id room))}))
        summary (m/summarize series 0)
        end (last series)
        tail-start (first (filter #(>= (:at %) (- (:at end) (:still-window W))) series))
        advance (- (:currentTime end) (:currentTime tail-start))]
    (<p! (m/record! info summary series
                    (assoc joined :advance-tail advance :qcap (:qcap end)
                           :capsteps (m/counter-delta series :capsteps)
                           :server-rates (vec (distinct (keep #(get-in % [:server :rate]) series))))))
    (-> (expect advance "currentTime advance over the last window")
        (.toBeGreaterThanOrEqual (t browser-name :still-playing)))))
