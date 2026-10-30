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
            [goob-toob.repos.settings :as settings]
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

(def text-sub-codecs
  "Subtitle codecs ffmpeg can turn into WebVTT (dialogue and timing; ASS
  styling is lost). Anything else (PGS, DVD, DVB bitmaps) is listed as
  unavailable and never mapped."
  #{"ass" "ssa" "subrip" "srt" "webvtt" "mov_text" "text"})

(defn- stream-lang [s] (or (not-empty (get-in s [:tags :language])) "und"))
(defn- stream-title [s] (not-empty (get-in s [:tags :title])))

(defn- audio-tracks
  "ffprobe's audio streams in order; :i is the audio-relative index that
  -map 0:a:<i> takes."
  [streams]
  (vec (map-indexed (fn [i s]
                      {:i i :lang (stream-lang s) :title (stream-title s)
                       :channels (some-> s :channels long)
                       :default (= 1 (get-in s [:disposition :default]))})
                    (filter #(= "audio" (:codec_type %)) streams))))

(defn- sub-tracks
  "ffprobe's subtitle streams in order; :text whether it converts to WebVTT,
  and for those the sidecar name: subs-<lang>.vtt, then subs-<lang>-2.vtt
  for a second text track in the same language, and so on."
  [streams]
  (loop [[s & more] (filter #(= "subtitle" (:codec_type %)) streams)
         i 0 seen {} acc []]
    (if-not s
      acc
      (let [lang (stream-lang s)
            text? (contains? text-sub-codecs (:codec_name s))
            n (if text? (inc (get seen lang 0)) (get seen lang 0))
            t (cond-> {:i i :lang lang :title (stream-title s) :codec (:codec_name s) :text text?}
                text? (assoc :file (str "subs-" lang (when (> n 1) (str "-" n)) ".vtt")))]
        (recur more (inc i) (if text? (assoc seen lang n) seen) (conj acc t))))))

(defn- probe-media
  "The media facts the job needs: size, duration, every audio track and
  every subtitle track (see audio-tracks, sub-tracks). :audio? stays for
  the callers that only ask whether there is sound."
  [probe]
  (let [v (first (filter #(= "video" (:codec_type %)) (:streams probe)))
        audio (audio-tracks (:streams probe))
        dur (some-> probe :format :duration Double/parseDouble)]
    (when-not v (throw (ex-info "no video stream found" {})))
    {:height (or (some-> v :height long) 1080)
     :width (or (some-> v :width long) 1920)
     :audio? (boolean (seq audio))
     :audio audio
     :subs (sub-tracks (:streams probe))
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

;; ---- encoders: the GPU when it works, libx264 when it does not ----------------
;; Four hardware families, in preference order, each with up to three tiers:
;;   :full    the GPU decodes, scales every rung and encodes (nothing touches
;;            the CPU but the audio);
;;   :upload  the CPU decodes once, the frames go up, the GPU scales every
;;            rung and encodes (a source the GPU cannot decode: 10-bit on an
;;            old part, a codec it lacks, or NVDEC missing as under WSL2);
;;   :encode  the CPU decodes and scales, the GPU only encodes.
;; An encoder being listed by ffmpeg means nothing: no driver, a laptop with
;; the dGPU asleep, a VM, a container without the device. So probe-engines!
;; runs a real one-second job per engine and tier at startup (the :full
;; probe decodes a real H.264 sample, so a GPU that cannot decode is caught
;; there and not on every job), then times every working tier against
;; libx264 on a short clip and keeps the fastest: a GPU is only chosen when
;; it is at least as fast as the CPU, within a margin, because it also
;; frees the CPU that hosts the party (a 16-core box ties a laptop GPU, and
;; WSL2's upload path is slower than x264). A job that still fails on the
;; GPU falls back a tier at a time, then to the CPU (attempts), so an encode
;; never fails for a GPU reason. The admin can switch it all off (settings
;; :hwenc "off").

(def ^:private test-src "testsrc2=duration=1:size=640x360:rate=30")
(def ^:private bench-src "testsrc2=duration=20:size=1280x720:rate=30") ; long enough that an encoder's start-up does not decide it
(def ^:private bench-ladder [{:h 720 :v 2800} {:h 360 :v 600}])
(def gpu-margin
  "A GPU tier is chosen when its bench wall time is at most this times the
  CPU's: it then costs nothing in speed and gives the CPU back."
  1.15)

(defn hw-device
  "The Linux render node for VAAPI (GOOB_HW_DEVICE, else the first
  /dev/dri/renderD*); nil on Windows and on a box without one."
  []
  (or (System/getenv "GOOB_HW_DEVICE")
      (when-not windows?
        (first (for [n (range 128 136)
                     :let [p (str "/dev/dri/renderD" n)]
                     :when (.exists (java.io.File. p))]
                 p)))))

(def engines
  "Hardware encoders in preference order. :init are global ffmpeg args,
  :decode the input-side args of the :full tier, :scale builds one rung's
  GPU scaler, :sw-format the pixel format the encoder takes from system
  memory in the :encode tier (nil: the encoder has no :encode tier),
  :upload the filter that lifts system frames onto the GPU (the :upload
  tier and the probes), :upload? whether the :encode tier's system frames
  must be hwuploaded too (VAAPI), :codec the encoder and its quality knobs.
  :device? needs hw-device."
  [{:id :nvenc :vendor "NVIDIA NVENC" :encoder "h264_nvenc"
    :init ["-init_hw_device" "cuda=hw" "-filter_hw_device" "hw"]
    :decode ["-hwaccel" "cuda" "-hwaccel_output_format" "cuda" "-extra_hw_frames" "16"]
    :scale (fn [w h] (str "scale_cuda=" w ":" h ":format=yuv420p"))
    :upload "format=yuv420p,hwupload_cuda"
    :sw-format "yuv420p"
    :codec ["-c:v" "h264_nvenc" "-preset" "p4" "-tune" "hq" "-rc" "vbr" "-profile:v" "high"
            "-bf" "2" "-spatial-aq" "1" "-forced-idr" "1"]}
   {:id :qsv :vendor "Intel Quick Sync" :encoder "h264_qsv"
    :init ["-init_hw_device" "qsv=hw" "-filter_hw_device" "hw"]
    :decode ["-hwaccel" "qsv" "-hwaccel_output_format" "qsv" "-extra_hw_frames" "16"]
    :scale (fn [w h] (str "scale_qsv=w=" w ":h=" h ":format=nv12"))
    :upload "format=nv12,hwupload=extra_hw_frames=16"
    :sw-format "nv12"
    :codec ["-c:v" "h264_qsv" "-preset" "medium" "-profile:v" "high" "-look_ahead" "0" "-forced_idr" "1"]}
   {:id :amf :vendor "AMD AMF" :encoder "h264_amf"
    ;; AMF has no portable decode/scale path in ffmpeg: encode tier only
    :init [] :decode nil :scale nil :upload nil
    :sw-format "nv12"
    :codec ["-c:v" "h264_amf" "-usage" "transcoding" "-quality" "balanced" "-rc" "vbr_peak" "-profile:v" "high"]}
   {:id :vaapi :vendor "VAAPI" :encoder "h264_vaapi" :device? true
    :init (fn [dev] ["-init_hw_device" (str "vaapi=hw:" dev) "-filter_hw_device" "hw"])
    :decode ["-hwaccel" "vaapi" "-hwaccel_output_format" "vaapi" "-extra_hw_frames" "16"]
    :scale (fn [w h] (str "scale_vaapi=w=" w ":h=" h ":format=nv12"))
    :upload "format=nv12,hwupload"
    :sw-format "nv12" :upload? true
    :codec ["-c:v" "h264_vaapi" "-profile:v" "high" "-rc_mode" "VBR"]}])

(defn- engine [id] (first (filter #(= id (:id %)) engines)))

(defn- init-args [eng dev]
  (let [i (:init eng)] (if (fn? i) (i dev) i)))

(defn even-width
  "The even width that keeps the source's aspect at height h (the GPU
  scalers take no -2)."
  [{:keys [width height]} h]
  (let [w (Math/round (* (double h) (/ (double width) (double height))))]
    (long (if (odd? w) (inc w) w))))

(defn parse-encoders
  "The encoder names in `ffmpeg -encoders` output."
  [text]
  (set (keep #(second (re-find #"^\s*[VASFXBD.]{6}\s+([A-Za-z0-9_]+)\s" %)) (str/split-lines text))))

(def ^:private tier-order [:full :upload :encode])

(defn attempts
  "What a job tries, in order, from the probed engine and tier: the probed
  pair, then each lower tier the engine has, then the CPU. hwenc \"off\"
  or no engine: the CPU alone."
  [{:keys [engine tier]} hwenc]
  (if (or (= "off" hwenc) (nil? engine) (= :cpu engine))
    [[:cpu :cpu]]
    (let [eng (first (filter #(= engine (:id %)) engines))
          has (set (cond-> [] (:scale eng) (conj :full)
                     (and (:upload eng) (:scale eng)) (conj :upload)
                     (:sw-format eng) (conj :encode)))]
      (conj (vec (for [t (drop-while #(not= tier %) tier-order) :when (has t)] [engine t]))
            [:cpu :cpu]))))

(defn- rung-bitrates [ladder hw?]
  (mapcat (fn [i {:keys [v]}]
            (concat [(str "-b:v:" i) (str v "k")]
                    ;; VBR on a GPU encoder wanders more than x264: pin it
                    (when hw? [(str "-maxrate:v:" i) (str (long (* 1.5 v)) "k")
                               (str "-bufsize:v:" i) (str (* 2 v) "k")])))
          (range) ladder))

(defn- audio-of
  "The audio tracks a command maps: the probe's :audio, or one nameless
  track when a caller only said :audio? true."
  [{:keys [audio? audio]}]
  (or (not-empty audio) (when audio? [{:i 0}])))

(defn- dash-args
  "The audio side and the dash muxer. Video rungs are outputs 0..n-1, audio
  track k is output n+k, and every one is its own adaptation set so each
  language is a switchable track, not a quality rung of the first (the
  muxer writes the set's lang from the stream's language metadata)."
  [n audio ^java.io.File out]
  (concat
   ;; stereo only: multi-channel ffmpeg-AAC carries a PCE that Chrome's
   ;; decoder rejects (CHUNK_DEMUXER_ERROR_APPEND_FAILED → spinner forever),
   ;; and a watch party through a quick tunnel doesn't need 5.1
   (when (seq audio) ["-c:a" "aac" "-b:a" "128k" "-ac" "2"])
   (mapcat (fn [k {:keys [lang title]}]
             (concat (when lang [(str "-metadata:s:a:" k) (str "language=" lang)])
                     (when title [(str "-metadata:s:a:" k) (str "title=" title)])))
           (range) audio)
   ["-f" "dash" "-seg_duration" "4" "-use_template" "1" "-use_timeline" "1"
    "-adaptation_sets" (str/join " " (cons (str "id=0,streams=" (str/join "," (range n)))
                                           (map (fn [k] (str "id=" (inc k) ",streams=" (+ n k)))
                                                (range (count audio)))))
    "-progress" "pipe:1" "-nostats"
    (ffmpeg-path out)]))

(defn- subtitle-outputs
  "One WebVTT sidecar output per text subtitle, next to the manifest (ASS,
  SSA, SubRip, mov_text: a built-in conversion). Bitmap tracks stay out."
  [tracks ^java.io.File out]
  (let [mpd (ffmpeg-path out)
        dir (subs mpd 0 (inc (.lastIndexOf mpd "/")))]
    (mapcat (fn [{:keys [i file]}]
              ["-map" (str "0:s:" i) "-c:s" "webvtt" (str dir file)])
            (filter :text tracks))))

(defn- ffmpeg-cmd
  "The whole ffmpeg command for one attempt. [:cpu :cpu] is the original
  libx264 pipeline; a hardware [engine tier] swaps the video side."
  ([in out media ladder] (ffmpeg-cmd in out media ladder [:cpu :cpu]))
  ([^java.io.File in ^java.io.File out {:keys [subs] :as media} ladder [eid tier]]
   (let [n (count ladder)
         audio (audio-of media)
         eng (engine eid)
         hw? (some? eng)
         dev (hw-device)
         outs (str/join (for [i (range n)] (str "[s" i "]")))
         ;; :upload lifts the decoded frames once, then splits on the GPU
         split (str "[0:v]" (when (= :upload tier) (str (:upload eng) ",")) "split=" n outs ";")
         rung (fn [i h]
                (str "[s" i "]"
                     (case tier
                       (:full :upload) ((:scale eng) (even-width media h) h)
                       :encode (str "scale=-2:" h ",format=" (:sw-format eng)
                                    (when (:upload? eng) ",hwupload"))
                       (str "scale=-2:" h))
                     "[v" i "]"))
         scales (str/join ";" (map-indexed (fn [i r] (rung i (:h r))) ladder))
         maps-v (mapcat (fn [i] ["-map" (str "[v" i)]) (range n))
         maps-a (mapcat (fn [{:keys [i]}] ["-map" (str "0:a:" i)]) audio)]
     (concat
      ;; nice is Unix-only; on Windows just run ffmpeg directly
      (when-not windows? ["nice" "-n" "10"])
      [(ffmpeg-bin) "-hide_banner" "-loglevel" "warning" "-nostdin" "-y"]
      (when hw? (init-args eng dev))
      (when (= :full tier) (:decode eng))
      ["-i" (.getPath in)]
      ["-filter_complex" (str split scales)]
      maps-v maps-a
      (if hw?
        (:codec eng)
        ["-c:v" "libx264" "-preset" "veryfast" "-profile:v" "high" "-pix_fmt" "yuv420p"
         "-sc_threshold" "0"])
      ["-force_key_frames" "expr:gte(t,n_forced*4)"]
      (rung-bitrates ladder hw?)
      (dash-args n audio out)
      (subtitle-outputs subs out)))))

;; ---- the probe --------------------------------------------------------------------

(defonce engine* (atom nil)) ; {:engine kw :tier kw :tried [...] :at ms} once probed

(defn- run-quiet
  "Runs cmd, waits up to timeout-ms. {:ok? :err}; a timeout is a failure."
  [cmd timeout-ms]
  (try
    (let [p (-> (ProcessBuilder. (vec cmd)) (.redirectErrorStream true) .start)
          out (future (read-all (.getInputStream p)))]
      (if (.waitFor p timeout-ms TimeUnit/MILLISECONDS)
        {:ok? (zero? (.exitValue p)) :err (apply str (take-last 300 @out))}
        (do (.destroyForcibly p) {:ok? false :err "timed out"})))
    (catch Exception e {:ok? false :err (.getMessage e)})))

(defn available-encoders []
  (let [p (-> (ProcessBuilder. [(ffmpeg-bin) "-hide_banner" "-encoders"]) .start)
        out (read-all (.getInputStream p))]
    (.waitFor p)
    (parse-encoders out)))

(defn- probe-sample!
  "A one-second H.264 file for the :full probe to decode (libx264, the
  encoder every build has); nil when it cannot be made."
  []
  (let [f (java.io.File. (System/getProperty "java.io.tmpdir") "goob-probe.mp4")]
    (if (or (.exists f)
            (:ok? (run-quiet [(ffmpeg-bin) "-hide_banner" "-loglevel" "error" "-nostdin" "-y"
                              "-f" "lavfi" "-i" test-src "-c:v" "libx264" "-preset" "ultrafast"
                              "-pix_fmt" "yuv420p" (.getPath f)]
                             30000)))
      f
      nil)))

(defn- probe-cmd
  "A one-second job that exercises the tier: :full decodes the H.264 sample
  on the GPU and scales there, :upload lifts synthetic frames to the GPU
  and scales there, :encode hands the encoder system frames."
  [eng tier dev sample]
  (concat
   [(ffmpeg-bin) "-hide_banner" "-loglevel" "error" "-nostdin"]
   (init-args eng dev)
   (case tier
     :full   (concat (:decode eng) ["-i" (.getPath ^java.io.File sample)
                                   "-vf" ((:scale eng) 160 90)])
     :upload ["-f" "lavfi" "-i" test-src "-vf" (str (:upload eng) "," ((:scale eng) 320 180))]
     :encode ["-f" "lavfi" "-i" test-src
              "-vf" (str "format=" (:sw-format eng) (when (:upload? eng) ",hwupload"))])
   (:codec eng)
   ["-b:v" "800k" "-f" "null" "-"]))

(defn- bench-cmd
  "The real pipeline on a short synthetic clip to -f null: what a job would
  run, minus the dash muxer, so tiers can be timed against each other."
  [[eid tier] dev]
  (let [eng (engine eid)
        n (count bench-ladder)
        media {:width 1280 :height 720}
        outs (str/join (for [i (range n)] (str "[s" i "]")))
        split (str "[0:v]" (when (= :upload tier) (str (:upload eng) ",")) "split=" n outs ";")
        rung (fn [i h]
               (str "[s" i "]"
                    (case tier
                      (:full :upload) ((:scale eng) (even-width media h) h)
                      :encode (str "scale=-2:" h ",format=" (:sw-format eng) (when (:upload? eng) ",hwupload"))
                      (str "scale=-2:" h))
                    "[v" i "]"))]
    (concat
     [(ffmpeg-bin) "-hide_banner" "-loglevel" "error" "-nostdin"]
     (when eng (init-args eng dev))
     ["-f" "lavfi" "-i" bench-src
      "-filter_complex" (str split (str/join ";" (map-indexed (fn [i r] (rung i (:h r))) bench-ladder)))]
     (mapcat (fn [i] ["-map" (str "[v" i)]) (range n))
     (if eng
       (:codec eng)
       ["-c:v" "libx264" "-preset" "veryfast" "-profile:v" "high" "-pix_fmt" "yuv420p" "-sc_threshold" "0"])
     (rung-bitrates bench-ladder (some? eng))
     ["-f" "null" "-"])))

(defn bench!
  "Wall milliseconds of the bench for one attempt; nil when it fails.
  Public so a test can pin the times instead of racing fake processes."
  [att dev]
  (let [t0 (System/nanoTime)
        {:keys [ok?]} (run-quiet (bench-cmd att dev) 120000)]
    (when ok? (long (/ (- (System/nanoTime) t0) 1e6)))))

(defn choose
  "The attempt to run jobs on, from the bench times: the fastest GPU tier
  when it is within gpu-margin of the CPU, else the CPU. bench is
  {[engine tier] ms}; the CPU's key is [:cpu :cpu]."
  [bench]
  (let [cpu (get bench [:cpu :cpu])
        gpus (sort-by val (dissoc bench [:cpu :cpu]))
        [best ms] (first gpus)]
    (cond
      (nil? best) [:cpu :cpu]
      (nil? cpu) best
      (<= ms (* gpu-margin cpu)) best
      :else [:cpu :cpu])))

(defn- tiers-of [eng]
  (cond-> []
    (and (:decode eng) (:scale eng)) (conj :full)
    (and (:upload eng) (:scale eng)) (conj :upload)
    (:sw-format eng) (conj :encode)))

(defn probe-engines!
  "Tries every engine ffmpeg lists, full tier then encode tier, with a real
  encode; keeps the first that works. Cached in engine*; see start!."
  []
  (let [listed (try (available-encoders) (catch Exception _ #{}))
        dev (hw-device)
        sample (probe-sample!)
        tried (atom [])
        candidates (for [eng engines
                         :when (contains? listed (:encoder eng))
                         :when (or (not (:device? eng)) dev)
                         tier (tiers-of eng)
                         :when (or (not= :full tier) sample)]
                     [eng tier])
        ;; every tier that works at all (doall: the probes are side effects)
        working (doall
                 (for [[eng tier] candidates
                       :let [{:keys [ok? err]} (run-quiet (probe-cmd eng tier dev sample) 30000)
                             _ (swap! tried conj {:engine (:id eng) :tier tier :ok? ok?
                                                  :err (when-not ok? (last (str/split-lines (str err))))})]
                       :when ok?]
                   [(:id eng) tier]))
        ;; then the race: each working GPU tier and the CPU on the same clip
        bench (when (seq working)
                (into {} (keep (fn [att] (when-let [ms (bench! att dev)] [att ms]))
                               (conj (vec working) [:cpu :cpu]))))
        [eid tier] (choose bench)
        result {:engine eid :tier tier
                :bench (into {} (map (fn [[k v]] [(str/join "/" (map name k)) v]) bench))
                :tried @tried :listed (vec (filter (set (map :encoder engines)) listed))
                :at (System/currentTimeMillis)}
        times (str/join ", " (for [[k v] (sort-by val bench)] (str (str/join "/" (map name k)) " " v " ms")))]
    (reset! engine* result)
    (cond
      (not= :cpu eid)
      (log/info "transcode: encoder" (:vendor (engine eid))
                (case tier
                  :full "(full GPU pipeline)"
                  :upload "(GPU scale and encode, CPU decode)"
                  "(GPU encode, CPU decode and scale)")
                "; bench:" times)
      (seq working)
      (log/info "transcode: software libx264; the GPU works but is slower here; bench:" times)
      :else
      (log/info "transcode: software libx264;"
                (if (seq @tried)
                  (str "no working GPU encoder: "
                       (str/join "; " (map #(str (name (:engine %)) "/" (name (:tier %)) " " (:err %)) @tried)))
                  "ffmpeg lists no GPU encoder")))
    result))

(defn encoder-info
  "What the admin console shows."
  []
  (let [{:keys [engine tier tried listed bench] :as e} (or @engine* {:engine :cpu :tier :cpu :probing? true})
        times (when (seq bench)
                (str " (bench: " (str/join ", " (for [[k v] (sort-by val bench)] (str k " " v " ms"))) ")"))]
    {:engine (name engine) :tier (name tier)
     :label (cond
              (:probing? e) "probing the GPU…"
              (= :cpu engine) (str "software (libx264)"
                                   (when-let [d (:downgraded e)] (str ", " d " gave up mid-job"))
                                   times)
              :else (str (:vendor (goob-toob.services.transcode/engine engine))
                         (case tier
                           :full ", full GPU pipeline"
                           :upload ", GPU scale and encode"
                           ", GPU encode")
                         times))
     :bench bench :listed listed :tried tried}))

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

(defn- wait-probe!
  "The first job after start waits for the startup probe (seconds), never
  longer than a minute."
  []
  (loop [i 0]
    (when (and (:probing? @engine*) (< i 120))
      (Thread/sleep 500)
      (recur (inc i)))))

(defn- clear-dash! [^java.io.File out]
  (doseq [^java.io.File f (.listFiles (.getParentFile out))] (.delete f)))

(defn- run-attempts!
  "Runs the job on each of (attempts) in turn until one exits 0 or the CPU
  has had its go. A GPU failure is logged and the next attempt starts from
  a clean dash dir; when the GPU's :encode tier itself fails the probe
  result is downgraded so later jobs skip it. Returns the last run's
  {:exit :stderr}."
  [in out media ladder video-id]
  (wait-probe!)
  (loop [[[eid tier :as att] & more] (attempts @engine* (settings/get :hwenc))]
    (let [{:keys [exit stderr] :as r} (run-ffmpeg (ffmpeg-cmd in out media ladder att)
                                                  video-id (:duration media))]
      (if (or (zero? exit) @stopping? (empty? more))
        (do (when (and (zero? exit) (not= :cpu eid))
              (log/info "transcode: encoded on" (name eid) (name tier)))
            r)
        (do (log/info "transcode:" (name eid) (name tier) "failed, falling back to"
                      (str/join "/" (map name (first more))) "::" (str/join (take-last 200 stderr)))
            (when (= :encode tier)
              (swap! engine* assoc :engine :cpu :tier :cpu :downgraded (name eid)))
            (clear-dash! out)
            (videos/set-progress! video-id 0.0)
            (recur more))))))

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
              _ (log/info (format "transcode start: %s (%.0fs, height %d, ladder %s, audio %s, subs %s)"
                                 (:title video) (:duration media) (:height media)
                                 (str/join ", " (map :h ladder))
                                 (str/join ", " (map :lang (:audio media)))
                                 (str/join ", " (map #(str (:lang %) (when-not (:text %) " (bitmap)")) (:subs media)))))
              _ (videos/set-duration! video-id (:duration media))
              _ (videos/set-tracks! video-id (select-keys media [:audio :subs]))
              _ (videos/set-status! video-id "transcoding" :progress 0.0)
              {:keys [exit stderr]} (run-attempts! in out media ladder video-id)]
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
    ;; the probe runs real encodes (a few seconds at most); the first job
    ;; waits for it, the server does not
    (when-not @engine*
      (reset! engine* {:engine :cpu :tier :cpu :probing? true})
      (future (probe-engines!)))
    (doseq [vid (videos/requeue-stuck)] (submit! vid))
    (log/info "transcode workers:" (:workers cfg))))
