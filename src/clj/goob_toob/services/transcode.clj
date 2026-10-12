(ns goob-toob.services.transcode
  "Async ffmpeg -> multi-bitrate MPEG-DASH pipeline. Uploads enqueue jobs on
  a core.async channel; a small worker pool (default 1) runs ffmpeg so
  encoding never starves streaming. Progress comes from ffmpeg's -progress
  output (emitted ~1/s) and lands in the videos table."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [goob-toob.config :as config]
            [goob-toob.repos.video :as videos]
            [goob-toob.repos.storage :as storage])
  (:import [java.lang ProcessBuilder]))

(defonce control (atom nil)) ; {:jobs chan}

(defn- read-all [^java.io.InputStream is]
  (String. (.readAllBytes is)))

(defn ffprobe [^java.io.File f]
  (let [p (-> (ProcessBuilder. ["ffprobe" "-v" "error" "-print_format" "json"
                                "-show_format" "-show_streams" (.getPath f)])
              .start)
        out (read-all (.getInputStream p))
        err (read-all (.getErrorStream p))
        exit (.waitFor p)]
    (if (zero? exit)
      (json/read-str out :key-fn keyword)
      (throw (ex-info (str "ffprobe failed (exit " exit "): "
                           (apply str (take-last 400 err))) {:exit exit :err err})))))

(defn- probe-media [probe]
  (let [v (first (filter #(= "video" (:codec_type %)) (:streams probe)))
        a (first (filter #(= "audio" (:codec_type %)) (:streams probe)))
        dur (some-> probe :format :duration Double/parseDouble)]
    (when-not v (throw (ex-info "no video stream found" {})))
    {:height (or (some-> v :height long) 1080)
     :audio? (boolean a)
     :duration (or dur 1.0)}))

(defn- ladder-for [{:keys [height]} ladder]
  (let [fit (filter #(<= (:h %) height) ladder)]
    (if (seq fit) fit [(last ladder)])))

(defn- ffmpeg-cmd [^java.io.File in ^java.io.File out {:keys [audio?]} ladder]
  (let [n (count ladder)
        split (str "[0:v]split=" n (str/join (for [i (range n)] (str "[s" i "]"))) ";")
        scales (str/join ";" (for [i (range n)]
                               (str "[s" i "]scale=-2:" (:h (nth ladder i)) "[v" i "]")))
        maps-v (mapcat (fn [i] ["-map" (str "[v" i)]) (range n))
        maps-a (when audio? (mapcat (fn [_] ["-map" "0:a:0"]) (range n)))
        bitrates (mapcat (fn [i rung] [(str "-b:v:" i) (str (:v rung) "k")])
                         (range n) ladder)]
    (concat
     ["nice" "-n" "10" "ffmpeg" "-hide_banner" "-nostdin" "-y" "-i" (.getPath in)]
     ["-filter_complex" (str split scales)]
     maps-v maps-a
     ["-c:v" "libx264" "-preset" "veryfast" "-profile:v" "high" "-pix_fmt" "yuv420p"
      "-sc_threshold" "0" "-force_key_frames" "expr:gte(t,n_forced*4)"]
     bitrates
     (when audio? ["-c:a" "aac" "-b:a" "128k"])
     ["-f" "dash" "-seg_duration" "4" "-use_template" "1" "-use_timeline" "1"
      "-adaptation_sets" (if audio? "id=0,streams=v id=1,streams=a" "id=0,streams=v")
      "-progress" "pipe:1" "-nostats"
      (.getPath out)])))

(defn- run-ffmpeg [cmd video-id dur]
  "Runs the encode; updates DB progress on each out_time_ms line (~1/s)."
  (let [p (-> (ProcessBuilder. (vec cmd)) .start)
        err-lines (java.util.concurrent.ConcurrentLinkedQueue.)]
    (a/thread ; stderr -> ring buffer for error reporting
      (with-open [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getErrorStream p)))]
        (loop []
          (when-let [line (.readLine r)]
            (.add err-lines line)
            (when (> (.size err-lines) 80) (.poll err-lines))
            (recur)))))
    (with-open [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream p)))]
      (loop []
        (when-let [line (.readLine r)]
          (when-let [[_ us] (re-find #"out_time_ms=(\d+)" line)]
            (let [secs (/ (Double/parseDouble us) 1e6)]
              (videos/set-progress! video-id (min 0.99 (max 0.0 (/ secs dur))))))
          (recur))))
    {:exit (.waitFor p)
     :stderr (str/join "\n" (take-last 40 err-lines))}))

(defn- process-job [video-id]
  (let [cfg (config/cfg)
        video (videos/get-by-id video-id)]
    (when video
      (try
        (let [in (storage/find-original video-id)
              _ (when-not in (throw (ex-info "original file missing" {:video-id video-id})))
              out (java.io.File. (storage/dash-dir video-id) "manifest.mpd")
              media (probe-media (ffprobe in))
              ladder (ladder-for media (:ladder cfg))
              _ (println (format "transcode start: %s (%.0fs, height %d, ladder %s)"
                                 (:title video) (:duration media) (:height media)
                                 (str/join ", " (map :h ladder))))
              _ (videos/set-duration! video-id (:duration media))
              _ (videos/set-status! video-id "transcoding" :progress 0.0)
              {:keys [exit stderr]} (run-ffmpeg (ffmpeg-cmd in out media ladder)
                                                video-id (:duration media))]
          (if (zero? exit)
            (do (videos/set-status! video-id "ready" :progress 1.0 :error nil)
                (when-not (:keep-original cfg) (storage/delete-original! video-id))
                (println "transcoded" (:title video) "-> ready"))
            (do (videos/set-status! video-id "failed" :error stderr)
                (println "transcode FAILED" (:title video) "::"
                         (str/join (take-last 300 stderr))))))
        (catch Exception e
          (videos/set-status! video-id "failed" :error (str e))
          (println "transcode ERROR" video-id (.getMessage e)))))))

(defn submit! [video-id]
  (when-let [jobs (:jobs @control)]
    (a/put! jobs video-id)))

(defn start! []
  (let [cfg (config/cfg)
        jobs (a/chan 64)]
    ;; one blocking worker per slot: takes a video id, runs ffmpeg to
    ;; completion, takes the next. Sequential by design (TRANSCODE_WORKERS).
    (dotimes [_ (:workers cfg)]
      (future
        (loop []
          (when-let [vid (a/<!! jobs)]
            (process-job vid)
            (recur)))))
    (reset! control {:jobs jobs})
    (doseq [vid (videos/requeue-stuck)] (submit! vid))
    (println "transcode workers:" (:workers cfg))))
