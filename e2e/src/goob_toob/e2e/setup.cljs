(ns goob-toob.e2e.setup
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.core.async :as a :refer [<!]]
            [goob-toob.e2e.async :as async :refer-macros [go-try <?]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.config :as config]
                        [goob-toob.e2e.metrics :as metrics]
            [goob-toob.e2e.thresholds :refer [T W]]))

(def password "pw")

(defn- clip! [file seconds]
  (cp/execFileSync "ffmpeg"
                   (clj->js ["-y" "-hide_banner" "-loglevel" "error"
                             "-f" "lavfi" "-i" (str "testsrc2=size=1280x720:rate=30:duration=" seconds)
                             "-f" "lavfi" "-i" (str "sine=frequency=440:duration=" seconds)
                             "-c:v" "libx264" "-preset" "veryfast" "-c:a" "aac" "-shortest" file]))
  file)

(defn- wait-ready [base cookie id budget-ms]
  (go-try
    (let [deadline (+ (js/Date.now) budget-ms)]
      (loop []
        (let [row (first (filter #(= id (:id %)) (<p! (api/videos base cookie))))]
          (cond
            (= "ready" (:status row)) row
            (= "failed" (:status row)) (throw (js/Error. (str "transcode failed: " (:error row))))
            (> (js/Date.now) deadline) (throw (js/Error. (str "transcode not ready in " budget-ms " ms: " (pr-str row))))
            :else (do (<! (a/timeout 500)) (recur))))))))

(defn run [^js full-config]
  (async/chan->promise
   (go-try
       (let [base (str "http://127.0.0.1:" config/port)
             dir (api/e2e-dir full-config)
             tmp (fs/mkdtempSync (path/join (os/tmpdir) "goob-e2e-"))
             clip (clip! (path/join tmp "clip.mp4") (:clip-seconds W))
             upload-clip (clip! (path/join tmp "upload.mp4") (:upload-seconds W))
             ;; the playlist journeys queue these after the shared clip (a
             ;; queue holds no duplicates); shorter, they transcode faster
             extra-clips (mapv #(clip! (path/join tmp (str % ".mp4")) (:extra-clip-seconds W))
                               ["clip-b" "clip-c"])
             cookie (<p! (api/login base password))
             upload! (fn [file]
                       (go-try
                         (let [{:keys [status data]} (<p! (api/upload base cookie file))]
                           (when-not (= 202 status)
                             (throw (js/Error. (str "upload failed: " (pr-str data)))))
                           (:id data))))
             id (<? (upload! clip))
             extra-ids (loop [files extra-clips ids []]
                         (if-let [f (first files)]
                           (recur (rest files) (conj ids (<? (upload! f))))
                           ids))
             t0 (js/Date.now)
             row (<? (wait-ready base cookie id (:transcode-within T)))
             extra (loop [ids extra-ids rows []]
                     (if-let [i (first ids)]
                       (let [r (<? (wait-ready base cookie i (:transcode-within T)))]
                         (recur (rest ids) (conj rows {:id i :title (:title r) :duration (:duration r)})))
                       rows))]
         (metrics/reset-summary! full-config)
         (fs/writeFileSync (api/fixture-path dir)
                           (js/JSON.stringify
                            (clj->js {:cookie cookie :video-id id :title (:title row) :duration (:duration row)
                                      :extra extra
                                      :upload-clip upload-clip :transcode-ms (- (js/Date.now) t0)})))
         nil))))
