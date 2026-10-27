(ns goob-toob.watch
  "The watch page: dash.js player parked at the room's position — the video
  element's own play button is the join gesture — then server-synced playback
  with gradual catch-up, resolution lock, peers and chat."
  (:require [clojure.string :as str]
            [goob-toob.ui :as ui :refer [h]]
            [goob-toob.ws :as wsx]
            [goob-toob.player :as player]
            [goob-toob.sync-core :as sc]))

(defn- play! [video]
  ;; browsers reject .play() without a user gesture; the video's native play
  ;; button supplies one. Swallow the rejection anyway (e.g. autoplay-blocked).
  (try (.catch (.play video) (fn [_] nil)) (catch js/Error _ nil)))

(defn- log! [token ev data]
  ;; Fire-and-forget playback diagnostics -> server.log (see routes/clientlog):
  ;; the user just sends the log file, no devtools required.
  (try
    (-> (js/fetch "/api/clientlog"
                  #js {:method "post"
                       :headers #js {"Content-Type" "application/json"}
                       :body (js/JSON.stringify
                               #js {:token token :msg (str ev " " (pr-str data))})})
        (.catch (fn [_] nil)))
    (catch js/Error _ nil)))

(defn- buffered [v]
  ;; buffered ranges as "0..32,32.5..60" — the stall snapshot's key signal
  (let [b (.-buffered v)]
    (loop [i 0 acc []]
      (if (< i (.-length b))
        (recur (inc i)
               (conj acc (str (/ (Math/round (* 10 (.start b i))) 10) ".."
                              (/ (Math/round (* 10 (.end b i))) 10))))
        (str/join "," acc)))))

(defn- expected-now [^js st room]
  (sc/expected-pos {:pos (:pos room) :t0 (:t0 room)
                    :now (+ (ui/perf-now) (.-offset st))
                    :playing? (:playing room)
                    :rate (:rate room)}))

(defn- bump! [^js st k]
  (let [c (.-counters st)]
    (unchecked-set c k (inc (unchecked-get c k)))))

(defn- mark-seek! [^js st]
  (bump! st "seeks")
  (set! (.-ourseek st) (ui/perf-now)))

(defn- park! [^js st video]
  ;; Sit paused at the room's position: the native play button is the
  ;; "press me" affordance, and a *real* seek is what makes dash composite
  ;; the frame there (same-value seek is a no-op -> black frame at pos 0).
  (when-let [room (.-room st)]
    (let [e (max 0.0 (expected-now st room))
          e (if (< (sc/abs (- (.-currentTime video) e)) 0.01) (+ e 0.001) e)]
      (.pause video)
      (mark-seek! st)
      (try (set! (.-currentTime video) e) (catch js/Error _ nil)))))

(defn- buffer-ahead [video]
  (let [b (.-buffered video) t (.-currentTime video)]
    (loop [i 0]
      (cond (>= i (.-length b)) 0.0
            (and (<= (.start b i) t) (<= t (.end b i))) (- (.end b i) t)
            :else (recur (inc i))))))

;; ---- #57 instrumentation: rate episodes, fullscreen, A/V readouts ----
;; Nothing here touches playback. It exists so a "the audio is out of sync"
;; report can be matched against the rate episodes and fullscreen changes
;; in server.log, and so goobProbe() carries the same numbers.

(defn- round1 [x] (/ (Math/round (* 10 x)) 10))
(defn- round3 [x] (/ (Math/round (* 1000 x)) 1000))
(defn- unity? [rate] (< (sc/abs (- rate 1.0)) 0.001))

(defn- av-probe [^js st ^js video]
  ;; cheap alignment readouts with no new dependency: dropped/total frames
  ;; from the element, and dash.js's buffer level for each track (the two
  ;; SourceBuffers fill independently; a gap between them is a lead)
  (let [^js q (try (when (.-getVideoPlaybackQuality video) (.getVideoPlaybackQuality video))
                   (catch js/Error _ nil))
        ^js m (try (some-> ^js (.-player st) (.getDashMetrics)) (catch js/Error _ nil))
        level (fn [kind] (try (some-> m (.getCurrentBufferLevel kind) round1)
                              (catch js/Error _ nil)))]
    {:dropped-frames (some-> q .-droppedVideoFrames)
     :total-frames (some-> q .-totalVideoFrames)
     :audio-buffer (level "audio")
     :video-buffer (level "video")}))

(defn- off-rate-s [^js st]
  ;; cumulative seconds the element has run off 1.0 since the page loaded,
  ;; the open episode (if any) included
  (let [ms (+ (.-offrate st) (if-let [since (.-offsince st)] (- (ui/perf-now) since) 0))]
    (round1 (/ ms 1000))))

(defn- close-off-rate! [^js st now]
  (when-let [since (.-offsince st)]
    (set! (.-offrate st) (+ (.-offrate st) (- now since)))
    (set! (.-offsince st) nil)))

(defn- note-rate! [^js st token ^js video from to mode d]
  ;; the policy is about to move the element's rate from -> to
  (let [now (ui/perf-now)]
    (close-off-rate! st now)
    (when-not (unity? to) (set! (.-offsince st) now))
    (set! (.-ratechanged st) now)
    (log! token :rate (merge {:from (round3 from) :to (round3 to) :mode mode
                              :drift (round1 d) :off-s (off-rate-s st)
                              :fullscreen (.-fullscreen st)}
                             (av-probe st video)))))

(defn- quality-event! [^js st token ev]
  (when-let [ctx (.-player st)]
    (when (.-auto st)
      (let [reps (player/quality-list ctx)]
        (when (seq reps)
          (let [capped (sc/quality-cap (.-qcap st) ev (player/current-quality ctx)
                                       (dec (count reps)) (ui/perf-now))]
            (when (not= capped (.-qcap st))
              (set! (.-qcap st) capped)
              (bump! st "capsteps")
              (let [kbps (sc/cap-kbps capped reps)]
                (player/set-max-kbps ctx kbps)
                (log! token :quality-cap {:event ev :cap (:cap capped) :kbps kbps})))))))))

(defn- apply-policy! [^js st token video lag-hint]
  (when (and (.-started st) (.-room st) (not (.-buffering st)))
    (let [room (.-room st)
          expected (expected-now st room)
          d (sc/drift (.-currentTime video) expected)
          mode (sc/policy (.-mode st) d (buffer-ahead video))
          rate (sc/rate-for mode (:rate room))]
      (when (and (= mode :catch-up) (not= (.-mode st) :catch-up))
        (quality-event! st token :catch-up))
      (set! (.-mode st) mode)
      (let [cur (.-playbackRate video)]
        (when (and (.-offsince st) (unity? cur))
          ;; reset to 1.0 behind the policy's back (pause, resync): the
          ;; episode is over, book it (at most one tick late)
          (close-off-rate! st (ui/perf-now)))
        (when (> (sc/abs (- cur rate)) 0.001)
          (note-rate! st token video cur rate mode d)
          (set! (.-playbackRate video) rate)))
      (set! (.-textContent lag-hint)
            (if (< d -10.0)
              (str "you're " (int (- d)) "s behind the room — reload to jump to it")
              "")))))

(defn- ready? [^js st ^js video]
  (boolean (and (.-room st) (.-player st) (.-streamok st) (seq (.-samples st))
                (>= (.-readyState video) 2))))

(defn- begin! [^js st ^js video]
  (set! (.-started st) true)
  (mark-seek! st)
  (set! (.-currentTime video) (expected-now st (.-room st))))

(defn- outcome! [^js st token outcome ev data]
  (when (not= outcome (.-autostart st))
    (log! token ev data))
  (set! (.-autostart st) outcome))

(defn- autostart! [^js st token ^js video]
  (set! (.-starting st) true)
  (bump! st "autostarts")
  (begin! st video)
  (-> (.play video)
      (.then (fn []
               (set! (.-starting st) false)
               (outcome! st token "audible" :autostart {})))
      (.catch (fn [^js err]
                (set! (.-starting st) false)
                (set! (.-started st) false)
                (outcome! st token "blocked" :autostart-blocked {:err (some-> err .-name)})
                (park! st video)))))

(defn- settle! [^js st token video]
  (let [action (sc/start-action :state {:started? (boolean (.-started st))
                                        :playing? (boolean (:playing (.-room st)))
                                        :can-control? (boolean (.-cancontrol st))})]
    (case action
      :autostart (if (and (ready? st video) (not (.-starting st)))
                   (autostart! st token video)
                   (park! st video))
      :park      (park! st video)
      :none      nil)
    action))

(defn- apply-state-msg! [^js st token video msg]
  (set! (.-room st) msg)
  (case (settle! st token video)
    :none (case (:op msg)
            "play"  (do (mark-seek! st)
                        (set! (.-currentTime video) (:pos msg))
                        (play! video))
            "pause" (do (.pause video)
                        (mark-seek! st)
                        (set! (.-currentTime video) (:pos msg))
                        (set! (.-playbackRate video) 1.0))
            "seek"  (do (mark-seek! st)
                        (set! (.-currentTime video) (:pos msg)))
            nil)
    nil))

(defn- resync! [^js st token video]
  (let [room (.-room st)
        e (expected-now st room)]
    (log! token :reconnect-resync {:drift (/ (Math/round (* 10 (sc/drift (.-currentTime video) e))) 10)})
    (set! (.-mode st) :none)
    (set! (.-playbackRate video) 1.0)
    (mark-seek! st)
    (set! (.-currentTime video) e)
    (if (:playing room)
      (play! video)
      (.pause video))))

;; ---- host transport (#53 jump buttons, #54 keys, #55 native seeks) ----
;; Every control here drives the *room*: a cmd the server re-gates (host, or
;; anyone when "co-hosts can drive" is on) and broadcasts as a state, which
;; is what moves this element too. None of it is sync policy. A controller's
;; native <video> seek (scrub bar, media keys) is a room seek as well, from
;; onseeked below, once it lands further than native-seek-floor from the
;; room's expected position.

(def ^:private native-seek-floor
  "Seconds a controller's unexplained seek must land from the room's
  expected position before it is taken as a scrub and sent as a room seek.
  dash.js's own small gap jumps and a landing stall sit well inside it; a
  scrub is always bigger."
  2.0)

(defn- room-duration [^js st ^js video]
  ;; the element's duration once it knows it, else the probe's from the welcome
  (let [d (.-duration video)]
    (if (and (number? d) (js/isFinite d) (pos? d)) d (.-duration st))))

(defn- jump-target
  "The room's expected position moved by n seconds, clamped to [0, duration]
  (no upper clamp while the duration is still unknown)."
  [expected n duration]
  (let [p (max 0.0 (+ expected n))]
    (if (and (number? duration) (pos? duration)) (min p duration) p)))

(defn- jump!
  "A room seek n seconds from the room's *expected* position — the client's
  model of the room clock, the same one the policy and the probe use — never
  from this element's currentTime, so a host whose element sits a little
  behind does not drag the room back. While paused the paused position moves
  and the room stays paused. Truthy when a command went out."
  [^js st ^js video n]
  (when-let [room (when (.-cancontrol st) (.-room st))]
    (wsx/send! (.-ws st) {:type "cmd" :op "seek"
                          :pos (jump-target (expected-now st room) n (room-duration st video))})
    true))

(defn- toggle!
  "Space/K: pause a playing room, play a paused one, both at the expected
  position. A controller who has not started yet while the room plays is
  left alone (nil): Space stays the join gesture there, and the keypress
  already handed the autostart retry its activation."
  [^js st]
  (when-let [room (when (.-cancontrol st) (.-room st))]
    (if (:playing room)
      (when (.-started st)
        (wsx/send! (.-ws st) {:type "cmd" :op "pause" :pos (expected-now st room)})
        true)
      (do (set! (.-started st) true)
          (wsx/send! (.-ws st) {:type "cmd" :op "play" :pos (expected-now st room)})
          true))))

(defn- transport-key
  "key -> [:jump seconds] | [:toggle] | nil"
  [k]
  (case k
    "ArrowLeft"   [:jump -5.0]
    "ArrowRight"  [:jump 5.0]
    ("j" "J")     [:jump -10.0]
    ("l" "L")     [:jump 10.0]
    ("k" "K" " ") [:toggle]
    nil))

(defn- owns-key? [^js t k]
  ;; the chat and nick inputs, the seek slider and the resolution menu keep
  ;; their keys; a focused button keeps Space (its own activation)
  (let [tag (some-> t .-tagName)]
    (boolean (or (#{"INPUT" "TEXTAREA" "SELECT"} tag)
                 (some-> t .-isContentEditable)
                 (and (= "BUTTON" tag) (= " " k))))))

(defn- keys!
  "Document-level transport keys for a client that can control the room:
  arrows -5/+5 s, J/L -10/+10 s, K/Space play-pause. preventDefault keeps
  the element's native seek and the page scroll from firing first. A viewer
  who cannot control gets nothing from the arrows (the native seek would only
  desync them) and Space stays as it is, the join gesture. Repeats are
  dropped so holding a key does not flood the socket.

  Chromium's native controls toggle play on Space *keyup*, at the target,
  and no keydown preventDefault reaches that. Left alone it races the room:
  the play command goes out on the keydown, and when its state lands before
  the release (a LAN round trip fits inside a human keypress) the release
  pauses the host's own element while the room plays on, the spinner at
  0:00. A capture-phase keyup preventDefault is what Chromium honours, so a
  controller's Space is ours end to end; a viewer who cannot control keeps
  the native toggle as the join gesture."
  [^js st ^js video]
  (.addEventListener js/document "keyup"
    (fn [^js e]
      (when (and (.-cancontrol st) (= " " (.-key e)) (not (owns-key? (.-target e) " ")))
        (.preventDefault e)))
    true)
  (.addEventListener js/document "keydown"
    (fn [^js e]
      (let [k (.-key e)]
        (when-not (or (.-repeat e) (.-ctrlKey e) (.-metaKey e) (.-altKey e)
                      (owns-key? (.-target e) k))
          (when-let [[op n] (transport-key k)]
            (cond
              (not (.-cancontrol st))
              (when (and (= op :jump) (identical? (.-target e) video))
                (.preventDefault e))

              (= op :jump)
              (when (jump! st video n) (.preventDefault e))

              :else
              (when (toggle! st) (.preventDefault e)))))))))

(defn- render-peers! [peers-el peers host?]
  (ui/clear! peers-el)
  (doseq [p peers]
    (ui/add! peers-el
      (h :div.peer
         (h :span {}
            (h :span {:class (if (:buffering p) "dot stall" "dot")})
            (str " " (:nick p)))
         (when host?
           (h :span.muted (or (ui/fmt-lag (:lag p)) "")))))))

(defn- near-bottom? [el]
  (< (- (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el)) 40))

(defn- bubble [msg]
  (h :div.bubble
     (h :span.who {:style (str "color:" (ui/nick-color (:from msg)))} (:from msg))
     (h :div (:text msg))))

(defn- append-chat! [log-el pill-el msg]
  ;; stick to the bottom only if the reader was already there; otherwise
  ;; surface the "new messages" pill and leave their scroll alone
  (let [stick (near-bottom? log-el)]
    (ui/add! log-el (bubble msg))
    (if stick
      (set! (.-scrollTop log-el) (.-scrollHeight log-el))
      (set! (.-display (.-style pill-el)) "block"))))

;; ---- fullscreen (#58) ----
;; The fullscreen target is the stage *wrapper*, not the <video>: nothing can
;; draw over a fullscreen element but its own children, and the chat overlay
;; is a sibling of the video inside the wrapper. Only our ⛶ button goes
;; through here. The native fullscreen button and the native double-click
;; stay as the browser ships them (Nathan's call, the Twitch shape): they
;; fullscreen the bare element, and that path has no overlay.

(def ^:private overlay-keep 6)
(def ^:private overlay-ms 8000)

(defn- fs-element []
  (or (.-fullscreenElement js/document) (.-webkitFullscreenElement js/document)))

(defn- request-fs! [^js wrap ^js video]
  (try
    (cond
      (.-requestFullscreen wrap)
      (some-> (.requestFullscreen wrap) (.catch (fn [_] nil)))

      (.-webkitRequestFullscreen wrap)
      (.webkitRequestFullscreen wrap)

      ;; iOS Safari fullscreens nothing but a <video>: no overlay there
      (.-webkitEnterFullscreen video)
      (.webkitEnterFullscreen video))
    (catch js/Error _ nil)))

(defn- exit-fs! []
  (try
    (cond (.-exitFullscreen js/document) (some-> (.exitFullscreen js/document) (.catch (fn [_] nil)))
          (.-webkitExitFullscreen js/document) (.webkitExitFullscreen js/document))
    (catch js/Error _ nil)))

(defn- toggle-fs! [wrap video]
  (if (fs-element) (exit-fs!) (request-fs! wrap video)))

(defn- picture-ratio!
  "Tell the stylesheet the picture's real aspect ratio (--ar on the wrapper)
  so the viewport-height cap keeps the border hugging the video."
  [^js wrap ^js video]
  (let [w (.-videoWidth video) h (.-videoHeight video)]
    (when (and (pos? w) (pos? h))
      (.setProperty (.-style wrap) "--ar" (str w " / " h)))))

(defn- overlay-chat!
  "The Twitch-style copy of a chat bubble over the fullscreen picture: it
  fades out on its own (CSS) and is dropped after overlay-ms; only the last
  overlay-keep stay. Off when the viewer's setting is off. The overlay is
  display:none outside fullscreen, so appending here is cheap and a viewer
  who goes fullscreen sees the last few seconds of chat straight away."
  [^js st overlay msg]
  (when (.-fschat st)
    (let [b (bubble msg)]
      (ui/add! overlay b)
      (while (> (.-childElementCount overlay) overlay-keep)
        (.remove (.-firstElementChild overlay)))
      (js/setTimeout #(.remove b) overlay-ms))))

(defn- fs-setting
  "The per-viewer \"chat over fullscreen video\" checkbox, remembered in
  localStorage (goob-fschat), default on."
  [^js st overlay]
  (let [on? (not= "off" (ui/store-get "goob-fschat"))
        box (h :input {:type "checkbox" :checked on?})]
    (set! (.-fschat st) on?)
    (set! (.-onchange box)
          #(let [on? (.-checked box)]
             (set! (.-fschat st) on?)
             (ui/store-set! "goob-fschat" (if on? "on" "off"))
             (when-not on? (ui/clear! overlay))))
    (h :label.setting {:title "show chat messages over the picture while fullscreen"}
       box "chat over fullscreen video")))

(defn- stage!
  "Wire the stage: ⛶ fullscreens the wrapper, the button's title follows
  the state, the picture ratio feeds the layout cap."
  [^js wrap ^js video ^js fs-btn]
  (set! (.-onclick fs-btn) #(toggle-fs! wrap video))
  ;; like the transport buttons: a click must not leave focus on the button,
  ;; or the next Space would re-press it instead of reaching the #54 keys
  (set! (.-onmousedown fs-btn) #(.preventDefault %))
  (doseq [ev ["fullscreenchange" "webkitfullscreenchange"]]
    (.addEventListener js/document ev
      #(.setAttribute fs-btn "title" (if (fs-element) "exit fullscreen" "fullscreen"))))
  (doseq [ev ["loadedmetadata" "resize"]]
    (.addEventListener video ev #(picture-ratio! wrap video))))

(defn- lock-height! [^js st idx]
  (set! (.-auto st) false)
  (set! (.-qcap st) nil)
  (player/set-max-kbps (.-player st) -1)
  (player/set-height (.-player st) idx))

(defn- res-menu [^js st]
  (let [sel (h :select {:title "resolution"})
        saved (ui/store-get "goob-res")]
    (doseq [[v label] (concat [["auto" "auto (best sync)"]]
                              (for [r (player/video-representations (.-player st))]
                                [(str (:idx r)) (str (:height r) "p")]))]
      (.appendChild sel (doto (h :option {:value v})
                          (.appendChild (.createTextNode js/document label))))
      (when (= saved v) (set! (.-value sel) v)))
    (when (and saved (not= saved "auto"))
      (lock-height! st (js/parseInt saved)))
    (set! (.-onchange sel)
          (fn []
            (let [v (.-value sel)]
              (ui/store-set! "goob-res" v)
              (if (= v "auto")
                (do (set! (.-auto st) true)
                    (set! (.-qcap st) nil)
                    (player/set-max-kbps (.-player st) -1)
                    (player/set-auto (.-player st)))
                (lock-height! st (js/parseInt v))))))
    sel))

(defn- connect! [^js st token refs]
  (let [{:keys [^js video title-el seek res-slot peers-el chat-log fs-chat new-msgs admin-link
                lag-hint slow-note]} refs
        proto (if (= "https:" (.-protocol js/location)) "wss:" "ws:")
        url (str proto "//" (.-host js/location) "/ws/room/" token
                 "?nick=" (js/encodeURIComponent (.-nick st)))]
    (wsx/connect
      url
      (fn [msg _ws]
        (case (:type msg)
          "welcome"
          (do (set! (.-ishost st) (:is-host msg))
              (when (:is-host msg)
                (set! (.-display (.-style admin-link)) ""))
              (set! (.-cancontrol st) (boolean (:can-control msg)))
              (set! (.-duration st) (get-in msg [:video :duration]))
              (set! (.-room st) (:state msg))
              (set! (.-textContent title-el) (get-in msg [:video :title]))
              (when (and (.-player st) (.-started st))
                (resync! st token video))
              (when-not (.-player st)
                (set! (.-preservesPitch video) true)
                (set! (.-webkitPreservesPitch video) true)
                (.addEventListener video "loadeddata" #(settle! st token video))
                (.addEventListener video "error"
                  #(log! token :video-error {:code (some-> (.-error video) .-code)
                                             :msg (some-> (.-error video) .-message)}))
                (set! (.-player st)
                  (player/create video (get-in msg [:video :dash]) (:start-kbps msg)
                    (fn [ctx]
                      (log! token :stream-init {:dash (get-in msg [:video :dash])
                                                :ua (.-userAgent js/navigator)})
                      (ui/clear! res-slot)
                      (ui/add! res-slot (res-menu st))
                      (set! (.-streamok st) true)
                      (settle! st token video))))
                ;; faults must be wired NOW, not on streamInitialized —
                ;; a broken manifest never initializes, that's the point
                (player/on-faults (.-player st)
                  (fn [d] (log! token (:ev d) (dissoc d :ev))))))
          "state" (apply-state-msg! st token video msg)
          "beat"  (do (set! (.-room st) msg)
                      (apply-policy! st token video lag-hint)
                      (render-peers! peers-el (:peers msg) (.-ishost st))
                      (set! (.-textContent slow-note)
                            (if-let [nick (:slow-for msg)]
                              (str "room at " (:room-slow-rate sc/tuning) "x for " nick)
                              ""))
                      (when-not (.-seeking st)
                        (set! (.-value seek) (str (.-currentTime video)))))
          "pong"  (let [t1 (ui/perf-now)
                        sample {:rtt (- t1 (:t msg))
                                :offset (sc/ntp-offset (:t msg) (:st msg) t1)}]
                    (set! (.-samples st) (take 8 (cons sample (.-samples st))))
                    (let [before (.-offset st)
                          after (sc/best-offset (.-samples st))]
                      (when (> (sc/abs (- after before)) 1000)
                        (log! token :clock-jump {:from (Math/round before) :to (Math/round after)
                                                 :rtt (Math/round (:rtt sample))}))
                      (set! (.-offset st) after))
                    (settle! st token video))
          "chat"  (do (append-chat! chat-log new-msgs msg)
                      (overlay-chat! st fs-chat msg))
          "peers" (render-peers! peers-el (:peers msg) (.-ishost st))
          "error" (set! (.-textContent title-el) (:message msg))
          nil))
      (fn [ws]
        (set! (.-ws st) ws)
        (set! (.-samples st) [])
        (wsx/send! ws {:type "ping" :t (ui/perf-now)})))))

(defn- shell [^js st]
  (let [video (.createElement js/document "video")
        _ (do (set! (.-className video) "stage")
              (.setAttribute video "controls" "")
              (.setAttribute video "playsinline" ""))
        fs-chat (h :div.fs-chat {:aria-hidden "true"})
        fs-btn (h :button.fs-btn {:title "fullscreen"} "⛶")
        stage-wrap (h :div.stage-wrap video fs-chat fs-btn)
        lag-hint (h :span.muted {})
        slow-note (h :span.muted {})
        title-el (h :h3 {:style "margin:0;color:var(--slime)"} "joining the goo…")
        admin-link (h :a {:href "/admin" :title "admin console"
                          :style "margin-left:auto;font-size:0.8rem;opacity:0.7;display:none"}
                      "admin?")
        back-btn (h :button.btn.ghost.jump {:title "back 10 s (shift: 5 s)"} "⏪ 10s")
        play-btn (h :button.btn "▶ play")
        pause-btn (h :button.btn.aqua "⏸ pause")
        fwd-btn (h :button.btn.ghost.jump {:title "forward 10 s (shift: 5 s)"} "10s ⏩")
        seek (h :input.seek {:type "range" :value 0 :min 0 :step 0.5})
        res-slot (h :span {})
        viewer-row (h :div.controls {})
        host-row (h :div.controls {:style "display:none"})
        badge (h :span.badge.host "you drive")
        chat-log (h :div.log {})
        new-msgs (h :button.new-msgs {:style "display:none"} "↓ new messages")
        chat-input (h :input {:type "text" :placeholder "say something gooey…"})
        peers-el (h :div {})
        send-chat (fn []
                    (let [t (str/trim (.-value chat-input))]
                      (when-not (empty? t)
                        (wsx/send! (.-ws st) {:type "chat" :text t})
                        (set! (.-value chat-input) ""))))]
    (set! (.-onwaiting video)
          #(do (bump! st "stalls")
               (when (and (.-started st) (not (.-buffering st)))
                 (quality-event! st (unchecked-get st "token") :stall))
               (set! (.-buffering st) true)))
    (set! (.-onplaying video) #(set! (.-buffering st) false))
    (set! (.-onseeking video) #(set! (.-seeking st) true))
    (set! (.-onseeked video)
      (fn []
        (set! (.-seeking st) false)
        (when (and (> (- (ui/perf-now) (.-ourseek st)) 1000) (.-started st))
          (let [room (.-room st)
                d (when room (sc/drift (.-currentTime video) (expected-now st room)))]
            (if (and d (.-cancontrol st) (> (sc/abs d) native-seek-floor))
              ;; #55: a controller's native seek (scrub bar drag, media keys)
              ;; drives the room, as a cmd the server re-gates and broadcasts;
              ;; the state that comes back re-seeks this element too, marked
              ;; as ours. Arrow keys never get here (keys! owns them, #54).
              (do (wsx/send! (.-ws st) {:type "cmd" :op "seek" :pos (.-currentTime video)})
                  (log! (unchecked-get st "token") :native-seek
                        {:to (Math/round (.-currentTime video)) :drift (round1 d)}))
              (log! (unchecked-get st "token") :seek-unexplained
                    {:to (Math/round (.-currentTime video))
                     :drift (when d (round1 d))
                     :buf (buffered video) :ready (.-readyState video) :paused (.-paused video)}))))))
    (set! (.-onplay video)
      (fn []
        (when-not (.-starting st)
          (let [act      (.-userActivation js/navigator)
                gesture? (or (nil? act) (boolean (.-isActive act)))
                started? (boolean (.-started st))
                room     (.-room st)
                action   (sc/start-action :play {:started? started? :gesture? gesture?
                                                 :playing? (boolean (:playing room))
                                                 :can-control? (boolean (.-cancontrol st))})]
            (when-not started?
              (if gesture?
                (do (set! (.-started st) true)
                    (log! (unchecked-get st "token") :started
                          {:activation (boolean (and act (.-isActive act)))}))
                (do (bump! st "rejected")
                    (log! (unchecked-get st "token") :play-rejected {}))))
            (case action
              :none       nil
              :park       (park! st video)
              :start      (do (mark-seek! st)
                              (set! (.-currentTime video) (expected-now st room)))
              :start-room (when room
                            (mark-seek! st)
                            (set! (.-currentTime video) (expected-now st room))
                            (wsx/send! (.-ws st) {:type "cmd" :op "play"
                                                   :pos (expected-now st room)})))))))
    (set! (.-onkeydown chat-input) #(when (= "Enter" (.-key %)) (send-chat)))
    (set! (.-onclick new-msgs)
          #(do (set! (.-scrollTop chat-log) (.-scrollHeight chat-log))
               (set! (.-display (.-style new-msgs)) "none")))
    (set! (.-onscroll chat-log)
          #(when (near-bottom? chat-log) (set! (.-display (.-style new-msgs)) "none")))
    (set! (.-onclick play-btn)
          #(do (set! (.-started st) true)
               (wsx/send! (.-ws st) {:type "cmd" :op "play" :pos (.-currentTime video)})))
    (set! (.-onclick pause-btn)
          #(wsx/send! (.-ws st) {:type "cmd" :op "pause" :pos (.-currentTime video)}))
    (set! (.-onclick back-btn) #(jump! st video (if (.-shiftKey %) -5.0 -10.0)))
    (set! (.-onclick fwd-btn) #(jump! st video (if (.-shiftKey %) 5.0 10.0)))
    ;; a clicked transport button must not keep focus, or the next Space
    ;; would re-press it instead of toggling the room (tab focus still works)
    (doseq [b [back-btn play-btn pause-btn fwd-btn]]
      (set! (.-onmousedown b) #(.preventDefault %)))
    (set! (.-onchange seek)
          #(do (set! (.-seeking st) false)
               (wsx/send! (.-ws st) {:type "cmd" :op "seek"
                                      :pos (js/parseFloat (.-value seek))})))
    (set! (.-oninput seek) #(set! (.-seeking st) true))
    (stage! stage-wrap video fs-btn)
    (ui/add! viewer-row res-slot (h :span.muted "picture quality") (fs-setting st fs-chat))
    (ui/add! host-row back-btn play-btn pause-btn fwd-btn seek badge
             (h :span.muted "everyone follows you") slow-note)
    {:video video
     :layout (h :div.watch-layout
                (h :div.row-gap
                   (h :div.card.row-gap
                      (h :div {:style "display:flex;align-items:center;gap:0.5rem"}
                         title-el admin-link)
                      stage-wrap
                      lag-hint
                      viewer-row
                      host-row))
                (h :div.row-gap
                   (h :div.card
                      (h :h3 "watching with")
                      peers-el)
                    (h :div.card.chat
                       (h :h3 "chat")
                       chat-log
                       new-msgs
                       (h :div.row {}
                          chat-input
                          (h :button.btn.aqua {:onclick send-chat} "💬")))))
     :title-el title-el :seek seek :res-slot res-slot :admin-link admin-link
     :chat-log chat-log :fs-chat fs-chat :peers-el peers-el :host-row host-row
     :badge badge :new-msgs new-msgs :lag-hint lag-hint :slow-note slow-note}))

(defn- fullscreen? []
  (boolean (or (.-fullscreenElement js/document) (.-webkitFullscreenElement js/document))))

(defn- watch-fullscreen! [^js st token ^js video]
  ;; #57: desync reports mention fullscreen, so every transition is logged
  ;; with the drift, rate and A/V readouts at that moment. Safari may fire
  ;; both the prefixed and the standard event; only a real change logs.
  (let [on-change (fn [_]
                    (let [on (fullscreen?)]
                      (when (not= on (boolean (.-fullscreen st)))
                        (set! (.-fullscreen st) on)
                        (log! token :fullscreen
                              (merge {:on on
                                      :drift (when-let [room (.-room st)]
                                               (round1 (sc/drift (.-currentTime video)
                                                                 (expected-now st room))))
                                      :rate (round3 (.-playbackRate video))
                                      :mode (.-mode st) :off-s (off-rate-s st)}
                                     (av-probe st video))))))]
    (set! (.-fullscreen st) (fullscreen?))
    (doseq [ev ["fullscreenchange" "webkitfullscreenchange"]]
      (.addEventListener js/document ev on-change))))

(defn- loops! [^js st refs]
  (let [{:keys [video seek lag-hint]} refs
        token (unchecked-get st "token")]
    (watch-fullscreen! st token video)
    (js/setInterval #(wsx/send! (.-ws st) {:type "ping" :t (ui/perf-now)}) 5000)
    (js/setInterval #(wsx/send! (.-ws st) {:type "status"
                                            :pos (.-currentTime video)
                                            :buffering (boolean (.-buffering st))})
                    2000)
    (js/setInterval #(quality-event! st token :tick) 5000)
    (js/setInterval #(apply-policy! st token video lag-hint) 500) ; smooth catch-up between beats
    (js/setInterval
      #(when (.-room st)
         (set! (.-max seek) (str (or (.-duration video) 0)))
         (when-not (.-seeking st)
           (set! (.-value seek) (str (.-currentTime video)))))
      1000)
    ;; stall watchdog: room says playing, but our clock hasn't moved in 10s
    ;; → log one snapshot per episode (that's the "spinner at 0:00" case)
    (let [wd (js-obj "t" -1 "at" 0 "reported" false)]
      (js/setInterval
        (fn []
          (let [t (.-currentTime video)
                now (ui/perf-now)]
            (if (>= (sc/abs (- t (.-t wd))) 0.05)
              (do (set! (.-t wd) t) (set! (.-at wd) now) (set! (.-reported wd) false))
              (when (and (.-started st) (:playing (.-room st))
                         (not (.-ended video))
                         (> (- now (.-at wd)) 10000)
                         (not (.-reported wd)))
                (set! (.-reported wd) true)
                (log! (unchecked-get st "token") :stalled
                      {:t (Math/round (* 10 t)) :ready (.-readyState video)
                       :paused (.-paused video) :net (.-networkState video)
                       :dur (Math/round (* 10 (or (.-duration video) 0)))
                       :src (some-> (.-currentSrc video) (.slice -30))
                       :buf (buffered video)})))))
        4000))))

(defn- mount! [^js st token root]
  (let [refs (shell st)
        started (js-obj "done" false)]
    ;; the stylesheet lets the watch page run wide (YouTube-style stage)
    (.add (.-classList (.-body js/document)) "watching")
    (connect! st token refs)
    (keys! st (:video refs))
    (js/setInterval
      (fn []
        (when (and (not (.-done started)) (.-room st))
          (set! (.-done started) true)
          (when (.-cancontrol st)
            (set! (.-display (.-style (:host-row refs))) "flex")
            (when-not (.-ishost st)
              (set! (.-textContent (:badge refs)) "co-hosting")))
          (loops! st refs)))
      800)
    (ui/add! root (:layout refs))))

(defn page [token]
  (let [^js st (js-obj "offset" 0 "samples" [] "buffering" false "ishost" false
                  "cancontrol" false "started" false "token" token "mode" :none
                  "auto" true "qcap" nil "ourseek" 0
                  "streamok" false "starting" false "autostart" nil
                  "ratechanged" nil "offrate" 0 "offsince" nil "fullscreen" false
                  "counters" (js-obj "seeks" 0 "stalls" 0
                                     "rejected" 0 "capsteps" 0 "autostarts" 0))
        saved-nick (ui/store-get "goob-nick")
        root (h :div {})]
    (set! (.-goobProbe js/window)
          (fn []
            (let [room (.-room st) ^js v (ui/$ "video")]
              (clj->js (merge
                        (when v (av-probe st v)) ; :dropped-frames :total-frames :audio-buffer :video-buffer
                       {:t (ui/perf-now)
                        :currentTime (some-> v .-currentTime)
                        :expected (when room (expected-now st room))
                        :drift (when (and room v) (sc/drift (.-currentTime v) (expected-now st room)))
                        :rate (some-> v .-playbackRate) :roomRate (:rate room)
                        :playing (:playing room) :paused (some-> v .-paused)
                        :muted (some-> v .-muted) :started (.-started st)
                        :autostart (.-autostart st) :starting (.-starting st)
                        :mode (name (.-mode st)) :buffering (.-buffering st)
                        :bufferAhead (when v (buffer-ahead v))
                        :qcap (:cap (.-qcap st)) :offset (.-offset st)
                        :rate-changed-ms (when-let [t (.-ratechanged st)]
                                           (Math/round (- (ui/perf-now) t)))
                        :off-rate-s (off-rate-s st)
                        :fullscreen (boolean (.-fullscreen st))
                        :counters (js->clj (.-counters st))})))))
    (if (str/blank? saved-nick)
      (let [input (h :input {:type "text" :placeholder "your name"})
            join-btn (h :button.btn "join the goo")
            card (h :div.card
                    (h :div.logo "goob-toob")
                    (h :p "slime into the room — what's your name?")
                    input
                    join-btn)
            join! (fn []
                    (let [n (str/trim (.-value input))]
                      (when-not (empty? n)
                        (ui/store-set! "goob-nick" n)
                        (set! (.-nick st) n)
                        (mount! st token (ui/clear! root)))))]
        (set! (.-onclick join-btn) join!)
        (set! (.-onkeydown input) #(when (= "Enter" (.-key %)) (join!)))
        (ui/add! root (h :div.overlay card)))
      (do (set! (.-nick st) saved-nick)
          (mount! st token root)))
    root))
