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
      (when (= mode :seek-back)
        (bump! st "seekbacks")
        (log! token :seek-back {:drift (/ (Math/round (* 10 d)) 10)
                                :offset (Math/round (.-offset st))
                                :rate (:rate room)})
        (mark-seek! st)
        (set! (.-currentTime video) expected))
      (when (> (sc/abs (- (.-playbackRate video) rate)) 0.001)
        (set! (.-playbackRate video) rate))
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

(defn- append-chat! [log-el pill-el msg]
  ;; stick to the bottom only if the reader was already there; otherwise
  ;; surface the "new messages" pill and leave their scroll alone
  (let [stick (near-bottom? log-el)]
    (ui/add! log-el
      (h :div.bubble
         (h :span.who {:style (str "color:" (ui/nick-color (:from msg)))} (:from msg))
         (h :div (:text msg))))
    (if stick
      (set! (.-scrollTop log-el) (.-scrollHeight log-el))
      (set! (.-display (.-style pill-el)) "block"))))

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
  (let [{:keys [^js video title-el seek res-slot peers-el chat-log new-msgs admin-link
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
          "chat"  (append-chat! chat-log new-msgs msg)
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
        stage-wrap (h :div.stage-wrap video)
        lag-hint (h :span.muted {})
        slow-note (h :span.muted {})
        title-el (h :h3 {:style "margin:0;color:var(--slime)"} "joining the goo…")
        admin-link (h :a {:href "/admin" :title "admin console"
                          :style "margin-left:auto;font-size:0.8rem;opacity:0.7;display:none"}
                      "admin?")
        play-btn (h :button.btn "▶ play")
        pause-btn (h :button.btn.aqua "⏸ pause")
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
          (log! (unchecked-get st "token") :seek-unexplained
                {:to (Math/round (.-currentTime video))
                 :drift (when-let [room (.-room st)]
                          (let [d (sc/drift (.-currentTime video) (expected-now st room))]
                            (/ (Math/round (* 10 d)) 10)))
                 :buf (buffered video) :ready (.-readyState video) :paused (.-paused video)}))))
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
    (set! (.-onchange seek)
          #(do (set! (.-seeking st) false)
               (wsx/send! (.-ws st) {:type "cmd" :op "seek"
                                      :pos (js/parseFloat (.-value seek))})))
    (set! (.-oninput seek) #(set! (.-seeking st) true))
    (ui/add! viewer-row res-slot (h :span.muted "picture quality"))
    (ui/add! host-row play-btn pause-btn seek badge (h :span.muted "everyone follows you") slow-note)
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
     :chat-log chat-log :peers-el peers-el :host-row host-row
     :badge badge :new-msgs new-msgs :lag-hint lag-hint :slow-note slow-note}))

(defn- loops! [^js st refs]
  (let [{:keys [video seek lag-hint]} refs
        token (unchecked-get st "token")]
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
    (connect! st token refs)
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
                  "counters" (js-obj "seeks" 0 "stalls" 0 "seekbacks" 0
                                     "rejected" 0 "capsteps" 0 "autostarts" 0))
        saved-nick (ui/store-get "goob-nick")
        root (h :div {})]
    (set! (.-goobProbe js/window)
          (fn []
            (let [room (.-room st) ^js v (ui/$ "video")]
              (clj->js {:t (ui/perf-now)
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
                        :counters (js->clj (.-counters st))}))))
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
