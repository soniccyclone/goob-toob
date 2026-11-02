(ns goob-toob.e2e.journeys.tracks
  "Audio and subtitle tracks, per viewer and never room state: the setup's
  dual-audio fixture (:dual, 440 Hz tagged eng and 880 Hz tagged jpn, a
  three-cue English SRT) carries two audio adaptation sets and a WebVTT
  sidecar. A viewer's choice lives in localStorage (goob-audio, goob-subs)
  and reaches dash.js (audio) or a <track> element (subs), nothing else.
  Subtitles are drawn by the page itself (div.subs over the picture, #94:
  the chosen track's active cues, in the viewer's theme, every <track>
  mode hidden so the browser draws nothing). Headless cannot hear which
  tone plays, so goobProbe() reports :audio-lang (what dash.js plays, in
  the descriptor's code), :subs (the chosen track's srclang), :text-cues
  (its loaded cues), :subs-theme and :subs-text (what the overlay shows),
  and the journeys judge those, next to the clock: a switch is a buffer
  flush, not a seek, and the room's sync does not notice."
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
  "lang's track chosen with at least n cues loaded."
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
  "The viewer row's track controls and what backs them: the three selects'
  values (nil when absent), the <track> elements with their modes, loaded
  cues and which is chosen, how many of the element's text tracks the
  browser draws itself (showing: always 0 now), the overlay's theme class,
  text and computed look (its colour and whether it has an outline, the
  cue box's background when a cue is up), and the stored choices."
  [^js page]
  (go-try
    (js->clj (<p! (.evaluate page (str "(() => { const v = document.querySelector('video');"
                                       " const a = document.querySelector('select.audio');"
                                       " const s = document.querySelector('select.subs');"
                                       " const th = document.querySelector('select.subs-theme');"
                                       " const slot = document.querySelector('.tracks');"
                                       " const o = document.querySelector('.stage-wrap .subs');"
                                       " const oc = o ? getComputedStyle(o) : null;"
                                       " const cue = o && o.querySelector('.cue');"
                                       " return {audio: a ? a.value : null, subs: s ? s.value : null,"
                                       " theme: th ? th.value : null,"
                                       " visible: !!slot && slot.style.display !== 'none',"
                                       " tracks: [...v.querySelectorAll('track')].map(t => ({lang: t.getAttribute('srclang'),"
                                       "   mode: t.track.mode, cues: t.track.cues ? t.track.cues.length : null,"
                                       "   chosen: t.hasAttribute('data-chosen')})),"
                                       " showing: [...v.textTracks].filter(t => t.mode === 'showing').length,"
                                       " overlay: o ? {cls: o.className, text: o.textContent, color: oc.color,"
                                       "   outlined: oc.textShadow !== 'none', cues: o.children.length,"
                                       "   cueBackground: cue ? getComputedStyle(cue).backgroundColor : null} : null,"
                                       " storedAudio: localStorage.getItem('goob-audio'),"
                                       " storedSubs: localStorage.getItem('goob-subs'),"
                                       " storedTheme: localStorage.getItem('goob-subs-theme')}; })()")))
             :keywordize-keys true)))

(defn- overlay-shows [text]
  (str "goobProbe()['subs-text'].includes(" (json text) ")"))

(defn- pip-state
  "What picture-in-picture looks like from the page: the probe's :pip
  keys, how many <video> elements exist (the hidden one only while in
  PiP), the real element's own PiP button disabled, and what the
  composite adds to the frame: the bottom 15% of the canvas against the
  same band of the real element drawn fresh at the same size (the room is
  paused when this is read, so the frame is the same one), :diff pixels
  that differ, :yellow those of them that are retro yellow. The test
  pattern has yellow of its own, so only the difference says a cue was
  painted."
  [^js page]
  (go-try
    (js->clj (<p! (.evaluate page (str "(() => { const p = goobProbe();"
                                       " const vids = document.querySelectorAll('video');"
                                       " const c = document.querySelector('canvas.pip-src');"
                                       " let diff = 0, yellow = 0, w = 0, h = 0;"
                                       " if (c && c.width > 0) { w = c.width; h = c.height;"
                                       "   const y0 = Math.floor(h * 0.85);"
                                       "   const s = document.createElement('canvas'); s.width = w; s.height = h;"
                                       "   const sc = s.getContext('2d'); sc.drawImage(vids[0], 0, 0, w, h);"
                                       "   const a = c.getContext('2d').getImageData(0, y0, w, h - y0).data;"
                                       "   const b = sc.getImageData(0, y0, w, h - y0).data;"
                                       "   for (let i = 0; i < a.length; i += 4) {"
                                       "     if (Math.abs(a[i]-b[i]) + Math.abs(a[i+1]-b[i+1]) + Math.abs(a[i+2]-b[i+2]) > 60) {"
                                       "       diff++; if (a[i] > 200 && a[i+1] > 200 && a[i+2] < 110) yellow++; } } }"
                                       " return {pip: p.pip, loop: p['pip-loop'], frames: p['pip-frames'], paused: p['pip-paused'],"
                                       "   videos: vids.length, nativeDisabled: vids[0].disablePictureInPicture,"
                                       "   canvas: [w, h], diff: diff, yellow: yellow, playing: p.playing, elementPaused: p.paused}; })()")))
             :keywordize-keys true)))

(defn- pip-video-call!
  "What the PiP window's own button does: play() or pause() on the
  hidden element."
  [^js page method]
  (.evaluate page (str "document.querySelector('video.pip-src')." method "()")))

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
                     :viewer-overlay (:overlay vc-on) :host-overlay (:overlay hc-on)
                     :back-audio (:audio-lang back) :back-subs (:subs back) :back-cues (:text-cues back)
                     :stored-audio (:storedAudio vc-back) :stored-subs (:storedSubs vc-back)
                     :host-stored-subs (:storedSubs hc-end)}))
    (-> (expect subs-ms "viewer's English subs showing with cues") (.not.toBeNull))
    (-> (expect (:subs viewer-on) "viewer probe subs") (.toBe "eng"))
    (-> (expect (:text-cues viewer-on) "viewer's loaded cues") (.toBeGreaterThanOrEqual 3))
    (-> (expect (:showing vc-on) "viewer's browser-drawn text tracks (the page draws them)") (.toBe 0))
    (-> (expect (clj->js (map :chosen (:tracks vc-on))) "viewer's chosen track") (.toEqual #js [true]))
    (-> (expect (:subs vc-on) "viewer's subtitle select") (.toBe "eng"))
    (-> (expect (:subs host-on) "host probe subs") (.toBeNull))
    (-> (expect (:text-cues host-on) "host's cues") (.toBe 0))
    (-> (expect (:showing hc-on) "host's browser-drawn text tracks") (.toBe 0))
    (-> (expect (clj->js (map :chosen (:tracks hc-on))) "host's chosen track") (.toEqual #js [false]))
    (-> (expect (get-in hc-on [:overlay :text]) "host's overlay") (.toBe ""))
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

(defjourney "subtitle looks are a viewer's choice" [{:keys [browser browser-name base-url]} info]
  ;; the overlay draws the chosen track's cues in the viewer's theme:
  ;; modern by default, classic CC and retro yellow on request, remembered
  ;; in localStorage; the host, with subs off, draws nothing. The host
  ;; pauses and seeks into the second cue (10-12 s) so the viewer parks
  ;; inside it and the text can be read off the overlay at leisure.
  (let [fx (api/fixture info)
        {:keys [host viewer]} (<? (dual-pair! browser base-url browser-name fx))
        hp (:page host) vp (:page viewer)
        within (t browser-name :switch-within)
        _ (<p! (pick! vp "select.subs" "eng"))
        subs-ms (<? (wait-ms! vp (subs-showing "eng" 3) within))
        default (<? (controls vp))
        _ (<p! (pick! vp "select.subs-theme" "cc"))
        cc (<? (controls vp))
        _ (<p! (pick! vp "select.subs-theme" "retro"))
        _ (<p! (party/pause! hp))
        _ (<p! (party/seek! hp 11.0))
        cue-ms (<? (wait-ms! vp (overlay-shows "second cue") within))
        retro (<? (controls vp))
        host-c (<? (controls hp))
        host-probe (<? (party/probe hp))
        ;; the viewer comes back: a fresh page, the same localStorage
        _ (<p! (.reload vp))
        _ (<p! (party/wait-for! vp welcomed (:ready-timeout W)))
        _ (<? (party/join! vp (t browser-name :start-within)))
        restore-ms (<? (wait-ms! vp (str "goobProbe()['subs-theme'] === 'retro' && " (subs-showing "eng" 3)) within))
        back (<? (controls vp))
        back-probe (<? (party/probe vp))]
    (<p! (m/record! info {} []
                    {:subs-ms subs-ms :cue-ms cue-ms :restore-ms restore-ms
                     :default (:overlay default) :cc (:overlay cc) :retro (:overlay retro)
                     :default-select (:theme default) :default-stored (:storedTheme default)
                     :retro-stored (:storedTheme retro)
                     :host-overlay (:overlay host-c) :host-subs-text (:subs-text host-probe)
                     :back (:overlay back) :back-select (:theme back) :back-theme (:subs-theme back-probe)}))
    (-> (expect subs-ms "viewer's English subs chosen with cues") (.not.toBeNull))
    (-> (expect (:theme default) "the select's default") (.toBe "modern"))
    (-> (expect (:storedTheme default) "nothing stored by default") (.toBeNull))
    (-> (expect (get-in default [:overlay :cls]) "modern overlay class") (.toContain "subs-modern"))
    (-> (expect (get-in default [:overlay :color]) "modern is white") (.toBe "rgb(255, 255, 255)"))
    (-> (expect (get-in default [:overlay :outlined]) "modern has an outline") (.toBe true))
    (-> (expect (get-in cc [:overlay :cls]) "cc overlay class") (.toContain "subs-cc"))
    (-> (expect (get-in cc [:overlay :outlined]) "cc has no outline (a box instead)") (.toBe false))
    (-> (expect (:storedTheme cc) "localStorage goob-subs-theme after cc") (.toBe "cc"))
    (-> (expect cue-ms "the second cue on the viewer's overlay") (.not.toBeNull))
    (-> (expect (get-in retro [:overlay :cls]) "retro overlay class") (.toContain "subs-retro"))
    (-> (expect (get-in retro [:overlay :color]) "retro is yellow") (.toBe "rgb(255, 238, 51)"))
    (-> (expect (get-in retro [:overlay :outlined]) "retro has an outline") (.toBe true))
    (-> (expect (get-in retro [:overlay :cues]) "one cue drawn") (.toBe 1))
    (-> (expect (get-in retro [:overlay :text]) "the cue's text") (.toBe "second cue"))
    (-> (expect (:storedTheme retro) "localStorage goob-subs-theme after retro") (.toBe "retro"))
    (-> (expect (get-in host-c [:overlay :text]) "host's overlay, subs off") (.toBe ""))
    (-> (expect (:subs-text host-probe) "host's probe subs-text") (.toBe ""))
    (-> (expect restore-ms "theme and subs back after the reload") (.not.toBeNull))
    (-> (expect (:theme back) "theme select after the reload") (.toBe "retro"))
    (-> (expect (get-in back [:overlay :cls]) "overlay class after the reload") (.toContain "subs-retro"))
    (-> (expect (:subs-theme back-probe) "probe theme after the reload") (.toBe "retro"))))

(defjourney "subtitles follow the video into picture-in-picture" [{:keys [browser browser-name base-url]} info]
  ;; #93: the PiP window gets frames composited by the page, cues painted
  ;; on, so what the viewer reads on the page they read in the window.
  ;; The viewer parks inside the second cue in retro yellow, pops out,
  ;; and the composite's bottom band must hold yellow pixels. The window's
  ;; pause is a viewer's no-op (it bounces back) and a controller's room
  ;; pause; leaving stops the loop and removes the hidden element.
  (let [fx (api/fixture info)
        {:keys [host viewer]} (<? (dual-pair! browser base-url browser-name fx))
        hp (:page host) vp (:page viewer)
        within (t browser-name :switch-within)
        _ (<p! (pick! vp "select.subs" "eng"))
        _ (<p! (pick! vp "select.subs-theme" "retro"))
        _ (<? (wait-ms! vp (subs-showing "eng" 3) within))
        _ (<p! (party/pause! hp))
        _ (<p! (party/seek! hp 11.0))
        cue-ms (<? (wait-ms! vp (overlay-shows "second cue") within))
        before (<? (pip-state vp))
        _ (<p! (.click (.locator vp ".pip-btn")))
        enter-ms (<? (wait-ms! vp "goobProbe().pip === true && goobProbe()['pip-frames'] > 3" within))
        in (<? (pip-state vp))
        ;; the host plays: the hidden element follows the real one
        _ (<p! (party/play! hp))
        play-ms (<? (wait-ms! vp "goobProbe().pip && goobProbe()['pip-paused'] === false" within))
        ;; the viewer presses pause in the window: no control, it bounces back
        _ (<p! (pip-video-call! vp "pause"))
        bounce-ms (<? (wait-ms! vp "goobProbe()['pip-paused'] === false && goobProbe().playing === true" within))
        bounced (<? (pip-state vp))
        ;; the host pops out too and presses pause in the window: the room pauses
        _ (<p! (.click (.locator hp ".pip-btn")))
        _ (<? (wait-ms! hp "goobProbe().pip === true" within))
        _ (<p! (pip-video-call! hp "pause"))
        room-pause-ms (<? (wait-ms! vp "goobProbe().playing === false && goobProbe().paused === true" within))
        host-paused (<? (pip-state hp))
        viewer-paused (<? (pip-state vp))
        ;; both leave through the button
        _ (<p! (.click (.locator vp ".pip-btn")))
        _ (<p! (.click (.locator hp ".pip-btn")))
        leave-ms (<? (wait-ms! vp "goobProbe().pip === false && goobProbe()['pip-loop'] === false" within))
        after (<? (pip-state vp))
        _ (<p! (.waitForTimeout vp 400))
        later (<? (pip-state vp))
        host-after (<? (pip-state hp))]
    (<p! (m/record! info {} []
                    {:cue-ms cue-ms :enter-ms enter-ms :play-ms play-ms :bounce-ms bounce-ms
                     :room-pause-ms room-pause-ms :leave-ms leave-ms
                     :before before :in in :bounced bounced :host-paused host-paused
                     :viewer-paused viewer-paused :after after :later later :host-after host-after}))
    (-> (expect cue-ms "the viewer parked inside the second cue") (.not.toBeNull))
    (-> (expect (:nativeDisabled before) "the real element's own PiP button is off") (.toBe true))
    (-> (expect (:videos before) "one <video> before PiP") (.toBe 1))
    (-> (expect (:pip before) "not in PiP before the click") (.toBe false))
    (-> (expect enter-ms "in PiP with frames drawn") (.not.toBeNull))
    (-> (expect (:videos in) "the hidden element while in PiP") (.toBe 2))
    (-> (expect (:loop in) "the loop on") (.toBe true))
    (-> (expect (first (:canvas in)) "composite width (the 720p fixture)") (.toBeGreaterThan 0))
    (-> (expect (:diff in) "pixels the composite adds to the frame's bottom band") (.toBeGreaterThan 500))
    (-> (expect (:yellow in) "retro-yellow among them: the cue") (.toBeGreaterThan 100))
    (-> (expect (:paused in) "the hidden element paused with the room") (.toBe true))
    (-> (expect play-ms "the hidden element plays when the room does") (.not.toBeNull))
    (-> (expect bounce-ms "a viewer's PiP pause bounces back") (.not.toBeNull))
    (-> (expect (:playing bounced) "the room still plays") (.toBe true))
    (-> (expect room-pause-ms "the host's PiP pause pauses the room") (.not.toBeNull))
    (-> (expect (:playing host-paused) "room paused, host's view") (.toBe false))
    (-> (expect (:paused host-paused) "host's hidden element paused") (.toBe true))
    (-> (expect (:diff host-paused) "host's composite, subs off: the bare frame") (.toBeLessThan 200))
    (-> (expect (:paused viewer-paused) "viewer's hidden element paused with the room") (.toBe true))
    (-> (expect leave-ms "out of PiP with the loop off") (.not.toBeNull))
    (-> (expect (:videos after) "the hidden element removed") (.toBe 1))
    (-> (expect (:frames later) "no frames drawn after leaving") (.toBe (:frames after)))
    (-> (expect (:pip host-after) "host out of PiP") (.toBe false))
    (-> (expect (:videos host-after) "host's hidden element removed") (.toBe 1))))

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
    (<p! (-> (expect (.locator page "select.subs-theme") "subtitle look select") (.toHaveCount 0)))
    (<p! (-> (expect (.locator page ".tracks") "the tracks slot") (.toBeHidden)))
    (-> (expect (:visible c) "slot display") (.toBe false))
    (-> (expect (clj->js (:tracks c)) "<track> elements") (.toEqual #js []))
    (-> (expect (:subs probe) "probe subs") (.toBeNull))
    (-> (expect (:text-cues probe) "probe cues") (.toBe 0))))
