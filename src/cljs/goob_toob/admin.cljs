(ns goob-toob.admin
  "Admin console: login, drag-and-drop upload with per-file cards, library
  with transcode status, room creation + shareable links + co-host control."
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

(defn- fmt-space [mb]
  (if (> mb 1024) (str (.toFixed (/ mb 1024.0) 1) " GB") (str mb " MB")))

(defn- render-videos [tbody videos acts]
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

(defn- render-rooms [tbody rooms acts]
  (ui/clear! tbody)
  (doseq [r rooms]
    (let [url (str (.-origin js/location) "/watch/" (:token r))]
      (ui/add! tbody
        (h :tr
          (h :td (:title r))
          (h :td (h :a {:href (str "/watch/" (:token r)) :target "_blank"} (str "/watch/" (:token r))))
          (h :td
             (h :label.cohost
                (h :input {:type "checkbox"
                           :checked (pos? (or (:allow-control r) 0))
                           :onchange
                           #(-> (api/req "PATCH" (str "/api/admin/rooms/" (:id r))
                                         {:allow-control (.-checked (.-target %))})
                                (.then (fn [_] (toast (if (.-checked (.-target %))
                                                        "co-hosts can drive now"
                                                        "co-host control turned off")))))})
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

(defn- render-admin [body]
  (ui/clear! body)
  (let [info-slot   (h :div.muted "")
        file-input  (h :input {:type "file" :multiple true :style "display:none"})
        uploads     (h :div.row-gap {})
        vtbody      (h :tbody {})
        rtbody      (h :tbody {})
        acts        #js {}
        refresh-v   #(-> (api/get-json "/api/admin/videos")
                         (.then (fn [{:keys [data]}] (render-videos vtbody data acts))))
        refresh-r   #(-> (api/get-json "/api/admin/rooms")
                         (.then (fn [{:keys [data]}] (render-rooms rtbody data acts))))
        _ (set! (.-refresh acts) refresh-r)
        _ (set! (.-room acts)
                (fn [v]
                  (-> (api/post! "/api/admin/rooms" {:video-id (:id v)})
                      (.then (fn [{:keys [status data]}]
                               (when (= 201 status)
                                 (let [url (str (.-origin js/location) (:url data))]
                                   (ui/copy! url)
                                   (toast (str "room open! link copied: " url)))
                                 (refresh-r)))))))
        _ (set! (.-del acts)
                (fn [v]
                  (when (js/confirm (str "delete " (:title v) " and its stream files?"))
                    (-> (api/delete! (str "/api/admin/videos/" (:id v)))
                        (.then (fn [_] (refresh-v)))))))
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
           (h :table.lib
              (h :thead (h :tr (h :th "video") (h :th "link") (h :th "co-hosts") (h :th "")))
              rtbody))
        (h :div.goo
           (h :button.btn.ghost {:onclick #(-> (api/post! "/api/logout" {})
                                               (.then (fn [_] (set! (.-href js/location) "/"))))}
              "log out"))))
    (-> (api/get-json "/api/admin/info")
        (.then (fn [{:keys [data]}]
                 (set! (.-textContent info-slot)
                       (str (:storage-dir data) " · " (fmt-space (:free-mb data))
                            " free · " (:workers data) " transcode worker(s)")))))
    (refresh-v)
    (refresh-r)
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
