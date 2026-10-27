(ns goob-toob.services.transcode
  "Async ffmpeg -> multi-bitrate MPEG-DASH pipeline. Uploads enqueue jobs on
  a core.async channel; a small worker pool (default 1) runs ffmpeg so
  encoding never starves streaming. Progress comes from ffmpeg's -progress
  output (emitted ~1/s) and lands in the videos table."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [goob-toob.config :as config]
            [goob-toob.log :as log]
            [goob-toob.repos.video :as videos]
            [goob-toob.repos.storage :as storage])
  (:import [java.lang ProcessBuilder]
           [java.util.concurrent TimeUnit]))

(defonce control (atom nil)) ; {:jobs chan}

;; ffmpeg processes mid-encode, so a JVM shutdown can take them with it:
;; without this a transcode outlives the app (#49). stopping? tells the
;; worker that a dead ffmpeg was killed on purpose, not a failure.
(defonce ^:private running (atom #{}))
(defonce ^:private stopping? (atom false))

(def windows?
  (.contains (str/lower-case (System/getProperty "os.name")) "windows"))

;; ffmpeg lives on PATH by default; the desktop bundle points FFMPEG_PATH at
;; its vendored copy. ffprobe is assumed to sit next to it.
(defn- ffmpeg-bin [] (:ffmpeg-path (config/cfg)))
(defn- ffprobe-bin []
  (let [p (ffmpeg-bin)]
    (if (= p "ffmpeg") "ffprobe"
        (str/replace p #"ffmpeg(\.exe)?$" "ffprobe$1"))))

(defn- read-all [^java.io.InputStream is]
  (String. (.readAllBytes is)))

(defn ffprobe [^java.io.File f]
  (let [p (-> (ProcessBuilder. [(ffprobe-bin) "-v" "error" "-print_format" "json"
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

(defn ladder-for
  "Rungs to encode for a source of the given height: every ladder rung below
  it plus, when the height is not itself a rung and sits below the top rung,
  the source height at the next rung's bitrate scaled by pixel ratio. A source
  below the lowest rung gets only its own height at the lowest rung's bitrate
  scaled by pixel ratio; a source above the top rung gets the whole ladder."
  [{:keys [height]} ladder]
  (let [below (filter #(< (:h %) height) ladder)
        exact (some #(when (= (:h %) height) %) ladder)
        above (last (filter #(> (:h %) height) ladder))]
    (cond
      exact (cons exact below)
      above (cons {:h height
                   :v (long (Math/round (* (:v above) (Math/pow (/ height (double (:h above))) 2))))}
                  below)
      :else below)))

(defn- ffmpeg-path [^java.io.File f]
  (str/replace (.getPath f) "\\" "/"))

(defn- ffmpeg-cmd [^java.io.File in ^java.io.File out {:keys [audio?]} ladder]
  (let [n (count ladder)
        split (str "[0:v]split=" n (str/join (for [i (range n)] (str "[s" i "]"))) ";")
        scales (str/join ";" (for [i (range n)]
                               (str "[s" i "]scale=-2:" (:h (nth ladder i)) "[v" i "]")))
        maps-v (mapcat (fn [i] ["-map" (str "[v" i)]) (range n))
        maps-a (when audio? ["-map" "0:a:0"])
        bitrates (mapcat (fn [i rung] [(str "-b:v:" i) (str (:v rung) "k")])
                         (range n) ladder)]
    (concat
     ;; nice is Unix-only; on Windows just run ffmpeg directly
     (when-not windows? ["nice" "-n" "10"])
     [(ffmpeg-bin) "-hide_banner" "-loglevel" "warning" "-nostdin" "-y" "-i" (.getPath in)]
     ["-filter_complex" (str split scales)]
     maps-v maps-a
     ["-c:v" "libx264" "-preset" "veryfast" "-profile:v" "high" "-pix_fmt" "yuv420p"
      "-sc_threshold" "0" "-force_key_frames" "expr:gte(t,n_forced*4)"]
     bitrates
     ;; stereo only: multi-channel ffmpeg-AAC carries a PCE that Chrome's
     ;; decoder rejects (CHUNK_DEMUXER_ERROR_APPEND_FAILED → spinner forever),
     ;; and a watch party through a quick tunnel doesn't need 5.1
     (when audio? ["-c:a" "aac" "-b:a" "128k" "-ac" "2"])
     ["-f" "dash" "-seg_duration" "4" "-use_template" "1" "-use_timeline" "1"
      "-adaptation_sets" (if audio? "id=0,streams=v id=1,streams=a" "id=0,streams=v")
      "-progress" "pipe:1" "-nostats"
      (ffmpeg-path out)])))

(defn priority-cmd
  "Command that drops an already-running ffmpeg to below-normal priority on
  Windows, where nice does not exist; nil elsewhere."
  [pid windows?]
  (when windows?
    ["powershell" "-NoProfile" "-NonInteractive" "-Command"
     (str "(Get-Process -Id " pid ").PriorityClass='BelowNormal'")]))

(defn- run-ffmpeg
  "Runs the encode; updates DB progress on each out_time_ms line (~1/s).
  The process sits in `running` for its whole life so stop! can end it."
  [cmd video-id dur]
  (let [p (-> (ProcessBuilder. (vec cmd)) .start)
        err-lines (java.util.concurrent.ConcurrentLinkedQueue.)]
    (swap! running conj p)
    (try
      (when-let [c (priority-cmd (.pid p) windows?)]
        (let [exit (-> (ProcessBuilder. ^java.util.List c) .inheritIO .start .waitFor)]
          (when-not (zero? exit)
            (log/info "transcode: could not lower ffmpeg priority (exit" exit ")"))))
      (let [err-done (a/thread
                       (with-open [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getErrorStream p)))]
                         (loop []
                           (when-let [line (.readLine r)]
                             (.add err-lines line)
                             (when (> (.size err-lines) 200) (.poll err-lines))
                             (recur)))))]
        (with-open [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream p)))]
          (loop []
            (when-let [line (.readLine r)]
              (when-let [[_ us] (re-find #"out_time_ms=(\d+)" line)]
                (let [secs (/ (Double/parseDouble us) 1e6)]
                  (videos/set-progress! video-id (min 0.99 (max 0.0 (/ secs dur))))))
              (recur))))
        (let [exit (.waitFor p)]
          (a/<!! err-done)
          {:exit exit
           :stderr (str/join "\n" err-lines)}))
      (finally
        (swap! running disj p)))))

(defn stop!
  "Ends every running ffmpeg: destroy (SIGTERM; TerminateProcess on Windows),
  then destroyForcibly on anything still alive after grace-ms. Runs as a JVM
  shutdown hook so an encode never outlives the app (#49). The job's row
  stays \"transcoding\", which requeue-stuck resumes on the next start."
  ([] (stop! 3000))
  ([grace-ms]
   (reset! stopping? true)
   (let [ps @running]
     (doseq [^Process p ps] (.destroy p))
     (doseq [^Process p ps]
       (when-not (.waitFor p grace-ms TimeUnit/MILLISECONDS)
         (.destroyForcibly p)
         (.waitFor p 1000 TimeUnit/MILLISECONDS)))
     (swap! running #(apply disj % ps))
     (when (seq ps) (log/info "transcode: stopped" (count ps) "ffmpeg process(es)")))))

(defn- note-interrupted [video-id]
  (log/info "transcode interrupted by shutdown:" video-id "(resumes on next start)"))

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
              _ (log/info (format "transcode start: %s (%.0fs, height %d, ladder %s)"
                                 (:title video) (:duration media) (:height media)
                                 (str/join ", " (map :h ladder))))
              _ (videos/set-duration! video-id (:duration media))
              _ (videos/set-status! video-id "transcoding" :progress 0.0)
              {:keys [exit stderr]} (run-ffmpeg (ffmpeg-cmd in out media ladder)
                                                video-id (:duration media))]
          (cond
            (zero? exit)
            (do (videos/set-status! video-id "ready" :progress 1.0 :error nil)
                (when-not (:keep-original cfg) (storage/delete-original! video-id))
                (log/info "transcoded" (:title video) "-> ready"))
            ;; killed by stop!: leave it "transcoding" so the next start
            ;; requeues it rather than showing a failure the user never caused
            @stopping? (note-interrupted video-id)
            :else
            (do (videos/set-status! video-id "failed" :error stderr)
                (log/info "transcode FAILED" (:title video) "::"
                         (str/join (take-last 300 stderr))))))
        (catch Exception e
          ;; destroy closes ffmpeg's pipes under the reader ("Stream closed"),
          ;; so a stop! lands here on Linux rather than at the exit code
          (if @stopping?
            (note-interrupted video-id)
            (do (videos/set-status! video-id "failed" :error (str e))
                (log/info "transcode ERROR" video-id (.getMessage e)))))))))

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
    (log/info "transcode workers:" (:workers cfg))))
