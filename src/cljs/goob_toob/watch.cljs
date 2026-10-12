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

(defn- expected-now [st room]
  (sc/expected-pos {:pos (:pos room) :t0 (:t0 room)
                    :now (+ (ui/perf-now) (.-offset st))
                    :playing? (:playing room)
                    :rate (:rate room)}))

(defn- park! [st video]
  ;; Sit paused at the room's position: the native play button is the
  ;; "press me" affordance, and a *real* seek is what makes dash composite
  ;; the frame there (same-value seek is a no-op -> black frame at pos 0).
  (when-let [room (.-room st)]
    (let [e (max 0.0 (expected-now st room))
          e (if (< (sc/abs (- (.-currentTime video) e)) 0.01) (+ e 0.001) e)]
      (.pause video)
      (try (set! (.-currentTime video) e) (catch js/Error _ nil)))))

(defn- apply-policy! [st video]
  (when (.-started st)
    (when-let [room (.-room st)]
      ;; while stalling, do nothing: seeking into an empty buffer is what
      ;; turns a stall into a stutter spiral
      (when-not (.-buffering st)
        (let [expected (expected-now st room)
              d (sc/drift (.-currentTime video) expected)]
          (case (sc/policy d)
            :none       (when-not (= 1.0 (.-playbackRate video))
                          (set! (.-playbackRate video) 1.0))
            :catch-up   (set! (.-playbackRate video) (sc/catch-rate d))
            :nudge-slow (set! (.-playbackRate video) sc/nudge-slow-rate)
            :seek       (do (set! (.-currentTime video) expected)
                            (set! (.-playbackRate video) 1.0))
            nil))))))

(defn- apply-state-msg! [st video msg]
  (set! (.-room st) msg)
  (if-not (.-started st)
    (park! st video) ;; unstarted: keep the parked frame fresh as the room moves
    (case (:op msg)
      "play"  (do (set! (.-currentTime video) (:pos msg))
                  (play! video))
      "pause" (do (.pause video)
                  (set! (.-currentTime video) (:pos msg))
                  (set! (.-playbackRate video) 1.0))
      "seek"  (set! (.-currentTime video) (:pos msg))
      nil)))

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

(defn- res-menu [st]
  (let [sel (h :select {:title "resolution"})
        saved (ui/store-get "goob-res")]
    (doseq [[v label] (concat [["auto" "auto (best sync)"]]
                              (for [r (player/video-representations (.-player st))]
                                [(str (:idx r)) (str (:height r) "p")]))]
      (.appendChild sel (doto (h :option {:value v})
                          (.appendChild (.createTextNode js/document label))))
      (when (= saved v) (set! (.-value sel) v)))
    (when (and saved (not= saved "auto"))
      (player/set-height (.-player st) (js/parseInt saved)))
    (set! (.-onchange sel)
          (fn []
            (let [v (.-value sel)]
              (ui/store-set! "goob-res" v)
              (if (= v "auto")
                (player/set-auto (.-player st))
                (player/set-height (.-player st) (js/parseInt v))))))
    sel))

(defn- connect! [st token refs]
  (let [{:keys [video title-el seek res-slot peers-el chat-log new-msgs]} refs
        proto (if (= "https:" (.-protocol js/location)) "wss:" "ws:")
        url (str proto "//" (.-host js/location) "/ws/room/" token
                 "?nick=" (js/encodeURIComponent (.-nick st)))]
    (wsx/connect
      url
      (fn [msg _ws]
        (case (:type msg)
          "welcome"
          (do (set! (.-ishost st) (:is-host msg))
              (set! (.-cancontrol st) (boolean (:can-control msg)))
              (set! (.-room st) (:state msg))
              (set! (.-textContent title-el) (get-in msg [:video :title]))
              (when-not (.-player st)
                (set! (.-preservesPitch video) true)     ; 1.5x catch-up ≠ chipmunks
                (set! (.-webkitPreservesPitch video) true)
                (set! (.-player st)
                  (player/create video (get-in msg [:video :dash])
                    (fn [ctx]
                      (ui/clear! res-slot)
                      (ui/add! res-slot (res-menu st))
                      (park! st video))))))
          "state" (apply-state-msg! st video msg)
          "beat"  (do (set! (.-room st) msg)
                      (apply-policy! st video)
                      (when-not (.-seeking st)
                        (set! (.-value seek) (str (.-currentTime video)))))
          "pong"  (let [t1 (ui/perf-now)
                        sample {:rtt (- t1 (:t msg))
                                :offset (sc/ntp-offset (:t msg) (:st msg) t1)}]
                    (set! (.-samples st) (take 8 (cons sample (.-samples st))))
                    (set! (.-offset st) (sc/best-offset (.-samples st)))
                    ;; the first pong is what makes `expected` trustworthy —
                    ;; keep the parked frame live until the viewer presses play
                    (when-not (.-started st) (park! st video)))
          "chat"  (append-chat! chat-log new-msgs msg)
          "peers" (render-peers! peers-el (:peers msg) (.-ishost st))
          "error" (set! (.-textContent title-el) (:message msg))
          nil))
      (fn [ws]
        (set! (.-ws st) ws)
        (set! (.-samples st) [])
        (wsx/send! ws {:type "ping" :t (ui/perf-now)})))))

(defn- shell [st]
  (let [video (.createElement js/document "video")
        _ (do (set! (.-className video) "stage")
              (.setAttribute video "controls" "")
              (.setAttribute video "playsinline" ""))
        stage-wrap (h :div.stage-wrap video)
        title-el (h :h3 {:style "margin:0;color:var(--slime)"} "joining the goo…")
        admin-link (h :a {:href "/admin" :title "admin console"
                          :style "margin-left:auto;font-size:0.8rem;opacity:0.7"}
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
    (set! (.-onwaiting video) #(set! (.-buffering st) true))
    (set! (.-onplaying video) #(set! (.-buffering st) false))
    (set! (.-onseeking video) #(set! (.-seeking st) true))
    (set! (.-onseeked video) #(set! (.-seeking st) false))
    ;; the native play button is the start trigger (proven in the scratch harness)
    (set! (.-onplay video)
      (fn []
        (if (.-started st)
          ;; already in: the room rules — a paused room means no solo-play
          (when-let [room (.-room st)]
            (when-not (:playing room) (park! st video)))
          ;; first play: this is the join gesture
          (let [act (.-userActivation js/navigator)]
            (if (and act (not (.-isActive act)))
              (park! st video) ;; gesture-less play; real browsers reject it anyway
              (do (set! (.-started st) true)
                  (when-let [room (.-room st)]
                    (cond
                      (:playing room)   (set! (.-currentTime video) (expected-now st room))
                      (.-cancontrol st) (do (set! (.-currentTime video) (expected-now st room))
                                            (wsx/send! (.-ws st) {:type "cmd" :op "play"
                                                                   :pos (expected-now st room)}))
                      :else             (park! st video)))))))))
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
    (ui/add! host-row play-btn pause-btn seek badge (h :span.muted "everyone follows you"))
    {:video video
     :layout (h :div.watch-layout
                (h :div.row-gap
                   (h :div.card.row-gap
                      (h :div {:style "display:flex;align-items:center;gap:0.5rem"}
                         title-el admin-link)
                      stage-wrap
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
     :title-el title-el :seek seek :res-slot res-slot
     :chat-log chat-log :peers-el peers-el :host-row host-row
     :badge badge :new-msgs new-msgs}))

(defn- loops! [st refs]
  (let [{:keys [video seek]} refs]
    (js/setInterval #(wsx/send! (.-ws st) {:type "ping" :t (ui/perf-now)}) 5000)
    (js/setInterval #(wsx/send! (.-ws st) {:type "status"
                                            :pos (.-currentTime video)
                                            :buffering (boolean (.-buffering st))})
                    5000)
    (js/setInterval #(apply-policy! st video) 500) ; smooth catch-up between beats
    (js/setInterval
      #(when (.-room st)
         (set! (.-max seek) (str (or (.-duration video) 0)))
         (when-not (.-seeking st)
           (set! (.-value seek) (str (.-currentTime video)))))
      1000)))

(defn page [token]
  (let [st (js-obj "offset" 0 "samples" [] "buffering" false "ishost" false
                  "cancontrol" false "started" false)
        saved-nick (ui/store-get "goob-nick")]
    (if (str/blank? saved-nick)
      (let [input (h :input {:type "text" :placeholder "your name"})
            join-btn (h :button.btn "join the goo")
            card (h :div.card
                    (h :div.logo "goob-toob")
                    (h :p "slime into the room — what's your name?")
                    input
                    join-btn)
            overlay (h :div.overlay card)
            join! (fn []
                    (let [n (str/trim (.-value input))]
                      (when-not (empty? n)
                        (ui/store-set! "goob-nick" n)
                        (.reload js/location))))]
        (set! (.-onclick join-btn) join!)
        (set! (.-onkeydown input) #(when (= "Enter" (.-key %)) (join!)))
        overlay)
      (do
        (set! (.-nick st) saved-nick)
        (let [refs (shell st)]
          (connect! st token refs)
          ;; once welcome lands: host chrome + steady loops (once)
          (let [started (js-obj "done" false)]
            (js/setInterval
              (fn []
                (when (and (not (.-done started)) (.-room st))
                  (set! (.-done started) true)
                  (when (.-cancontrol st)
                    (set! (.-display (.-style (:host-row refs))) "flex")
                    (when-not (.-ishost st)
                      (set! (.-textContent (:badge refs)) "co-hosting")))
                  (loops! st refs)))
              800))
          (:layout refs))))))
