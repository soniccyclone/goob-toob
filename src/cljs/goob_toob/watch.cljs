(ns goob-toob.watch
  "The watch page: join overlay -> dash.js player + server-synced playback
  with per-viewer catch-up, resolution choice, peers and chat."
  (:require [clojure.string :as str]
            [goob-toob.ui :as ui :refer [h]]
            [goob-toob.ws :as wsx]
            [goob-toob.player :as player]
            [goob-toob.sync-core :as sc]))

(defn- apply-policy! [st video]
  (when-let [room (.-room st)]
    (let [now (+ (ui/perf-now) (.-offset st))
          expected (sc/expected-pos {:pos (:pos room) :t0 (:t0 room)
                                     :now now :playing? (:playing room)
                                     :rate (:rate room)})
          pol (sc/policy (sc/drift (.-currentTime video) expected))]
      (case pol
        :none (when (not= 1.0 (.-playbackRate video))
                (set! (.-playbackRate video) 1.0))
        :seek (do (set! (.-currentTime video) expected)
                  (set! (.-playbackRate video) 1.0))
        :nudge-fast (set! (.-playbackRate video) sc/nudge-fast-rate)
        :nudge-slow (set! (.-playbackRate video) sc/nudge-slow-rate)
        nil))))

(defn- apply-state-msg! [st video msg]
  (set! (.-room st) msg)
  (case (:op msg)
    "play"  (do (set! (.-currentTime video) (:pos msg))
                (.play video))
    "pause" (do (.pause video)
                (set! (.-currentTime video) (:pos msg))
                (set! (.-playbackRate video) 1.0))
    "seek"  (set! (.-currentTime video) (:pos msg))
    nil))

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

(defn- append-chat! [log-el msg]
  (ui/add! log-el
    (h :div.bubble
       (h :span.who {:style (str "color:" (ui/nick-color (:from msg)))} (:from msg))
       (h :div (:text msg))))
  (set! (.-scrollTop log-el) (.-scrollHeight log-el)))

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
  (let [{:keys [video title-el seek res-slot peers-el chat-log]} refs
        proto (if (= "https:" (.-protocol js/location)) "wss:" "ws:")
        url (str proto "//" (.-host js/location) "/ws/room/" token
                 "?nick=" (js/encodeURIComponent (.-nick st)))]
    (wsx/connect
      url
      (fn [msg _ws]
        (case (:type msg)
          "welcome"
          (do (set! (.-ishost st) (:is-host msg))
              (set! (.-room st) (:state msg))
              (set! (.-textContent title-el) (get-in msg [:video :title]))
              (when-not (.-player st)
                (set! (.-player st)
                  (player/create video (get-in msg [:video :dash])
                    (fn [ctx]
                      (ui/clear! res-slot)
                      (ui/add! res-slot (res-menu st))))))
                ;; join mid-party: jump to where the room is
                (when (:playing (:state msg))
                  (let [now (+ (ui/perf-now) (.-offset st))]
                    (set! (.-currentTime video)
                      (sc/expected-pos (assoc (:state msg) :now now :playing? true)))
                    (.play video))))
          "state" (apply-state-msg! st video msg)
          "beat"  (do (set! (.-room st) msg)
                      (apply-policy! st video)
                      (when-not (.-seeking st)
                        (set! (.-value seek) (str (.-currentTime video)))))
          "pong"  (let [t1 (ui/perf-now)
                        sample {:rtt (- t1 (:t msg))
                                :offset (sc/ntp-offset (:t msg) (:st msg) t1)}]
                    (set! (.-samples st) (take 8 (cons sample (.-samples st))))
                    (set! (.-offset st) (sc/best-offset (.-samples st))))
          "chat"  (append-chat! chat-log msg)
          "peers" (render-peers! peers-el (:peers msg) (.-ishost st))
          "error" (set! (.-textContent title-el) (:message msg))
          nil))
      (fn [ws]
        (set! (.-ws st) ws)
        (set! (.-samples st) [])
        (wsx/send! ws {:type "ping" :t (ui/perf-now)})))))

(defn- shell [st]
  (let [video (.createElement js/document "video")
        _ (set! (.-className video) "stage")
        title-el (h :h3 {:style "margin:0;color:var(--slime)"} "joining the goo…")
        play-btn (h :button.btn "▶ play")
        pause-btn (h :button.btn.aqua "⏸ pause")
        seek (h :input.seek {:type "range" :value 0 :min 0 :step 0.5})
        res-slot (h :span {})
        host-row (h :div.controls {:style "display:none"})
        chat-log (h :div.log {})
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
    (set! (.-onkeydown chat-input) #(when (= "Enter" (.-key %)) (send-chat)))
    (set! (.-onclick play-btn)
          #(wsx/send! (.-ws st) {:type "cmd" :op "play" :pos (.-currentTime video)}))
    (set! (.-onclick pause-btn)
          #(wsx/send! (.-ws st) {:type "cmd" :op "pause" :pos (.-currentTime video)}))
    (set! (.-onchange seek)
          #(do (set! (.-seeking st) false)
               (wsx/send! (.-ws st) {:type "cmd" :op "seek"
                                      :pos (js/parseFloat (.-value seek))})))
    (set! (.-oninput seek) #(set! (.-seeking st) true))
    (ui/add! host-row
      play-btn pause-btn seek res-slot
      (h :span.badge.host "you drive")
      (h :span.muted "everyone follows you"))
    {:video video
     :layout (h :div.watch-layout
                (h :div.row-gap
                   (h :div.card.row-gap
                      (h :div {} title-el)
                      video
                      host-row))
                (h :div.row-gap
                   (h :div.card
                      (h :h3 "watching with")
                      peers-el)
                   (h :div.card.chat
                      (h :h3 "chat")
                      chat-log
                      (h :div.row {}
                         chat-input
                         (h :button.btn.aqua {:onclick send-chat} "💬")))))
     :title-el title-el :seek seek :res-slot res-slot
     :chat-log chat-log :peers-el peers-el :host-row host-row}))

(defn- loops! [st refs]
  (let [{:keys [video seek]} refs]
    (js/setInterval #(wsx/send! (.-ws st) {:type "ping" :t (ui/perf-now)}) 5000)
    (js/setInterval #(wsx/send! (.-ws st) {:type "status"
                                            :pos (.-currentTime video)
                                            :buffering (boolean (.-buffering st))})
                    5000)
    (js/setInterval
      #(when (.-room st)
         (set! (.-max seek) (str (or (.-duration video) 0)))
         (when-not (.-seeking st)
           (set! (.-value seek) (str (.-currentTime video)))))
      1000)))

(defn page [token]
  (let [st (js-obj "offset" 0 "samples" [] "buffering" false "ishost" false)
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
                  (when (.-ishost st)
                    (set! (.-display (.-style (:host-row refs))) "flex"))
                  (loops! st refs)))
              800))
          (:layout refs))))))
