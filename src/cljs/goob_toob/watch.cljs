(ns goob-toob.watch
  "The watch page: dash.js player parked at the room's position — the video
  element's own play button is the join gesture — then server-synced playback
  with gradual catch-up, resolution lock, peers, chat and the room's queue
  (the server switches the item; this page swaps the source in place)."
  (:require [clojure.string :as str]
            [goob-toob.ui :as ui :refer [h]]
            [goob-toob.ws :as wsx]
            [goob-toob.player :as player]
            [goob-toob.sync-core :as sc]))

(defn- play! [video]
  ;; browsers reject .play() without a user gesture; the video's native play
  ;; button supplies one. Swallow the rejection anyway (e.g. autoplay-blocked).
  (try (.catch (.play video) (fn [_] nil)) (catch js/Error _ nil)))

;; ---- #57 telemetry: the playback stream, on a budget ----
;; Every started viewer samples its playback once a second and uploads ONE
;; chunk every tele-chunk-ms (5 min) to /api/telemetry, plus one sendBeacon
;; with the unsent tail on pagehide. Nothing is ever sent more often: the
;; auto trigger only drops a marker into the stream, and it leaves with the
;; next chunk. There is no "report" button: every session is recorded, so
;; the analysis finds the episode without anyone pressing anything. Budget: a sample is one
;; 16-column positional row (tele-header), ~60 B as JSON; a full 300-sample
;; chunk is ~18 KB; 12 requests and ~220 KB per viewer per hour at most.
;; Paused or not started: one "idle" marker, then no samples until playback
;; resumes, and a chunk with no new samples is not sent. A 413 or 429 from
;; the server drops the chunk; nothing retries. One watch page per
;; document, so the state lives here and log! can mark the stream.

(def ^:private tele-chunk-ms 300000)
(def ^:private tele-max-rows 600)    ; ~36 KB: a chunk stays under the server's 64 KB cap however long the interval slips
(def ^:private tele-max-events 60)   ; ~15 KB at most: a dash.js fault storm must not bloat a chunk either
(def ^:private tele-event-chars 240) ; a clientlog line's data, as text, clipped

(def ^:private tele-header
  ;; ms since the chunk's t0, currentTime, expected, drift, rate, room rate,
  ;; paused, buffering, readyState, audio/video buffer s, dropped/total
  ;; frames, quality index, quality cap, fullscreen (booleans as 0/1)
  ["t" "ct" "exp" "d" "r" "rr" "p" "b" "rs" "ab" "vb" "df" "tf" "q" "qc" "fs"])

(def ^:private tele
  (js-obj "session" (.slice (.toString (js/Math.random) 36) 2 12)
          "t0" nil "rows" (array) "events" (array) "seq" 0 "sent" 0
          "active" false "idled" false "recent" (array)
          "autoat" nil "autofired" (js-obj)))

(defn- event!
  "A marker in the telemetry stream: every clientlog line, the button, the
  auto trigger, idle/resume. m is merged in (:data, :note, :reason)."
  [type m]
  (let [evs (.-events tele)]
    (when (< (.-length evs) tele-max-events)
      (.push evs (merge {:t (js/Date.now) :type type} m)))))

(defn- log! [token ev data]
  ;; Fire-and-forget playback diagnostics -> server.log (see routes/clientlog):
  ;; the user just sends the log file, no devtools required. The same line
  ;; marks the telemetry stream (#57).
  (event! (if (keyword? ev) (name ev) (str ev)) {:data (.slice (pr-str data) 0 tele-event-chars)})
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

(defn- quality-now [^js st]
  ;; dash.js throws on its quality index between an attach and stream-init
  (when (and (.-player st) (.-streamok st))
    (try (player/current-quality (.-player st)) (catch js/Error _ nil))))

(defn- snapshot
  "The per-second readout goobProbe() and the telemetry sampler share."
  [^js st ^js video]
  (let [room (.-room st)
        e (when room (expected-now st room))]
    (merge (av-probe st video)
           {:currentTime (.-currentTime video)
            :expected e
            :drift (when e (sc/drift (.-currentTime video) e))
            :rate (.-playbackRate video) :roomRate (:rate room)
            :paused (.-paused video) :buffering (boolean (.-buffering st))
            :readyState (.-readyState video)
            :quality (quality-now st) :qcap (:cap (.-qcap st))
            :fullscreen (boolean (.-fullscreen st))})))

(defn- bit [x] (if x 1 0))
(defn- round2 [x] (/ (Math/round (* 100 x)) 100))

(defn- tele-row
  "A snapshot as a tele-header row (av-probe already rounds the buffers)."
  [t0 now s]
  [(- now t0) (round2 (:currentTime s)) (some-> (:expected s) round2) (some-> (:drift s) round3)
   (round3 (:rate s)) (some-> (:roomRate s) round3) (bit (:paused s)) (bit (:buffering s))
   (:readyState s) (:audio-buffer s) (:video-buffer s) (:dropped-frames s) (:total-frames s)
   (:quality s) (:qcap s) (bit (:fullscreen s))])

(def ^:private auto-every-ms 600000)

(defn- auto-reason
  "A plausible desync, from the cumulative off-rate time and the last 30 s
  of samples: a minute off 1.0x in total, where the pitch-preserving
  stretcher has had its chance (#57 suspect 1); or 30+ dropped frames over
  30 s of unbroken playback with the audio buffer healthy (>= 2 s), the
  picture failing to keep up with the sound."
  [recent off-s]
  (cond
    (>= off-s 60) "off-rate-60s"
    (and (>= (count recent) 30)
         (every? #(and (not (:paused %)) (not (:buffering %))
                       (>= (or (:audio-buffer %) 0) 2))
                 recent)
         (>= (- (or (:dropped-frames (last recent)) 0)
                (or (:dropped-frames (first recent)) 0))
             30))
    "dropped-frames-30s"))

(defn- auto-mark! [reason]
  ;; each reason once per page, and never two auto markers inside 10 min
  (let [now (js/Date.now)]
    (when (and (not (unchecked-get (.-autofired tele) reason))
               (or (nil? (.-autoat tele)) (> (- now (.-autoat tele)) auto-every-ms)))
      (unchecked-set (.-autofired tele) reason true)
      (set! (.-autoat tele) now)
      (event! "auto" {:reason reason}))))

(defn- tele-sample! [^js st ^js video]
  (let [now (js/Date.now)
        s (snapshot st video)
        rows (.-rows tele)
        recent (.-recent tele)]
    (when-not (.-t0 tele) (set! (.-t0 tele) now))
    (when (< (.-length rows) tele-max-rows)
      (.push rows (tele-row (.-t0 tele) now s)))
    (.push recent s)
    (when (> (.-length recent) 30) (.shift recent))
    (when-let [reason (auto-reason (array-seq recent) (off-rate-s st))]
      (auto-mark! reason))))

(defn- tele-tick!
  "Once a second: a sample while started and playing; on the way into a
  pause (or an item switch's restart) one idle marker, and a resume
  marker on the way back."
  [^js st ^js video]
  (let [active? (boolean (and (.-started st) (.-vid st) (not (.-paused video))))]
    (cond
      active?
      (do (when (and (not (.-active tele)) (.-idled tele)) (event! "resume" {}))
          (set! (.-active tele) true)
          (tele-sample! st video))

      (.-active tele)
      (do (set! (.-active tele) false)
          (set! (.-idled tele) true)
          (event! "idle" {})))))

(defn- tele-flush!
  "The unsent rows and markers as one chunk; nothing when no row is new.
  beacon? is the pagehide path (sendBeacon survives the unload). The
  envelope carries once what every sample would repeat (nick, vid, ua)."
  [^js st token beacon?]
  (when (pos? (.-length (.-rows tele)))
    (let [body (js/JSON.stringify
                 (clj->js {:v 1 :token token :nick (.-nick st) :vid (.-vid st)
                           :session (.-session tele) :seq (.-seq tele) :t0 (.-t0 tele)
                           :ua (.-userAgent js/navigator)
                           :header tele-header
                           :samples (vec (array-seq (.-rows tele)))
                           :events (vec (array-seq (.-events tele)))}))]
      (set! (.-rows tele) (array))
      (set! (.-events tele) (array))
      (set! (.-t0 tele) nil)
      (set! (.-seq tele) (inc (.-seq tele)))
      (set! (.-sent tele) (+ (.-sent tele) (.-length body)))
      (try
        (if beacon?
          (.sendBeacon js/navigator "/api/telemetry"
                       (js/Blob. #js [body] #js {:type "application/json"}))
          (-> (js/fetch "/api/telemetry"
                        #js {:method "post"
                             :headers #js {"Content-Type" "application/json"}
                             :body body})
              (.catch (fn [_] nil))))
        (catch js/Error _ nil)))))

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
    ;; dash.js throws on its bitrate list before the stream is initialized
    ;; (between an attach and its stream-init, or with no source at all)
    (when (and (.-auto st) (.-streamok st))
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

(def ^:private end-hint "that's the end of the queue")

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
            (cond
              (.-ended st) end-hint
              (< d -10.0)  (str "you're " (int (- d)) "s behind the room — reload to jump to it")
              :else        "")))))

;; ---- viewer audio: boost above the stream's own 100 % (#56) ----
;; Per viewer, never room state. The element's output runs through a Web
;; Audio chain, element → gain (0–3) → compressor → out, so the volume can
;; go past the stream's own loudness, VLC's layout: native 100 % sits a
;; third of the way along a 0–300 % bar. The element's volume stays 1 and
;; muted keeps working on the element (both apply before the capture), the
;; native control bar's own slider scales the boosted signal like VLC's
;; fine control, and none of it touches playback: the chain is downstream
;; of the element, so the quality cap, the sync policy and preservesPitch
;; never see it. An AudioContext starts suspended until a user gesture
;; (Safari resumes it only inside the gesture's handler), and a
;; MediaElementSource takes the element's output for good, so the context
;; is made and resumed inside the first gesture on the page and the source
;; only once the context reports running. A browser without AudioContext,
;; or one that refuses the source, keeps the audio native and loses the
;; control (one clientlog line says so).

(def ^:private volume-key "goob-volume")
(def ^:private volume-max 300)
(def ^:private volume-step 5)

(defn- clamp-volume [pct]
  (max 0 (min volume-max pct)))

(defn- saved-volume []
  (let [n (js/parseInt (or (ui/store-get volume-key) "") 10)]
    (if (js/isNaN n) 100 (clamp-volume n))))

(defn- compressor-threshold
  "dB. Transparent at native and below (nothing exceeds 0 dBFS before the
  gain), easing to −12 dB by 200 % so a boosted loud passage squashes
  instead of crackling."
  [pct]
  (* -12.0 (max 0.0 (min 1.0 (/ (- pct 100) 100.0)))))

(defn- apply-volume! [^js a pct]
  ;; the chain follows the setting once it exists
  (when-let [^js g (.-gainnode a)]
    (set! (.-value (.-gain g)) (/ pct 100.0))
    (set! (.-value (.-threshold ^js (.-compressor a))) (compressor-threshold pct))))

(defn- volume!
  "The viewer's volume to pct (0–300, clamped): the chain, the slider, its
  label and localStorage."
  [^js st pct]
  (let [^js a (.-audio st)
        pct (clamp-volume (Math/round pct))
        boost? (> pct 100)]
    (set! (.-pct a) pct)
    (ui/store-set! volume-key (str pct))
    (apply-volume! a pct)
    (when-let [^js s (.-slider a)]
      (set! (.-value s) (str pct))
      (.toggle (.-classList s) "boost" boost?))
    (when-let [^js l (.-label a)]
      (set! (.-textContent l) (str pct " %"))
      (.toggle (.-classList l) "boost" boost?))))

(defn- volume-key-step
  "ArrowUp/ArrowDown -> ±5 %, else nil"
  [k]
  (case k "ArrowUp" volume-step "ArrowDown" (- volume-step) nil))

(defn- audio-native!
  "No chain: the audio stays as the element plays it and the control goes
  away. Logged once."
  [^js st token why]
  (let [^js a (.-audio st)]
    (when-not (.-dead a)
      (set! (.-dead a) true)
      (when-let [^js ctx (.-ctx a)]
        (set! (.-ctx a) nil)
        (try (.close ctx) (catch js/Error _ nil)))
      (when-let [^js w (.-wrap a)]
        (set! (.-display (.-style w)) "none"))
      (log! token :audio-native {:why why}))))

(defn- build-chain!
  "element → gain → compressor (ratio 4, knee 20, threshold per the
  setting) → out, once, on a running context."
  [^js st token ^js video]
  (let [^js a (.-audio st) ^js ctx (.-ctx a)]
    (when (and ctx (not (.-gainnode a)) (not (.-dead a)))
      (try
        (let [src (.createMediaElementSource ctx video)
              g (.createGain ctx)
              c (.createDynamicsCompressor ctx)]
          (set! (.-value (.-ratio c)) 4)
          (set! (.-value (.-knee c)) 20)
          (.connect src g)
          (.connect g c)
          (.connect c (.-destination ctx))
          (set! (.-gainnode a) g)
          (set! (.-compressor a) c)
          (apply-volume! a (.-pct a))
          (log! token :audio-chain {:pct (.-pct a) :sample-rate (.-sampleRate ctx)}))
        (catch js/Error e
          (audio-native! st token (str "source: " (some-> e .-message))))))))

(defn- audio-start!
  "Make the context on the first call, resume it on every one until it
  runs, and build the chain once it does. Called at mount (inside the
  join click for a first-time viewer; at page load for a returning one,
  where a strict browser keeps it suspended and nothing is taken from the
  element until a gesture resumes it, while a lenient one runs it at once
  and the saved boost is on from the first frame) and inside every user
  gesture after that."
  [^js st token ^js video]
  (let [^js a (.-audio st)]
    (when-not (.-dead a)
      (when-not (.-ctx a)
        (if-let [ctor (or (.-AudioContext js/window) (unchecked-get js/window "webkitAudioContext"))]
          (try
            (let [^js ctx (new ctor)]
              (set! (.-ctx a) ctx)
              (.addEventListener ctx "statechange"
                                 #(when (= "running" (.-state ctx)) (build-chain! st token video))))
            (catch js/Error e
              (audio-native! st token (str "context: " (some-> e .-message)))))
          (audio-native! st token "no AudioContext")))
      (when-let [^js ctx (.-ctx a)]
        (if (= "running" (.-state ctx))
          (build-chain! st token video)
          (try (.catch (.resume ctx) (fn [_] nil)) (catch js/Error _ nil)))))))

(defn- audio-gestures!
  "The events that carry a user activation; each is a chance to start the
  context. keydown covers Space on the video, pointerdown the click on it
  and the buttons, touchend iOS (a touch's pointerdown activates nothing)."
  [^js st token ^js video]
  (audio-start! st token video)
  (doseq [ev ["keydown" "pointerdown" "touchend"]]
    (.addEventListener js/document ev #(audio-start! st token video) true)))

(defn- volume-control
  "The per-viewer volume slider, 0–300 % of the stream's own with native
  100 % a third of the way along (a tick marks it, the colour changes past
  it), labelled with the percentage; double-click resets to 100 %.
  Remembered in localStorage (goob-volume), never room state. Hidden once
  the chain is known to be impossible."
  [^js st]
  (let [^js a (.-audio st)
        slider (h :input.vol {:type "range" :min 0 :max volume-max :step volume-step
                              :title "volume, up to 300 % of the stream's own (double-click: 100 %)"})
        label (h :span.vol-pct {})
        wrap (h :span.volume {}
                (h :span.vol-track {} slider (h :span.vol-tick {:aria-hidden "true"}))
                label)]
    (set! (.-slider a) slider)
    (set! (.-label a) label)
    (set! (.-wrap a) wrap)
    (set! (.-oninput slider) #(volume! st (js/parseInt (.-value slider) 10)))
    (set! (.-ondblclick slider) #(volume! st 100))
    (when (.-dead a) (set! (.-display (.-style wrap)) "none"))
    (volume! st (.-pct a))
    wrap))

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
                (if (= "AbortError" (some-> err .-name))
                  ;; the source changed under a play() still in flight
                  ;; (switch-item!): not a block, nothing to park; the new
                  ;; stream's on-ready settles it and started stays as it is
                  (log! token :autostart-aborted {})
                  (do (set! (.-started st) false)
                      (outcome! st token "blocked" :autostart-blocked {:err (some-> err .-name)})
                      (park! st video)))))))

(defn- settle!
  "Run the start path on the current stream; :none when the room has no
  video (an empty queue: nothing to park, nothing to start)."
  [^js st token video]
  (if-not (.-vid st)
    :none
    (let [action (sc/start-action :state {:started? (boolean (.-started st))
                                          :playing? (boolean (:playing (.-room st)))
                                          :can-control? (boolean (.-cancontrol st))})]
      (case action
        :autostart (if (and (ready? st video) (not (.-starting st)))
                     (autostart! st token video)
                     (park! st video))
        :park      (park! st video)
        :none      nil)
      action)))

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
  (when-let [room (when (and (.-cancontrol st) (.-vid st)) (.-room st))]
    (wsx/send! (.-ws st) {:type "cmd" :op "seek"
                          :pos (jump-target (expected-now st room) n (room-duration st video))})
    true))

(defn- toggle!
  "Space/K: pause a playing room, play a paused one, both at the expected
  position. A controller who has not started yet while the room plays is
  left alone (nil): Space stays the join gesture there, and the keypress
  already handed the autostart retry its activation."
  [^js st]
  (when-let [room (when (and (.-cancontrol st) (.-vid st)) (.-room st))]
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
  ;; the chat and nick inputs, the seek and volume sliders and the
  ;; resolution menu keep their keys; a focused button keeps Space (its own
  ;; activation)
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
  the native toggle as the join gesture.

  ArrowUp/ArrowDown are the viewer's volume (#56, ±5 %), for everyone,
  controller or not, and in the capture phase for the same reason:
  Chromium's native controls take them as element volume at the focused
  <video>, which would fight the gain. Repeats are fine here, holding the
  key ramps the volume and nothing leaves the page."
  [^js st ^js video]
  (.addEventListener js/document "keydown"
    (fn [^js e]
      (let [k (.-key e)]
        (when-let [n (volume-key-step k)]
          (when-not (or (.-ctrlKey e) (.-metaKey e) (.-altKey e) (owns-key? (.-target e) k))
            (.preventDefault e)
            (volume! st (+ (.-pct ^js (.-audio st)) n))))))
    true)
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

(defn- doc-fs-element []
  (or (.-fullscreenElement js/document) (.-webkitFullscreenElement js/document)))

(defn- fs-element
  "What is fullscreen, if anything. iOS Safari fullscreens nothing but a
  <video>, and reports that on the element (webkitDisplayingFullscreen,
  webkitbegin/endfullscreen events), never on the document."
  [^js video]
  (or (doc-fs-element) (when (.-webkitDisplayingFullscreen video) video)))

(defn- video-fs!
  "iOS: the bare element goes fullscreen, no overlay. Throws before
  loadedmetadata and outside a user gesture; the click is the gesture."
  [^js video]
  (try (when (.-webkitEnterFullscreen video) (.webkitEnterFullscreen video))
       (catch js/Error _ nil)))

(defn- request-fs! [^js wrap ^js video]
  (try
    (cond
      (.-requestFullscreen wrap)
      (some-> (.requestFullscreen wrap) (.catch (fn [_] (video-fs! video))))

      (.-webkitRequestFullscreen wrap)
      (.webkitRequestFullscreen wrap)

      :else (video-fs! video))
    (catch js/Error _ nil)))

(defn- exit-fs! [^js video]
  (try
    (cond
      (and (.-webkitDisplayingFullscreen video) (not (doc-fs-element)))
      (.webkitExitFullscreen video)

      (.-exitFullscreen js/document)
      (some-> (.exitFullscreen js/document) (.catch (fn [_] nil)))

      (.-webkitExitFullscreen js/document)
      (.webkitExitFullscreen js/document))
    (catch js/Error _ nil)))

(defn- toggle-fs! [wrap video]
  (if (fs-element video) (exit-fs! video) (request-fs! wrap video)))

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
  (let [title! #(.setAttribute fs-btn "title" (if (fs-element video) "exit fullscreen" "fullscreen"))]
    (doseq [ev ["fullscreenchange" "webkitfullscreenchange"]]
      (.addEventListener js/document ev title!))
    (doseq [ev ["webkitbeginfullscreen" "webkitendfullscreen"]]
      (.addEventListener video ev title!)))
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

;; ---- the queue (playlists, part 3) ----
;; The server owns the queue and the current item: an `item` state on every
;; change (auto-advance, next/prev/jump, an admin edit that moves the
;; pointer), a `queue` message on every edit, and the current video id on
;; every state and beat. This page swaps the dash source in place and
;; re-runs the first-load start path on the new stream, so a viewer who
;; already pressed play follows on and one who never did parks on the new
;; video's live frame. No seek here closes drift: the only seeks are the
;; landings the start path already makes.

(defn- video-of
  "The wire's video descriptor, or nil for an empty room (the server sends
  :video nil; a descriptor without an :id reads the same)."
  [v]
  (when (:id v) v))

(defn- descriptor-for
  "A descriptor for a queued video from its queue entry (title, duration)
  and the dash path convention: a switch whose `item` broadcast was missed
  (the beat's video id disagrees with ours) has nothing else to go on."
  [^js st id]
  (let [e (first (filter #(= id (:video-id %)) (.-queue st)))]
    {:id id :title (or (:title e) "") :duration (:duration e)
     :dash (str "/dash/" id "/manifest.mpd")}))

(defn- stage-empty! [refs empty?]
  (let [{:keys [^js video ^js stage-wrap]} refs]
    (if empty?
      (do (.add (.-classList stage-wrap) "empty")
          (.removeAttribute video "controls"))
      (do (.remove (.-classList stage-wrap) "empty")
          (.setAttribute video "controls" "")))))

(defn- host-row-enabled! [refs on?]
  (doseq [^js el (:transport refs)]
    (set! (.-disabled el) (not on?))))

(defn- render-queue!
  "The \"up next\" card: one row per item in order, the current one .now,
  the ones before it .done, items not ready muted with their status. A row
  is a jump (cmd jump) when this client can control; the current row is
  not, a mis-click must not restart the video for everyone. prev follows
  the queue (off at the start, off without a video); next stays on at the
  end, where the server ends the queue."
  [^js st refs]
  (let [{:keys [queue-el ^js prev-btn ^js next-btn]} refs
        queue (vec (.-queue st))
        cur   (.-vid st)
        ids   (map :video-id queue)
        at    (first (keep-indexed (fn [i id] (when (= id cur) i)) ids))
        can?  (boolean (.-cancontrol st))]
    (ui/clear! queue-el)
    (if (empty? queue)
      (ui/add! queue-el (h :p.muted.qempty "the queue is empty"))
      (doseq [[i item] (map-indexed vector queue)]
        (let [now?   (= cur (:video-id item))
              ready? (= "ready" (:status item))
              row    (h :div.qrow {:class (not-empty (str/join " " (remove nil? [(when now? "now")
                                                                                  (when (and at (< i at)) "done")
                                                                                  (when (and can? (not now?)) "jumpable")])))
                                   :title (when (and can? (not now?)) "jump here (everyone follows)")}
                        (h :span.pos (str (inc i)))
                        (h :span.qtitle (:title item))
                        (h :span.muted (cond now?         "now playing"
                                             (not ready?) (:status item)
                                             :else        (or (ui/fmt-time (:duration item)) ""))))]
          (when (and can? (not now?))
            (set! (.-onclick row)
                  #(wsx/send! (.-ws st) {:type "cmd" :op "jump" :video (:video-id item)})))
          (ui/add! queue-el row))))
    (set! (.-disabled prev-btn) (not (and cur (sc/neighbour ids cur :prev))))
    (set! (.-disabled next-btn) (nil? cur))))

(defn- create-player!
  "The dash player on the stage, made once: on the welcome's video, or on
  the first `item` that carries one in a room that opened empty."
  [^js st token refs dash]
  (let [{:keys [^js video res-slot]} refs]
    (set! (.-preservesPitch video) true)
    (set! (.-webkitPreservesPitch video) true)
    (.addEventListener video "loadeddata" #(settle! st token video))
    (.addEventListener video "error"
      #(log! token :video-error {:code (some-> (.-error video) .-code)
                                 :msg (some-> (.-error video) .-message)}))
    (set! (.-player st)
      (player/create video dash (.-startkbps st)
        (fn [_ctx]
          ;; fires again on every attach!: the res menu, the stream flag
          ;; and the start path are per stream
          (log! token :stream-init {:dash (.-dash st) :ua (.-userAgent js/navigator)})
          (ui/clear! res-slot)
          (ui/add! res-slot (res-menu st))
          (set! (.-streamok st) true)
          (settle! st token video))))
    ;; faults must be wired NOW, not on streamInitialized —
    ;; a broken manifest never initializes, that's the point
    (player/on-faults (.-player st)
      (fn [d] (log! token (:ev d) (dissoc d :ev))))))

(defn- reset-start!
  "The state the start path reads, back to first-load: the new stream is
  settled, logged and probed fresh (begin! re-seeks to the room position,
  the outcome is logged again, the harness reads autostart to tell an
  autostart from a gesture). `starting` is left alone: a play() still in
  flight on the old source rejects with AbortError when the source
  changes, and autostart!'s catch ignores that one."
  [^js st]
  (set! (.-ended st) false)
  (set! (.-started st) false)
  (set! (.-autostart st) nil)
  (set! (.-mode st) :none)
  (set! (.-qcap st) nil)
  (set! (.-buffering st) false)
  (set! (.-streamok st) false))

(defn- switch-item!
  "Point the page at video: title, duration, the quality cap off, the
  source swapped (or the player made, for a room that opened empty), the
  queue card re-marked. The dash on-ready then settles the new stream."
  [^js st token refs {:keys [id title duration dash]}]
  (let [{:keys [^js video title-el lag-hint]} refs]
    (set! (.-vid st) id)
    (set! (.-dash st) dash)
    (set! (.-duration st) duration)
    (reset-start! st)
    (set! (.-playbackRate video) 1.0)
    (set! (.-textContent title-el) title)
    (set! (.-textContent lag-hint) "")
    (stage-empty! refs false)
    (host-row-enabled! refs true)
    (log! token :item {:id id})
    (if-let [ctx (.-player st)]
      (do (player/set-max-kbps ctx -1)
          (player/attach! ctx dash))
      (create-player! st token refs dash))
    (set! (.-buffering st) false)
    (render-queue! st refs)))

(defn- empty-room!
  "No current video: the stage shows \"nothing queued\" over an empty
  picture, the host row is off, the queue card says so. A later `item`
  attaches as a normal switch."
  [^js st token refs]
  (let [{:keys [title-el lag-hint]} refs]
    (set! (.-vid st) nil)
    (set! (.-dash st) nil)
    (set! (.-duration st) nil)
    (reset-start! st)
    (set! (.-textContent title-el) "nothing queued")
    (set! (.-textContent lag-hint) "")
    (stage-empty! refs true)
    (host-row-enabled! refs false)
    (log! token :item {:id nil})
    (when-let [ctx (.-player st)]
      (player/attach! ctx nil))
    (set! (.-buffering st) false)
    (render-queue! st refs)))

(defn- follow-video!
  "A state, beat or queue message names the room's video; when it is not
  ours an `item` broadcast was missed (a reconnect blip), so switch from
  the queue entry, or go blank when the room emptied."
  [^js st token refs msg]
  (when (contains? msg :video-id)
    (let [id (:video-id msg)]
      (when (not= id (.-vid st))
        (if id
          (switch-item! st token refs (descriptor-for st id))
          (empty-room! st token refs))))))

(defn- apply-state-msg! [^js st token refs msg]
  (let [{:keys [^js video lag-hint]} refs]
    (if (= "item" (:op msg))
      (if-let [v (video-of (:video msg))]
        (switch-item! st token refs v)
        (empty-room! st token refs))
      (follow-video! st token refs msg))
    (set! (.-room st) msg)
    ;; only the end of the queue (a pause past the last video) carries
    ;; :ended true; any later op clears it
    (set! (.-ended st) (boolean (:ended msg)))
    (when (.-ended st) (set! (.-textContent lag-hint) end-hint))
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
      nil)))

(defn- host-ui!
  "The host row and its badge follow the last welcome: shown for a
  controller (\"you drive\" for the host, \"co-hosting\" for a viewer the
  room lets control), hidden otherwise. A welcome after a reconnect can
  change the answer either way (#71): a server that came back without
  this tab's login demotes it — the row goes (the keys are already off,
  cancontrol gates them) and the login hint says what to do; a login made
  meanwhile in another tab promotes it."
  [^js st refs]
  (let [{:keys [host-row badge login-hint]} refs]
    (set! (.-display (.-style host-row)) (if (.-cancontrol st) "flex" "none"))
    (set! (.-textContent badge) (if (.-ishost st) "you drive" "co-hosting"))
    (set! (.-display (.-style login-hint)) (if (.-expired st) "" "none"))))

(defn- connect! [^js st token refs]
  (let [{:keys [^js video title-el seek peers-el chat-log fs-chat new-msgs admin-link
                lag-hint slow-note]} refs
        proto (if (= "https:" (.-protocol js/location)) "wss:" "ws:")
        url (str proto "//" (.-host js/location) "/ws/room/" token
                 "?nick=" (js/encodeURIComponent (.-nick st)))]
    (wsx/connect
      url
      (fn [msg _ws]
        (case (:type msg)
          "welcome"
          (let [v         (video-of (:video msg))
                again?    (some? (.-room st)) ; a reconnect, not the first welcome
                was-host? (boolean (.-ishost st))]
            (set! (.-ishost st) (:is-host msg))
            (when (:is-host msg)
              (set! (.-display (.-style admin-link)) ""))
            (set! (.-cancontrol st) (boolean (:can-control msg)))
            (set! (.-startkbps st) (:start-kbps msg))
            (set! (.-queue st) (vec (:queue msg)))
            (set! (.-room st) (:state msg))
            ;; mount! reveals the host row once the first welcome is in; a
            ;; later one re-decides it (#71). "expired": this tab was the
            ;; host and the server no longer knows its login.
            (when again?
              (set! (.-expired st) (and was-host? (not (:is-host msg))))
              (when (not= was-host? (boolean (:is-host msg)))
                (log! token :host-change {:host (boolean (:is-host msg))}))
              (host-ui! st refs))
            (cond
              ;; an empty room, first time or still
              (nil? v)                    (empty-room! st token refs)
              ;; the first video (the player is made here), or a reconnect
              ;; across an item change
              (not= (:id v) (.-vid st))   (switch-item! st token refs v)
              ;; a plain reconnect
              (.-started st)              (do (render-queue! st refs)
                                              (resync! st token video))
              :else                       (render-queue! st refs)))
          "state" (apply-state-msg! st token refs msg)
          "queue" (do (set! (.-queue st) (vec (:queue msg)))
                      (follow-video! st token refs msg)
                      (render-queue! st refs))
          "beat"  (do (follow-video! st token refs msg)
                      (set! (.-room st) msg)
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
        stage-wrap (h :div.stage-wrap video fs-chat fs-btn
                      (h :div.stage-empty {:aria-hidden "true"} "nothing queued"))
        lag-hint (h :span.muted.lag-hint {})
        login-hint (h :a.muted.login-hint {:href "/admin" :style "display:none"}
                      "your login expired — log in again")
        slow-note (h :span.muted {})
        title-el (h :h3 {:style "margin:0;color:var(--slime)"} "joining the goo…")
        admin-link (h :a {:href "/admin" :title "admin console"
                          :style "margin-left:auto;font-size:0.8rem;opacity:0.7;display:none"}
                      "admin?")
        prev-btn (h :button.btn.ghost.jump {:title "previous video (everyone follows)"} "⏮ prev")
        next-btn (h :button.btn.ghost.jump {:title "next video (everyone follows)"} "next ⏭")
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
        queue-el (h :div.queue {})
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
    ;; the queue's transport: the server moves the room (item broadcast)
    (set! (.-onclick prev-btn) #(wsx/send! (.-ws st) {:type "cmd" :op "prev"}))
    (set! (.-onclick next-btn) #(wsx/send! (.-ws st) {:type "cmd" :op "next"}))
    ;; a clicked transport button must not keep focus, or the next Space
    ;; would re-press it instead of toggling the room (tab focus still works)
    (doseq [b [prev-btn back-btn play-btn pause-btn fwd-btn next-btn]]
      (set! (.-onmousedown b) #(.preventDefault %)))
    (set! (.-onchange seek)
          #(do (set! (.-seeking st) false)
               (wsx/send! (.-ws st) {:type "cmd" :op "seek"
                                      :pos (js/parseFloat (.-value seek))})))
    (set! (.-oninput seek) #(set! (.-seeking st) true))
    (stage! stage-wrap video fs-btn)
    (ui/add! viewer-row res-slot (h :span.muted "picture quality")
             (volume-control st) (fs-setting st fs-chat))
    (ui/add! host-row prev-btn back-btn play-btn pause-btn fwd-btn next-btn seek badge
             (h :span.muted "everyone follows you") slow-note)
    {:video video
     :layout (h :div.watch-layout
                (h :div.row-gap
                   (h :div.card.row-gap
                      (h :div {:style "display:flex;align-items:center;gap:0.5rem"}
                         title-el admin-link)
                      stage-wrap
                      lag-hint
                      login-hint
                      viewer-row
                      host-row))
                (h :div.row-gap
                   (h :div.card
                      (h :h3 "watching with")
                      peers-el)
                   (h :div.card.queue-card
                      (h :h3 "up next")
                      queue-el)
                    (h :div.card.chat
                       (h :h3 "chat")
                       chat-log
                       new-msgs
                       (h :div.row {}
                          chat-input
                          (h :button.btn.aqua {:onclick send-chat} "💬")))))
     :title-el title-el :seek seek :res-slot res-slot :admin-link admin-link
     :chat-log chat-log :fs-chat fs-chat :peers-el peers-el :host-row host-row
     :badge badge :new-msgs new-msgs :lag-hint lag-hint :login-hint login-hint
     :slow-note slow-note
     :stage-wrap stage-wrap :queue-el queue-el :prev-btn prev-btn :next-btn next-btn
     ;; what an empty room switches off (prev/next follow the queue card)
     :transport [back-btn play-btn pause-btn fwd-btn seek]}))

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
    ;; #57 telemetry: 1 Hz samples, one chunk per tele-chunk-ms, the tail
    ;; by beacon when the page goes
    (js/setInterval #(tele-tick! st video) 1000)
    (js/setInterval #(tele-flush! st token false) tele-chunk-ms)
    (.addEventListener js/window "pagehide" #(tele-flush! st token true))
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
    (audio-gestures! st token (:video refs))
    (js/setInterval
      (fn []
        (when (and (not (.-done started)) (.-room st))
          (set! (.-done started) true)
          (host-ui! st refs)
          (loops! st refs)))
      800)
    (ui/add! root (:layout refs))))

(defn page [token]
  (let [^js st (js-obj "offset" 0 "samples" [] "buffering" false "ishost" false
                  "cancontrol" false "started" false "token" token "mode" :none
                  "auto" true "qcap" nil "ourseek" 0
                  "streamok" false "starting" false "autostart" nil
                  "queue" [] "vid" nil "ended" false "dash" nil "startkbps" nil
                  "ratechanged" nil "offrate" 0 "offsince" nil "fullscreen" false
                  "expired" false
                  "audio" (js-obj "ctx" nil "gainnode" nil "compressor" nil "pct" (saved-volume)
                                  "slider" nil "label" nil "wrap" nil "dead" false)
                  "counters" (js-obj "seeks" 0 "stalls" 0
                                     "rejected" 0 "capsteps" 0 "autostarts" 0))
        saved-nick (ui/store-get "goob-nick")
        root (h :div {})]
    (set! (.-goobProbe js/window)
          (fn []
            (let [room (.-room st) ^js v (ui/$ "video")]
              (clj->js (merge
                        ;; :currentTime :expected :drift :rate :roomRate :paused
                        ;; :buffering :readyState :quality :qcap :fullscreen and
                        ;; av-probe's :dropped-frames :total-frames :audio-buffer
                        ;; :video-buffer — the telemetry sampler's row, unrounded
                        (when v (snapshot st v))
                        {:t (ui/perf-now)
                         :expected (when room (expected-now st room))
                         :playing (:playing room)
                         :muted (some-> v .-muted) :started (.-started st)
                         :volume (some-> v .-volume)
                         :gain (some-> ^js (.-gainnode ^js (.-audio st)) .-gain .-value)
                         :autostart (.-autostart st) :starting (.-starting st)
                         :mode (name (.-mode st)) :buffering (.-buffering st)
                         :bufferAhead (when v (buffer-ahead v))
                         :qcap (:cap (.-qcap st)) :offset (.-offset st)
                         :rate-changed-ms (when-let [t (.-ratechanged st)]
                                            (Math/round (- (ui/perf-now) t)))
                         :off-rate-s (off-rate-s st)
                         :fullscreen (boolean (.-fullscreen st))
                         :vid (.-vid st) :ended (boolean (.-ended st))
                         :telemetry {:pending (.-length (.-rows tele)) :events (.-length (.-events tele))
                                     :chunks (.-seq tele) :sent-bytes (.-sent tele)
                                     :active (.-active tele)}
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
