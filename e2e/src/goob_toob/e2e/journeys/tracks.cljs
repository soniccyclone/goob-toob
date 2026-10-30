(ns goob-toob.e2e.journeys.tracks
  "Audio and subtitle tracks, per viewer and never room state: the setup's
  dual-audio fixture (:dual, 440 Hz tagged eng and 880 Hz tagged jpn, a
  three-cue English SRT) carries two audio adaptation sets and a WebVTT
  sidecar. A viewer's choice lives in localStorage (goob-audio, goob-subs)
  and reaches dash.js (audio) or a <track> element (subs), nothing else.
  Headless cannot hear which tone plays, so goobProbe() reports
  :audio-lang (what dash.js plays, in the descriptor's code), :subs (the
  showing track's srclang) and :text-cues (its loaded cues), and the
  journeys judge those, next to the clock: a switch is a buffer flush, not
  a seek, and the room's sync does not notice."
  (:refer-clojure :exclude [test])
  (:require ["@playwright/test" :refer [test expect]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.async :refer-macros [go-try <?]]
            [goob-toob.e2e.journey :refer [defjourney]]
            [goob-toob.e2e.metrics :as m]
            [goob-toob.e2e.party :as party]
            [goob-toob.e2e.thresholds :refer [t T W]]))

(defn- json [x] (js/JSON.stringify (clj->js x)))

(defn- audio-is [lang]
  (str "goobProbe()['audio-lang'] === " (json lang)))

(defn- subs-showing
  "lang's track showing with at least n cues loaded."
  [lang n]
  (str "(() => { const p = goobProbe(); return p.subs === " (json lang)
       " && p['text-cues'] >= " n "; })()"))

(defn- restored [alang slang n]
  (str "(() => { const p = goobProbe(); return p['audio-lang'] === " (json alang)
       " && p.subs === " (json slang) " && p['text-cues'] >= " n "; })()"))

(def ^:private welcomed "window.goobProbe && goobProbe().expected !== null")

(defn- wait-ms!
  "Waits for expr on page; the ms it took, or nil after timeout."
  [^js page expr timeout]
  (go-try
    (let [t0 (js/Date.now)]
      (try (<p! (party/wait-for! page expr timeout))
           (- (js/Date.now) t0)
           (catch :default _ nil)))))

(defn- controls
  "The viewer row's track controls and what backs them: the two selects'
  values (nil when absent), the <track> elements with their modes and
  loaded cues, how many of the element's text tracks are showing, and the
  two stored choices."
  [^js page]
  (go-try
    (js->clj (<p! (.evaluate page (str "(() => { const v = document.querySelector('video');"
                                       " const a = document.querySelector('select.audio');"
                                       " const s = document.querySelector('select.subs');"
                                       " const slot = document.querySelector('.tracks');"
                                       " return {audio: a ? a.value : null, subs: s ? s.value : null,"
                                       " visible: !!slot && slot.style.display !== 'none',"
                                       " tracks: [...v.querySelectorAll('track')].map(t => ({lang: t.getAttribute('srclang'),"
                                       "   mode: t.track.mode, cues: t.track.cues ? t.track.cues.length : null})),"
                                       " showing: [...v.textTracks].filter(t => t.mode === 'showing').length,"
                                       " storedAudio: localStorage.getItem('goob-audio'),"
                                       " storedSubs: localStorage.getItem('goob-subs')}; })()")))
             :keywordize-keys true)))

(defn- pick! [^js page sel value]
  (.selectOption (.locator page sel) value))

(defn- dual-pair!
  "A room on the dual-audio fixture, host and viewer playing and landed."
  [browser base-url engine {:keys [cookie dual] :as fx}]
  (go-try
    (let [room (<p! (api/make-room base-url cookie (:id dual)))
          scene (<? (party/pair! browser base-url engine (assoc fx :room room)))]
      (<p! (party/wait-landed! (:page (:host scene)) (t engine :sync-within)))
      scene)))

(defjourney "viewers pick their own audio and stay in sync" [{:keys [browser browser-name base-url]} info]
  (let [fx (api/fixture info)
        {:keys [host viewer]} (<? (dual-pair! browser base-url browser-name fx))
        hp (:page host) vp (:page viewer)
        within (t browser-name :switch-within)
        before (<? (party/probe vp))
        host-before (<? (party/probe hp))
        _ (<p! (pick! vp "select.audio" "jpn"))
        switch-ms (<? (wait-ms! vp (audio-is "jpn") within))
        series (<? (m/sample vp (:tracks-window W) {:host hp}))
        summary (m/summarize series 0)
        end (last series)
        seeks-across (- (get-in end [:counters :seeks] 0) (get-in before [:counters :seeks] 0))
        stalls-across (- (get-in end [:counters :stalls] 0) (get-in before [:counters :stalls] 0))
        viewer-langs (vec (distinct (map :audio-lang series)))
        host-langs (vec (distinct (map #(get-in % [:host :audio-lang]) series)))
        rates (vec (distinct (map :rate series)))
        vc (<? (controls vp))
        hc (<? (controls hp))]
    (<p! (m/record! info summary series
                    {:switch-ms switch-ms :viewer-before (:audio-lang before) :host-before (:audio-lang host-before)
                     :viewer-langs viewer-langs :host-langs host-langs :rates rates
                     :seeks-across seeks-across :stalls-across stalls-across
                     :viewer-select (:audio vc) :host-select (:audio hc)
                     :viewer-stored (:storedAudio vc) :host-stored (:storedAudio hc)
                     :autostart (:autostart end) :muted (:muted end)}))
    (-> (expect (:audio-lang before) "viewer's default audio under en-US") (.toBe "eng"))
    (-> (expect (:audio-lang host-before) "host's default audio") (.toBe "eng"))
    (-> (expect switch-ms "viewer on jpn") (.not.toBeNull))
    (-> (expect switch-ms "ms from the pick to jpn playing") (.toBeLessThanOrEqual within))
    (-> (expect (clj->js viewer-langs) "viewer's audio over the window") (.toEqual #js ["jpn"]))
    (-> (expect (clj->js host-langs) "host's audio over the window") (.toEqual #js ["eng"]))
    (-> (expect seeks-across "viewer seeks across the switch") (.toBe 0))
    (-> (expect (:drift-abs-p95 summary) "viewer drift p95 on the other language")
        (.toBeLessThanOrEqual (t browser-name :steady-p95)))
    (-> (expect stalls-across "viewer stalls across the switch (the flush)") (.toBeLessThanOrEqual 1))
    (-> (expect (clj->js rates) "playbackRate over the window") (.toEqual #js [1]))
    (-> (expect (:audio vc) "viewer's audio select") (.toBe "jpn"))
    (-> (expect (:storedAudio vc) "viewer's localStorage goob-audio") (.toBe "jpn"))
    (-> (expect (:audio hc) "host's audio select") (.toBe "eng"))
    (-> (expect (:storedAudio hc) "host's localStorage goob-audio") (.toBeNull))))

(defjourney "subtitles are a viewer's choice" [{:keys [browser browser-name base-url]} info]
  (let [fx (api/fixture info)
        {:keys [host viewer]} (<? (dual-pair! browser base-url browser-name fx))
        hp (:page host) vp (:page viewer)
        within (t browser-name :switch-within)
        _ (<p! (pick! vp "select.audio" "jpn"))
        _ (<p! (pick! vp "select.subs" "eng"))
        subs-ms (<? (wait-ms! vp (subs-showing "eng" 3) within))
        viewer-on (<? (party/probe vp))
        host-on (<? (party/probe hp))
        vc-on (<? (controls vp))
        hc-on (<? (controls hp))
        ;; the viewer comes back: a fresh page, the same localStorage
        _ (<p! (.reload vp))
        _ (<p! (party/wait-for! vp welcomed (:ready-timeout W)))
        _ (<? (party/join! vp (t browser-name :start-within)))
        _ (<p! (party/wait-synced! vp (t browser-name :landed) (t browser-name :sync-within)))
        restore-ms (<? (wait-ms! vp (restored "jpn" "eng" 3) within))
        back (<? (party/probe vp))
        vc-back (<? (controls vp))
        series (<? (m/sample vp (:host-window W) {:host hp}))
        summary (m/summarize series 0)
        host-end (:host (last series))
        hc-end (<? (controls hp))]
    (<p! (m/record! info summary series
                    {:subs-ms subs-ms :restore-ms restore-ms
                     :viewer-subs (:subs viewer-on) :viewer-cues (:text-cues viewer-on)
                     :host-subs (:subs host-on) :host-cues (:text-cues host-on)
                     :viewer-showing (:showing vc-on) :host-showing (:showing hc-on)
                     :viewer-tracks (:tracks vc-on) :host-tracks (:tracks hc-on)
                     :back-audio (:audio-lang back) :back-subs (:subs back) :back-cues (:text-cues back)
                     :stored-audio (:storedAudio vc-back) :stored-subs (:storedSubs vc-back)
                     :host-stored-subs (:storedSubs hc-end)}))
    (-> (expect subs-ms "viewer's English subs showing with cues") (.not.toBeNull))
    (-> (expect (:subs viewer-on) "viewer probe subs") (.toBe "eng"))
    (-> (expect (:text-cues viewer-on) "viewer's loaded cues") (.toBeGreaterThanOrEqual 3))
    (-> (expect (:showing vc-on) "viewer's showing text tracks") (.toBe 1))
    (-> (expect (:subs vc-on) "viewer's subtitle select") (.toBe "eng"))
    (-> (expect (:subs host-on) "host probe subs") (.toBeNull))
    (-> (expect (:text-cues host-on) "host's cues") (.toBe 0))
    (-> (expect (:showing hc-on) "host's showing text tracks") (.toBe 0))
    (-> (expect (:subs hc-on) "host's subtitle select") (.toBe "off"))
    (-> (expect (:storedSubs vc-on) "viewer's localStorage goob-subs") (.toBe "eng"))
    (-> (expect restore-ms "both choices back after the reload") (.not.toBeNull))
    (-> (expect (:audio-lang back) "audio after the reload") (.toBe "jpn"))
    (-> (expect (:subs back) "subs after the reload") (.toBe "eng"))
    (-> (expect (:text-cues back) "cues after the reload") (.toBeGreaterThanOrEqual 3))
    (-> (expect (:audio vc-back) "audio select after the reload") (.toBe "jpn"))
    (-> (expect (:subs vc-back) "subtitle select after the reload") (.toBe "eng"))
    (-> (expect (:subs host-end) "host subs at the end") (.toBeNull))
    (-> (expect (:storedSubs hc-end) "host's localStorage goob-subs") (.toBeNull))
    (-> (expect (:drift-abs-p95 summary) "viewer drift p95 with subs showing")
        (.toBeLessThanOrEqual (t browser-name :steady-p95)))))

(defjourney "a plain clip shows no track controls" [{:keys [browser browser-name base-url]} info]
  ;; the single-audio fixture: one untagged audio set, no sidecar; the row
  ;; must look as it did before tracks existed
  (let [fx (api/fixture info)
        {:keys [host]} (<? (party/host! browser base-url browser-name fx))
        ^js page (:page host)
        _ (<p! (party/wait-landed! page (t browser-name :sync-within)))
        probe (<? (party/probe page))
        c (<? (controls page))]
    (<p! (m/record! info {} [] {:audio-lang (:audio-lang probe) :subs (:subs probe) :text-cues (:text-cues probe)
                                :visible (:visible c) :tracks (:tracks c) :showing (:showing c)
                                :quality-select (:audio c)}))
    (<p! (-> (expect (.locator page "select.audio") "audio select") (.toHaveCount 0)))
    (<p! (-> (expect (.locator page "select.subs") "subtitle select") (.toHaveCount 0)))
    (<p! (-> (expect (.locator page ".tracks") "the tracks slot") (.toBeHidden)))
    (-> (expect (:visible c) "slot display") (.toBe false))
    (-> (expect (clj->js (:tracks c)) "<track> elements") (.toEqual #js []))
    (-> (expect (:subs probe) "probe subs") (.toBeNull))
    (-> (expect (:text-cues probe) "probe cues") (.toBe 0))))
