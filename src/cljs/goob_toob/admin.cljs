(ns goob-toob.admin
  "Admin console: login, drag-and-drop upload with per-file cards, library
  with transcode status, rooms with shareable links, co-host control, a
  rename, the queue editor and the telemetry files with their analysis."
  (:require [goob-toob.ui :as ui :refer [h]]
            [goob-toob.api :as api]))

(defn- login-view [on-ok]
  (let [input (h :input {:type "password" :placeholder "admin password"})
        msg   (h :p.muted "")
        btn   (h :button.btn "log in")
        go    #(-> (api/post! "/api/login" {:password (.-value input)})
                   (.then (fn [{:keys [status]}]
                            (if (= 200 status)
                              (on-ok)
                              (set! (.-textContent msg) "wrong password (or too many tries — wait a minute)")))))]
    (set! (.-onclick btn) go)
    (set! (.-onkeydown input) #(when (= "Enter" (.-key %)) (go)))
    (h :div.overlay
       (h :div.card.row-gap
          (h :h3 "the goo needs a keeper")
          input btn msg))))

(defn- toast [msg]
  (let [t (h :div.toast msg)]
    (.appendChild (.-body js/document) t)
    (js/setTimeout #(.remove t) 3200)))

(defonce public-base
  ;; set from the Electron shell when a tunnel URL exists; room links use it
  (atom nil))

(defn- base-url [] (or @public-base (.-origin js/location)))

(defn- sharing-card []
  ;; only rendered inside the Electron shell (window.goob bridge): the public
  ;; tunnel URL + the storage folder picker
  (when-let [^js goob (.-goob js/window)]
    (let [url-el (h :code {:style "word-break:break-all;flex:1"} "opening the tunnel…")
          copy   (h :button.btn "copy link")
          dir-el (h :span.muted "…")
          change (h :button.btn.aqua "change")
          card   (h :div.card.row-gap
                    (h :h3 "sharing")
                    (h :div.row url-el copy)
                    (h :div.row (h :span "storage: ") dir-el change))]
      (set! (.-onclick copy)
            #(do (ui/copy! (.-textContent url-el))
                 (toast "tunnel link copied — toss it to your friends")))
      (set! (.-onclick change)
            #(.then (.pickStorage goob)
                    (fn [dir] (when dir (set! (.-textContent dir-el) dir)))))
      (.then (.getState goob)
             (fn [^js st]
               (when-let [u (.-tunnelUrl st)]
                 (reset! public-base u)
                 (set! (.-textContent url-el) u))
               (set! (.-textContent dir-el) (or (.-storageDir st) "?"))))
      (.onTunnelUrl goob
                    (fn [u]
                      (reset! public-base u)
                      (set! (.-textContent url-el) (or u "re-dialing the tunnel…"))
                      (.dispatchEvent js/document (js/CustomEvent. "goob:tunnel"))))
      card)))

(defn- fmt-space [mb]
  (if (> mb 1024) (str (.toFixed (/ mb 1024.0) 1) " GB") (str mb " MB")))

(defn- render-videos [tbody videos ^js acts]
  (ui/clear! tbody)
  (doseq [v videos]
    (let [st (:status v)]
      (ui/add! tbody
        (h :tr
          (h :td (:title v))
          (h :td {:class (str "status-" st)}
             st
             (when (#{"transcoding" "uploaded"} st)
               (h :div.bar {:style "width:140px;margin-top:4px"}
                  (h :i {:style (str "width:" (int (* 100 (or (:progress v) 0))) "%")}))))
          (h :td (or (ui/fmt-time (:duration v)) "—"))
          (h :td {:class "err"} (some-> (:error v) (#(subs % 0 (min 80 (count %))))))
          (h :td
             (h :div.goo
               (h :button.btn.aqua {:disabled (not= st "ready")
                                    :onclick #((.-room acts) v)} "make room")
               (h :button.btn.danger {:onclick #((.-del acts) v)} "del"))))))))

;; ---- rooms: rename + queue editor ---------------------------------------------

(defonce library*
  ;; the latest library rows, so the queue editor's add-select can list the
  ;; ready videos without a fetch of its own
  (atom []))

(defonce open-queues
  ;; room ids whose queue editor is open, kept across the periodic re-render
  (atom #{}))

(defn- move-in
  "ids with the elements at i and j swapped."
  [ids i j]
  (assoc ids i (ids j) j (ids i)))

(defn- queue-editor
  "The queue of one room, rendered into slot and re-rendered from every
  response's :queue. The current item is marked; removing it moves the
  room to its neighbour (the server's call), removing the only item
  empties the room. The add-select lists the ready library videos not yet
  queued."
  [slot room]
  (let [items-url (str "/api/admin/rooms/" (:id room) "/items")]
    (letfn [(call! [method url body]
              (-> (api/req method url body)
                  (.then (fn [{:keys [status data]}]
                           (if (<= 200 status 299)
                             (render! (:queue data) (:video-id data))
                             (toast (or (:error data) (str "queue edit failed (" status ")"))))))))
            (render! [queue current]
              (ui/clear! slot)
              (let [ids   (mapv :video-id queue)
                    last-i (dec (count ids))
                    move!  (fn [i j] (call! "PUT" items-url {:order (move-in ids i j)}))]
                (doseq [[i item] (map-indexed vector queue)]
                  (let [now? (= current (:video-id item))]
                    (ui/add! slot
                      (h :div.qitem {:class (when now? "now")}
                         (h :span.pos (str (inc i)))
                         (h :span.qtitle (:title item))
                         (h :span.muted (if now? "now playing" (:status item)))
                         (h :div.goo
                            (h :button.btn.ghost {:disabled (zero? i) :onclick #(move! i (dec i))} "up")
                            (h :button.btn.ghost {:disabled (= i last-i) :onclick #(move! i (inc i))} "down")
                            (h :button.btn.danger
                               {:onclick #(call! "DELETE" (str items-url "/" (:video-id item)) nil)}
                               "remove"))))))
                (let [queued  (set ids)
                      options (remove #(or (queued (:id %)) (not= "ready" (:status %))) @library*)
                      sel     (apply h :select {} (for [v options] (h :option {:value (:id v)} (:title v))))]
                  (ui/add! slot
                    (h :div.qadd sel
                       (h :button.btn.aqua {:disabled (empty? options)
                                            :onclick #(call! "POST" items-url {:video-id (.-value sel)})}
                          "add"))))))]
      (render! (:queue room) (:video-id room))
      slot)))

(defn- title-cell
  "The room's title; click to rename. Enter or leaving the field saves,
  Escape cancels. on-saved re-reads the rooms (a blank title falls back to
  the current video's)."
  [r on-saved]
  (let [cell   (h :td {})
        title* (atom (or (:title r) "empty room"))]
    (letfn [(show! []
              (ui/clear! cell)
              (ui/add! cell (h :span.editable {:title "click to rename" :onclick (fn [_] (edit!))} @title*)))
            (save! [v]
              (let [old @title*]
                (reset! title* v) ; shown as soon as the field closes
                (-> (api/req "PATCH" (str "/api/admin/rooms/" (:id r)) {:title v})
                    (.then (fn [{:keys [status data]}]
                             (if (= 200 status)
                               (do (toast "room renamed") (on-saved))
                               (do (reset! title* old) (show!)
                                   (toast (or (:error data) "rename failed")))))))))
            (edit! []
              (let [input (h :input.room-title
                             {:type "text" :value @title*
                              :onchange  (fn [^js e] (save! (.-value (.-target e))))
                              :onblur    (fn [_] (show!))
                              :onkeydown (fn [^js e]
                                           (case (.-key e)
                                             "Enter"  (.blur (.-target e))
                                             "Escape" (do (set! (.-value (.-target e)) @title*)
                                                          (.blur (.-target e)))
                                             nil))})]
                (ui/clear! cell)
                (ui/add! cell input)
                (.focus input)
                (.select input)))]
      (show!)
      cell)))

(defn- queue-cell
  "n items, opening into the queue editor; the editor renders lazily on the
  first open and stays open across re-renders."
  [r]
  (let [n     (count (:queue r))
        open? (contains? @open-queues (:id r))
        slot  (h :div.queue-editor {})]
    (when open? (queue-editor slot r))
    (h :td
       (h :details.queue
          {:open (when open? true)
           :ontoggle (fn [^js e]
                       (if (.-open (.-currentTarget e))
                         (do (swap! open-queues conj (:id r))
                             (when-not (.-firstChild slot) (queue-editor slot r)))
                         (swap! open-queues disj (:id r))))}
          (h :summary (cond (zero? n) "empty" (= 1 n) "1 item" :else (str n " items")))
          slot))))

(defn- render-rooms [tbody rooms ^js acts]
  (ui/clear! tbody)
  (doseq [r rooms]
    (let [url (str (base-url) "/watch/" (:token r))]
      (ui/add! tbody
        (h :tr
          (title-cell r (.-refresh acts))
          (h :td (h :a {:href (str "/watch/" (:token r)) :target "_blank"} (str "/watch/" (:token r))))
          (queue-cell r)
          (h :td
             (h :label.cohost
                (h :input {:type "checkbox"
                           :checked (pos? (or (:allow-control r) 0))
                           :onchange
                           (fn [^js e]
                             (let [on? (.-checked (.-target e))]
                               (-> (api/req "PATCH" (str "/api/admin/rooms/" (:id r)) {:allow-control on?})
                                   (.then (fn [_] (toast (if on?
                                                           "co-hosts can drive now"
                                                           "co-host control turned off")))))))})
                "co-hosts can drive"))
          (h :td
             (h :div.goo
               (h :button.btn.aqua {:onclick #(do (ui/copy! url)
                                                  (toast "link copied — toss it to your friends"))}
                  "copy link")
               (h :button.btn.danger
                  {:onclick #(when (js/confirm (str "close this room? (" (:title r) ")"))
                               (-> (api/delete! (str "/api/admin/rooms/" (:id r)))
                                   (.then (fn [_] (toast "room closed") ((.-refresh acts))))))}
                  "delete"))))))))

;; ---- telemetry (#57) ------------------------------------------------------------
;; Every started viewer uploads its playback numbers once every five minutes
;; (watch.cljs, the telemetry block); the server files them per day, room and
;; viewer. This card lists them with download links and runs the analysis
;; (goob-toob.telemetry) over a room's day on demand.

(defn- fmt-bytes [n]
  (cond (> n 1048576) (str (.toFixed (/ n 1048576) 1) " MB")
        (> n 1024)    (str (js/Math.round (/ n 1024)) " KB")
        :else         (str n " B")))

(defn- clock [ms]
  (if (number? ms)
    (.toLocaleTimeString (js/Date. ms) js/undefined #js {:hour "2-digit" :minute "2-digit" :second "2-digit"})
    "?"))

(defn- episode-tables
  "The analysis as two tables: a row per client, a row per flagged episode."
  [{:keys [clients episodes]}]
  (h :div.row-gap
     (h :table.lib
        (h :thead (h :tr (h :th "who") (h :th "samples") (h :th "span") (h :th "drift p95 / max")
                         (h :th "off 1.0×") (h :th "stalls") (h :th "dropped") (h :th "marks")))
        (apply h :tbody
               (for [c clients]
                 (h :tr (h :td (:nick c))
                        (h :td (str (:samples c)))
                        (h :td (str (:span-s c) " s"))
                        (h :td (str (:drift-abs-p95 c) " / " (:drift-abs-max c)))
                        (h :td (str (:off-rate-s c) " s"))
                        (h :td (str (:stalls c)))
                        (h :td (str (:dropped c)))
                        (h :td (apply str (interpose ", " (map #(str (clock (:t %)) " " (:type %)) (:marks c)))))))))
     (if (empty? episodes)
       (h :p.muted "no episodes flagged — a clean run")
       (h :table.lib
          (h :thead (h :tr (h :th "from") (h :th "to") (h :th "what") (h :th "who") (h :th "length") (h :th "evidence")))
          (apply h :tbody
                 (for [e episodes]
                   (h :tr (h :td (clock (:start e)))
                          (h :td (clock (:end e)))
                          (h :td (:type e))
                          (h :td (:nick e))
                          (h :td (str (:duration-s e) " s"))
                          (h :td.muted (pr-str (:evidence e))))))))))

(defn- telemetry-room [day {:keys [id title files]}]
  (let [base    (str "/api/admin/telemetry/" day "/" id)
        slot    (h :div {})
        analyse (h :button.btn.ghost "analyse")]
    (set! (.-onclick analyse)
          #(-> (api/get-json (str base "/analysis"))
               (.then (fn [{:keys [status data]}]
                        (ui/clear! slot)
                        (ui/add! slot (if (= 200 status)
                                        (episode-tables data)
                                        (h :p.muted "the analysis failed")))))))
    (h :div.tele-room
       (h :div.row (h :strong (or title (str "room " (subs id 0 8) "…")))
                   (h :span.muted (str (count files) " viewer file(s)"))
                   analyse)
       (apply h :ul.muted
              (for [f files]
                (h :li (h :a {:href (str base "/" (:name f)) :download (:name f)} (:name f))
                       (str " · " (fmt-bytes (:size f)) " · " (:chunks f) " chunk(s) · " (:samples f)
                            " samples · " (clock (:first f)) " – " (clock (:last f))))))
       slot)))

(defn- render-telemetry [slot days]
  (ui/clear! slot)
  (if (empty? days)
    (ui/add! slot (h :p.muted "nothing recorded yet — every viewer who pressed play sends their playback numbers once every five minutes"))
    (doseq [[i {:keys [day rooms]}] (map-indexed vector days)]
      (ui/add! slot
        (h :details.tele-day {:open (when (zero? i) true)}
           (h :summary day)
           (apply h :div.row-gap (map #(telemetry-room day %) rooms)))))))

(defn- editing-in?
  "A text field or select inside node has focus: a periodic re-render would
  pull it out from under the admin."
  [node]
  (let [^js a (.-activeElement js/document)]
    (boolean (and a (.contains node a)
                  (or (= "SELECT" (.-tagName a))
                      (and (= "INPUT" (.-tagName a)) (= "text" (.-type a))))))))

(defn- settings-card
  "The global switches (#57: record playback telemetry). Reads the stored
  values once, writes on change."
  []
  (let [box  (h :input {:type "checkbox" :checked true})
        card (h :label.cohost box "record playback telemetry (every viewer, every room)")]
    (-> (api/get-json "/api/admin/settings")
        (.then (fn [{:keys [data]}] (set! (.-checked box) (boolean (:telemetry data))))))
    (set! (.-onchange box)
          (fn [^js e]
            (let [on? (.-checked (.-target e))]
              (-> (api/req "PUT" "/api/admin/settings" {:telemetry on?})
                  (.then (fn [{:keys [status data]}]
                           (if (= 200 status)
                             (toast (if on? "telemetry on: viewers record from their next welcome"
                                            "telemetry off: nothing is sent, nothing is stored"))
                             (do (set! (.-checked box) (not on?))
                                 (toast (or (:error data) "settings save failed"))))))))))
    card))

(defn- render-admin [body]
  (ui/clear! body)
  (let [info-slot   (h :div.muted "")
        tele-slot   (h :div.row-gap {})
        refresh-t   #(-> (api/get-json "/api/admin/telemetry")
                         (.then (fn [{:keys [data]}] (render-telemetry tele-slot data))))
        file-input  (h :input {:type "file" :multiple true :style "display:none"})
        uploads     (h :div.row-gap {})
        vtbody      (h :tbody {})
        rtbody      (h :tbody {})
        acts        #js {}
        refresh-v   #(-> (api/get-json "/api/admin/videos")
                         (.then (fn [{:keys [data]}]
                                  (reset! library* data)
                                  (render-videos vtbody data acts))))
        refresh-r   #(-> (api/get-json "/api/admin/rooms")
                         (.then (fn [{:keys [data]}]
                                  (when-not (editing-in? rtbody)
                                    (render-rooms rtbody data acts)))))
        _ (set! (.-refresh acts) refresh-r)
        _ (when (.-goob js/window)
            ;; tunnel URL arrived (or re-dialed): room links carry it now
            (.addEventListener js/document "goob:tunnel" #(refresh-r)))
        _ (set! (.-room acts)
                (fn [v]
                  (-> (api/post! "/api/admin/rooms" {:video-id (:id v)})
                      (.then (fn [{:keys [status data]}]
                               (when (= 201 status)
                                 (let [url (str (base-url) (:url data))]
                                   (ui/copy! url)
                                   (toast (str "room open! link copied: " url)))
                                 (refresh-r)))))))
        _ (set! (.-del acts)
                (fn [v]
                  (when (js/confirm (str "delete " (:title v) " and its stream files?"))
                    (-> (api/delete! (str "/api/admin/videos/" (:id v)))
                        (.then (fn [{:keys [status data]}]
                                 (if (= 409 status)
                                   ;; queued somewhere: the room keeps it; remove it from the queue first
                                   (toast (str "can't delete — " (:error data)))
                                   (refresh-v))))))))
        upload-one
        (fn [f]
          (let [bar   (h :div.bar {:style "width:100%"} (h :i {:style "width:0%"}))
                label (h :div {} (str (.-name f) " · "
                                       (fmt-space (/ (.-size f) 1048576.0))))
                card  (h :div.upload-card label bar)]
            (ui/add! uploads card)
            (-> (api/upload-file f
                                 (fn [loaded total]
                                   (when (pos? total)
                                     (set! (.-width (.-style (.-firstChild bar)))
                                           (str (int (* 100 (/ loaded total))) "%")))))
                (.then (fn [status]
                         (if (<= 200 status 299)
                           (do (set! (.-textContent label) (str "✓ " (.-name f) " — queued for ffmpeg"))
                               (set! (.-className label) "ok"))
                           (do (set! (.-textContent label) (str "✗ " (.-name f) " — upload failed"))
                               (set! (.-className label) "err")))
                         (refresh-v))))))]
    (set! (.-onchange file-input)
          #(doseq [f (array-seq (.-files file-input))] (upload-one f)))
    (let [drop (h :div.dropzone
                  {:ondragover  #(do (.preventDefault %)
                                     (set! (.-className (.-currentTarget %)) "dropzone over"))
                   :ondragleave #(set! (.-className (.-currentTarget %)) "dropzone")
                   :ondrop      #(do (.preventDefault %)
                                     (set! (.-className (.-currentTarget %)) "dropzone")
                                     (doseq [f (array-seq (.-files (.-dataTransfer %)))]
                                       (upload-one f)))
                   :onclick     #(.click file-input)}
                  "drop videos here — or click to pick")]
      (when-let [sc (sharing-card)] (ui/add! body sc))
      (ui/add! body
        (h :div.card.row-gap
           (h :h3 "your storage")
           info-slot)
        (h :div.card.row-gap
           (h :h3 "en-masse upload")
           (h :p.muted "drop your whole library in; ffmpeg builds a multi-bitrate DASH ladder for each file. transcode before the party — one job runs at a time.")
           drop
           file-input
           uploads)
        (h :div.card.row-gap
           (h :h3 "library")
           (h :table.lib
              (h :thead (h :tr (h :th "title") (h :th "status") (h :th "length") (h :th "") (h :th "")))
              vtbody))
        (h :div.card.row-gap
           (h :h3 "open rooms")
           (h :p.muted "click a title to rename it; open the queue to line up more of the library — the party follows live.")
           (h :table.lib
              (h :thead (h :tr (h :th "title") (h :th "link") (h :th "queue") (h :th "co-hosts") (h :th "")))
              rtbody))
        (h :div.card.row-gap
           (h :h3 "telemetry")
           (h :p.muted "every viewer who pressed play sends their playback numbers once every five minutes (and the tail when the tab closes): one file per viewer, per room, per day, kept 30 days. \"analyse\" flags the episodes — stretches off 1.0×, drift, stall clusters, dropped frames, starved buffers, the A/V desync signatures.")
           (settings-card)
           (h :div.goo (h :button.btn.ghost {:onclick (fn [_] (refresh-t))} "refresh"))
           tele-slot)
        (when-not (.-goob js/window)
          (h :div.goo
             (h :button.btn.ghost {:onclick #(-> (api/post! "/api/logout" {})
                                                 (.then (fn [_] (set! (.-href js/location) "/"))))}
                "log out")))))
    (-> (api/get-json "/api/admin/info")
        (.then (fn [{:keys [data]}]
                 (set! (.-textContent info-slot)
                       (str (:storage-dir data) " · " (fmt-space (:free-mb data))
                            " free · " (:workers data) " transcode worker(s)")))))
    (refresh-v)
    (refresh-r)
    (refresh-t) ; on demand after that: a periodic re-render would close an open analysis
    (js/setInterval refresh-v 2500)
    (js/setInterval refresh-r 10000)))

(defn page []
  (let [root (h :div.row-gap {})
        body (h :div.row-gap {})]
    (ui/add! root body)
    (let [show-admin (fn [] (render-admin body))
          show-login (fn [] (ui/clear! body) (ui/add! body (login-view show-admin)))]
      (-> (api/get-json "/api/admin/me")
          (.then (fn [{:keys [status]}]
                   (if (= 200 status) (show-admin) (show-login)))))
      root)))
