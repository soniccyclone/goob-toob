(ns goob-toob.transcode-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [goob-toob.config :as config]
            [goob-toob.repos.storage :as storage]
            [goob-toob.repos.video :as videos]
            [goob-toob.services.transcode :as transcode]
            [goob-toob.test-util :refer [with-tmp]]))

(defn- with-no-ffmpeg-running
  "stop! leaves stopping? set for the rest of the JVM; tests start clean."
  [f]
  (reset! @#'transcode/stopping? false)
  (reset! @#'transcode/running #{})
  (f))

(use-fixtures :each with-tmp with-no-ffmpeg-running)

(deftest dash-output-path-uses-forward-slashes-so-ffmpeg-finds-the-segment-dir
  (let [cmd (#'transcode/ffmpeg-cmd
             (io/file "C:\\Users\\goob\\videos\\uploads\\v1.mkv")
             (io/file "C:\\Users\\goob\\videos\\dash\\v1\\manifest.mpd")
             {:audio? true} [{:h 360 :v 600}])]
    (is (= "C:/Users/goob/videos/dash/v1/manifest.mpd" (last cmd)))))

(deftest audio-is-mapped-once-however-many-video-rungs
  (let [cmd (#'transcode/ffmpeg-cmd (io/file "v1.mkv") (io/file "dash/v1/manifest.mpd")
                                    {:audio? true} config/default-ladder)]
    (is (= 1 (count (filter #{"0:a:0"} cmd))))))

(deftest ffmpeg-logs-only-warnings-and-errors
  (let [cmd (#'transcode/ffmpeg-cmd (io/file "v1.mkv") (io/file "dash/v1/manifest.mpd")
                                    {:audio? true} config/default-ladder)]
    (is (= ["-loglevel" "warning"] (take 2 (drop-while #(not= "-loglevel" %) cmd))))))

(deftest ladder-for-keeps-exact-rung-and-everything-below
  (let [ladder (transcode/ladder-for {:height 720} config/default-ladder)]
    (is (= [720 480 360] (map :h ladder)))
    (is (= 2000 (:v (first ladder))))))

(deftest ladder-for-adds-the-source-height-between-rungs
  (let [ladder (transcode/ladder-for {:height 696} config/default-ladder)]
    (is (= [696 480 360] (map :h ladder)))
    (is (= {:h 696 :v 1869} (first ladder)))))

(deftest ladder-for-scales-the-bitrate-from-the-rung-above
  (let [ladder (transcode/ladder-for {:height 800} config/default-ladder)]
    (is (= [800 720 480 360] (map :h ladder)))
    (is (= {:h 800 :v 2195} (first ladder)))))

(deftest ladder-for-never-exceeds-the-top-rung
  (is (= [1080 720 480 360]
         (map :h (transcode/ladder-for {:height 2160} config/default-ladder)))))

(deftest ladder-for-never-upscales-a-source-below-the-ladder
  (is (= [{:h 240 :v 200}] (transcode/ladder-for {:height 240} config/default-ladder))))

(deftest priority-cmd-lowers-ffmpeg-on-windows
  (is (= ["powershell" "-NoProfile" "-NonInteractive" "-Command"
          "(Get-Process -Id 4242).PriorityClass='BelowNormal'"]
         (transcode/priority-cmd 4242 true))))

(deftest priority-cmd-is-nil-elsewhere
  (is (nil? (transcode/priority-cmd 4242 false))))

(deftest probe-media-reads-height-audio-and-duration
  (is (= {:height 696 :width 1920 :audio? true :duration 12.5
          :audio [{:i 0 :lang "und" :title nil :channels nil :default false}] :subs []}
         (#'transcode/probe-media {:streams [{:codec_type "video" :height 696} {:codec_type "audio"}]
                                   :format {:duration "12.5"}}))))

(deftest probe-media-defaults-when-fields-are-missing
  (is (= {:height 1080 :width 1920 :audio? false :duration 1.0 :audio [] :subs []}
         (#'transcode/probe-media {:streams [{:codec_type "video"}] :format {}}))))

(deftest probe-media-refuses-a-file-without-video
  (is (thrown? clojure.lang.ExceptionInfo
               (#'transcode/probe-media {:streams [{:codec_type "audio"}] :format {:duration "3.0"}}))))

(deftest ffprobe-sits-next-to-a-vendored-ffmpeg
  (with-redefs [config/cfg (constantly {:ffmpeg-path "/opt/g/ffmpeg/ffmpeg.exe"})]
    (is (= "/opt/g/ffmpeg/ffprobe.exe" (#'transcode/ffprobe-bin))))
  (with-redefs [config/cfg (constantly {:ffmpeg-path "ffmpeg"})]
    (is (= "ffprobe" (#'transcode/ffprobe-bin)))))

(deftest ffmpeg-cmd-omits-audio-when-the-source-has-none
  (let [cmd (#'transcode/ffmpeg-cmd (io/file "v1.mkv") (io/file "dash/v1/manifest.mpd")
                                    {:audio? false} config/default-ladder)]
    (is (not-any? #{"0:a:0"} cmd))
    (is (not-any? #{"-c:a"} cmd))
    (is (= "id=0,streams=0,1,2,3" (second (drop-while #(not= "-adaptation_sets" %) cmd)))
        "the four rungs, by output index, and no audio set")))

(defn- script! [^java.io.File f body]
  (spit f (str "#!/bin/sh\n" body))
  (.setExecutable f true))

(defn- fake-ffmpeg!
  "Points :ffmpeg-path at a shell-script ffmpeg (with an ffprobe beside it)
  that reports a 720p, 2 s source with audio, emits two progress lines,
  writes the manifest and exits with exit."
  [exit]
  (let [bin (doto (io/file (storage/root) "bin") (.mkdirs))]
    (script! (io/file bin "ffprobe")
             (str "echo '{\"streams\":[{\"codec_type\":\"video\",\"height\":720},"
                  "{\"codec_type\":\"audio\"}],\"format\":{\"duration\":\"2.0\"}}'\n"))
    (script! (io/file bin "ffmpeg")
             (str "for last; do :; done\n"
                  (when-not (zero? exit) "echo 'x264 said no' >&2\n")
                  "echo out_time_ms=1000000\n"
                  "echo out_time_ms=2000000\n"
                  "echo '<MPD/>' > \"$last\"\n"
                  "exit " exit "\n"))
    (swap! config/config assoc :ffmpeg-path (str (io/file bin "ffmpeg")))))

(defn- video-with-original! [id]
  (videos/insert! {:id id :title "Clip" :original-path (str "uploads/" id ".mkv")})
  (let [f (io/file (storage/uploads-dir) (str id ".mkv"))]
    (spit f "not really a video")
    f))

(deftest process-job-deletes-the-original-when-told
  (fake-ffmpeg! 0)
  (swap! config/config assoc :keep-original false)
  (let [orig (video-with-original! "v1")]
    (#'transcode/process-job "v1")
    (is (not (.exists orig)))))

(deftest process-job-records-ffmpeg-stderr-on-failure
  (fake-ffmpeg! 1)
  (let [orig (video-with-original! "v1")]
    (#'transcode/process-job "v1")
    (let [v (videos/get-by-id "v1")]
      (is (= "failed" (:status v)))
      (is (str/includes? (str (:error v)) "x264 said no")))
    (is (.exists orig))))

(deftest process-job-fails-when-the-original-is-missing
  (fake-ffmpeg! 0)
  (videos/insert! {:id "v1" :title "Clip" :original-path "uploads/v1.mkv"})
  (#'transcode/process-job "v1")
  (let [v (videos/get-by-id "v1")]
    (is (= "failed" (:status v)))
    (is (str/includes? (str (:error v)) "original file missing"))))

(deftest progress-is-written-while-ffmpeg-runs
  (fake-ffmpeg! 0)
  (video-with-original! "v1")
  (let [seen (atom [])]
    (with-redefs [videos/set-progress! (fn [_ p] (swap! seen conj p))]
      (#'transcode/process-job "v1"))
    (is (= [0.5 0.99] @seen))))

(defn- long-process!
  "A process stop! has to end: `sleep 30`, or one that shrugs off SIGTERM so
  only destroyForcibly can end it. Registered as if run-ffmpeg had started it."
  [ignore-term?]
  (let [p (.start (ProcessBuilder. ["sh" "-c" (if ignore-term?
                                                "trap '' TERM; exec sleep 30"
                                                "exec sleep 30")]))]
    (swap! @#'transcode/running conj p)
    p))

(deftest stop!-ends-the-running-ffmpeg-and-forgets-it
  (let [p (long-process! false)]
    (is (.isAlive p))
    (transcode/stop!)
    (is (not (.isAlive p)))
    (is (empty? @@#'transcode/running))))

(deftest stop!-forces-an-ffmpeg-that-ignores-the-first-signal
  (let [p (long-process! true)]
    (transcode/stop! 200)
    (is (not (.isAlive p)))))

(deftest run-ffmpeg-forgets-the-process-once-it-exits
  (fake-ffmpeg! 0)
  (video-with-original! "v1")
  (#'transcode/process-job "v1")
  (is (empty? @@#'transcode/running))
  (is (= "ready" (:status (videos/get-by-id "v1")))))

(deftest a-job-killed-by-stop!-stays-transcoding-so-the-next-start-requeues-it
  (fake-ffmpeg! 1)
  (video-with-original! "v1")
  (reset! @#'transcode/stopping? true)
  (#'transcode/process-job "v1")
  (is (= "transcoding" (:status (videos/get-by-id "v1"))))
  (is (= ["v1"] (videos/requeue-stuck))))

(deftest a-job-whose-pipes-stop!-closed-stays-transcoding-too
  ;; on Linux Process.destroy closes ffmpeg's stdout under the reader, so the
  ;; worker sees an IOException rather than an exit code
  (fake-ffmpeg! 0)
  (video-with-original! "v1")
  (reset! @#'transcode/stopping? true)
  (with-redefs [transcode/run-ffmpeg (fn [& _] (throw (java.io.IOException. "Stream closed")))]
    (#'transcode/process-job "v1"))
  (is (= "transcoding" (:status (videos/get-by-id "v1"))))
  (is (nil? (:error (videos/get-by-id "v1")))))

;; ---- GPU engines ------------------------------------------------------------------

(defn- after [cmd flag] (second (drop-while #(not= flag %) cmd)))

(deftest the-cpu-command-is-the-original-pipeline
  (let [cmd (#'transcode/ffmpeg-cmd (io/file "v1.mkv") (io/file "dash/v1/manifest.mpd")
                                    {:audio? true :width 1920 :height 1080} [{:h 720 :v 2800} {:h 360 :v 600}])]
    (is (= "libx264" (after cmd "-c:v")))
    (is (= "[0:v]split=2[s0][s1];[s0]scale=-2:720[v0];[s1]scale=-2:360[v1]" (after cmd "-filter_complex")))
    (is (not-any? #{"-init_hw_device" "-hwaccel" "-maxrate:v:0"} cmd))))

(deftest the-nvenc-full-tier-decodes-scales-and-encodes-on-the-gpu
  (let [cmd (#'transcode/ffmpeg-cmd (io/file "v1.mkv") (io/file "dash/v1/manifest.mpd")
                                    {:audio? true :width 960 :height 544} [{:h 480 :v 1400} {:h 360 :v 600}]
                                    [:nvenc :full])]
    (is (= "cuda=hw" (after cmd "-init_hw_device")))
    (is (= "cuda" (after cmd "-hwaccel")))
    (is (= "cuda" (after cmd "-hwaccel_output_format")))
    (is (= "[0:v]split=2[s0][s1];[s0]scale_cuda=848:480:format=yuv420p[v0];[s1]scale_cuda=636:360:format=yuv420p[v1]"
           (after cmd "-filter_complex")) "even widths from the source aspect, the GPU scaler takes no -2")
    (is (= "h264_nvenc" (after cmd "-c:v")))
    (is (= "2100k" (after cmd "-maxrate:v:0")))
    (is (= "2800k" (after cmd "-bufsize:v:0")))
    (is (= "expr:gte(t,n_forced*4)" (after cmd "-force_key_frames")))
    (is (not-any? #{"libx264" "-pix_fmt" "-sc_threshold"} cmd))
    (is (= "aac" (after cmd "-c:a")) "audio is untouched")
    (is (= "dash" (after cmd "-f")))))

(deftest the-encode-tier-decodes-and-scales-on-the-cpu
  (let [cmd (#'transcode/ffmpeg-cmd (io/file "v1.mkv") (io/file "dash/v1/manifest.mpd")
                                    {:audio? false :width 1920 :height 1080} [{:h 360 :v 600}]
                                    [:qsv :encode])]
    (is (= "qsv=hw" (after cmd "-init_hw_device")))
    (is (not-any? #{"-hwaccel"} cmd) "no GPU decode in this tier")
    (is (= "[0:v]split=1[s0];[s0]scale=-2:360,format=nv12[v0]" (after cmd "-filter_complex")))
    (is (= "h264_qsv" (after cmd "-c:v")))))

(deftest vaapi-uploads-system-frames-and-names-its-device
  (with-redefs [transcode/hw-device (constantly "/dev/dri/renderD129")]
    (let [cmd (#'transcode/ffmpeg-cmd (io/file "v1.mkv") (io/file "dash/v1/manifest.mpd")
                                      {:audio? false :width 1280 :height 720} [{:h 360 :v 600}]
                                      [:vaapi :encode])]
      (is (= "vaapi=hw:/dev/dri/renderD129" (after cmd "-init_hw_device")))
      (is (= "[0:v]split=1[s0];[s0]scale=-2:360,format=nv12,hwupload[v0]" (after cmd "-filter_complex")))
      (is (= "h264_vaapi" (after cmd "-c:v"))))))

(deftest amf-has-no-full-tier
  (is (= [:encode] (#'transcode/tiers-of (#'transcode/engine :amf))))
  (is (= [:full :upload :encode] (#'transcode/tiers-of (#'transcode/engine :nvenc)))))

(deftest the-upload-tier-decodes-on-the-cpu-and-scales-on-the-gpu
  (let [cmd (#'transcode/ffmpeg-cmd (io/file "v1.mkv") (io/file "dash/v1/manifest.mpd")
                                    {:audio? true :width 960 :height 544} [{:h 480 :v 1400} {:h 360 :v 600}]
                                    [:nvenc :upload])]
    (is (not-any? #{"-hwaccel"} cmd) "no GPU decode")
    (is (= "[0:v]format=yuv420p,hwupload_cuda,split=2[s0][s1];[s0]scale_cuda=848:480:format=yuv420p[v0];[s1]scale_cuda=636:360:format=yuv420p[v1]"
           (after cmd "-filter_complex")) "one upload, then the split and every scale on the GPU")
    (is (= "h264_nvenc" (after cmd "-c:v")))))

(deftest even-width-keeps-the-aspect-and-stays-even
  (is (= 1280 (transcode/even-width {:width 1920 :height 1080} 720)))
  (is (= 848 (transcode/even-width {:width 960 :height 544} 480)))
  (is (= 636 (transcode/even-width {:width 960 :height 544} 360)))
  (is (= 1708 (transcode/even-width {:width 1708 :height 720} 720))))

(deftest parse-encoders-reads-ffmpegs-table
  (is (= #{"libx264" "h264_nvenc" "h264_qsv"}
         (transcode/parse-encoders
          (str "Encoders:\n V..... = Video\n ------\n"
               " V....D libx264              libx264 H.264 / AVC\n"
               " V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)\n"
               " V..... h264_qsv             H.264 (Intel Quick Sync Video acceleration)\n")))))

(deftest attempts-fall-back-a-tier-then-to-the-cpu
  (is (= [[:nvenc :full] [:nvenc :upload] [:nvenc :encode] [:cpu :cpu]] (transcode/attempts {:engine :nvenc :tier :full} "auto")))
  (is (= [[:nvenc :upload] [:nvenc :encode] [:cpu :cpu]] (transcode/attempts {:engine :nvenc :tier :upload} "auto")))
  (is (= [[:amf :encode] [:cpu :cpu]] (transcode/attempts {:engine :amf :tier :encode} "auto")))
  (is (= [[:cpu :cpu]] (transcode/attempts {:engine :cpu :tier :cpu} "auto")))
  (is (= [[:cpu :cpu]] (transcode/attempts nil "auto")) "nothing probed yet")
  (is (= [[:cpu :cpu]] (transcode/attempts {:engine :nvenc :tier :full} "off")) "the admin switched it off"))

(defn- fake-ffmpeg-failing-on!
  "An ffmpeg that fails whenever its args mention token (and says so), and
  behaves like fake-ffmpeg! otherwise; lists libx264 and h264_nvenc."
  [token]
  (fake-ffmpeg! 0)
  (let [bin (io/file (storage/root) "bin")]
    (script! (io/file bin "ffmpeg")
             (str "case \"$*\" in *-encoders*) echo ' V....D libx264   x'; echo ' V....D h264_nvenc  x'; exit 0;; esac\n"
                  "echo \"$*\" >> \"$(dirname \"$0\")/calls\"\n"
                  "case \"$*\" in *" token "*) echo '" token " said no' >&2; exit 1;; esac\n"
                  "for last; do :; done\n"
                  "echo out_time_ms=1000000\n"
                  "echo out_time_ms=2000000\n"
                  "echo '<MPD/>' > \"$last\"\n"
                  "exit 0\n"))))

(defn- calls [] (str/split-lines (slurp (io/file (storage/root) "bin" "calls"))))

(deftest a-job-falls-back-from-the-gpu-to-the-cpu
  (fake-ffmpeg-failing-on! "h264_nvenc")
  (reset! transcode/engine* {:engine :nvenc :tier :full})
  (try
    (let [orig (video-with-original! "v1")]
      (#'transcode/process-job "v1")
      (is (= "ready" (:status (videos/get-by-id "v1"))))
      (let [cs (calls)]
        (is (= 4 (count cs)) "full, upload, encode, cpu")
        (is (str/includes? (first cs) "-hwaccel cuda"))
        (is (str/includes? (second cs) "hwupload_cuda,split"))
        (is (str/includes? (nth cs 2) "h264_nvenc"))
        (is (not (str/includes? (nth cs 2) "-hwaccel")))
        (is (str/includes? (last cs) "libx264")))
      (is (= :cpu (:engine @transcode/engine*)) "the encode tier failed too, so later jobs skip the GPU")
      (is (= "nvenc" (:downgraded @transcode/engine*))))
    (finally (reset! transcode/engine* nil))))

(deftest a-job-stays-on-the-gpu-when-it-works
  (fake-ffmpeg-failing-on! "never-matches")
  (reset! transcode/engine* {:engine :nvenc :tier :full})
  (try
    (video-with-original! "v1")
    (#'transcode/process-job "v1")
    (is (= "ready" (:status (videos/get-by-id "v1"))))
    (is (= 1 (count (calls))))
    (is (str/includes? (first (calls)) "h264_nvenc"))
    (is (= :nvenc (:engine @transcode/engine*)))
    (finally (reset! transcode/engine* nil))))

(deftest hwenc-off-keeps-a-job-on-the-cpu
  (fake-ffmpeg-failing-on! "never-matches")
  (reset! transcode/engine* {:engine :nvenc :tier :full})
  (try
    (goob-toob.repos.settings/set! :hwenc "off")
    (video-with-original! "v1")
    (#'transcode/process-job "v1")
    (is (= 1 (count (calls))))
    (is (str/includes? (first (calls)) "libx264"))
    (finally (reset! transcode/engine* nil))))

(deftest the-probe-tries-every-tier-then-races-the-working-ones-against-the-cpu
  ;; the fake lists libx264 and h264_nvenc; its nvenc :full probe fails on
  ;; the GPU decode (-hwaccel, the WSL2 shape: CUDA and NVENC but no NVDEC),
  ;; :upload and :encode work; every fake run takes the same time, so the
  ;; race is a tie and the GPU wins it (gpu-margin)
  (fake-ffmpeg-failing-on! "-hwaccel")
  (.delete (io/file (System/getProperty "java.io.tmpdir") "goob-probe.mp4")) ; a run before may have left it
  (try
    (let [r (transcode/probe-engines!)]
      (is (= :nvenc (:engine r)))
      (is (#{:upload :encode} (:tier r)))
      (is (= [{:engine :nvenc :tier :full :ok? false} {:engine :nvenc :tier :upload :ok? true}
              {:engine :nvenc :tier :encode :ok? true}]
             (mapv #(dissoc % :err) (:tried r))))
      (is (= #{"nvenc/upload" "nvenc/encode" "cpu/cpu"} (set (keys (:bench r)))) "the race ran the working tiers and the CPU")
      (is (every? number? (vals (:bench r))))
      (is (= ["h264_nvenc"] (:listed r)))
      (is (str/starts-with? (:label (transcode/encoder-info)) "NVIDIA NVENC, GPU "))
      (is (str/includes? (:label (transcode/encoder-info)) "bench:"))
      (is (str/includes? (first (calls)) "libx264") "the :full probe decodes a real sample made with libx264"))
    (finally (reset! transcode/engine* nil))))

(deftest choose-prefers-a-gpu-that-keeps-up-and-the-cpu-otherwise
  (is (= [:nvenc :encode] (transcode/choose {[:nvenc :encode] 5400 [:cpu :cpu] 5500})) "a tie goes to the GPU")
  (is (= [:nvenc :encode] (transcode/choose {[:nvenc :encode] 6200 [:cpu :cpu] 5500})) "within the margin")
  (is (= [:cpu :cpu] (transcode/choose {[:nvenc :upload] 11300 [:cpu :cpu] 5500})) "twice as slow: the CPU")
  (is (= [:nvenc :encode] (transcode/choose {[:nvenc :upload] 11300 [:nvenc :encode] 5400 [:cpu :cpu] 5500})) "the fastest GPU tier is the one compared")
  (is (= [:qsv :full] (transcode/choose {[:qsv :full] 3000 [:nvenc :encode] 5400 [:cpu :cpu] 5500})) "across engines too")
  (is (= [:cpu :cpu] (transcode/choose {[:cpu :cpu] 5500})) "nothing on the GPU")
  (is (= [:cpu :cpu] (transcode/choose {})))
  (is (= [:nvenc :encode] (transcode/choose {[:nvenc :encode] 5400})) "the CPU bench failed: still the GPU"))

(deftest a-gpu-that-is-slower-leaves-jobs-on-the-cpu
  (reset! transcode/engine* {:engine :cpu :tier :cpu :bench {"nvenc/upload" 11300 "cpu/cpu" 5500}})
  (try
    (is (= [[:cpu :cpu]] (transcode/attempts @transcode/engine* "auto")))
    (is (str/includes? (:label (transcode/encoder-info)) "software (libx264) (bench: cpu/cpu 5500 ms, nvenc/upload 11300 ms)"))
    (finally (reset! transcode/engine* nil))))

(deftest the-probe-falls-back-to-software-when-nothing-works
  (fake-ffmpeg-failing-on! "h264_nvenc")
  (try
    (let [r (transcode/probe-engines!)]
      (is (= :cpu (:engine r)))
      (is (= 3 (count (:tried r))))
      (is (= "software (libx264)" (:label (transcode/encoder-info)))))
    (finally (reset! transcode/engine* nil))))

;; ---- tracks (#85) -------------------------------------------------------------------

(def ^:private nabari
  "ffprobe of Nathan's dual-audio rip: h264 960x544, audio eng AAC 5.1
  (default) and jpn AAC stereo, one ASS subtitle (eng), a TTF attachment."
  (delay (json/read-str (slurp (io/resource "nabari-ffprobe.json")) :key-fn keyword)))

(deftest probe-media-keeps-every-audio-track-in-order
  (let [m (#'transcode/probe-media @nabari)]
    (is (= {:height 544 :width 960 :audio? true} (select-keys m [:height :width :audio?])))
    (is (= 1427.009 (:duration m)))
    (is (= [{:i 0 :lang "eng" :title "AAC" :channels 6 :default true}
            {:i 1 :lang "jpn" :title "ASS" :channels 2 :default false}]
           (:audio m)))))

(deftest probe-media-turns-text-subtitles-into-sidecar-names-and-ignores-attachments
  (let [m (#'transcode/probe-media @nabari)]
    (is (= [{:i 0 :lang "eng" :title "ASS" :codec "ass" :text true :file "subs-eng.vtt"}]
           (:subs m)))
    (is (= 5 (count (:streams @nabari))) "the fixture has the attachment stream the probe skips")))

(defn- probe-with-subs [& subs]
  {:streams (into [{:codec_type "video" :height 720 :width 1280} {:codec_type "audio" :channels 2}] subs)
   :format {:duration "10.0"}})

(deftest probe-media-lists-a-bitmap-subtitle-as-unavailable
  (let [m (#'transcode/probe-media (probe-with-subs {:codec_type "subtitle" :codec_name "hdmv_pgs_subtitle"
                                                     :tags {:language "eng"}}))]
    (is (= [{:i 0 :lang "eng" :title nil :codec "hdmv_pgs_subtitle" :text false}] (:subs m)))
    (is (not (contains? (first (:subs m)) :file)))))

(deftest probe-media-numbers-a-second-text-subtitle-in-the-same-language
  (let [m (#'transcode/probe-media
           (probe-with-subs {:codec_type "subtitle" :codec_name "subrip" :tags {:language "eng" :title "Full"}}
                            {:codec_type "subtitle" :codec_name "dvd_subtitle" :tags {:language "eng"}}
                            {:codec_type "subtitle" :codec_name "ass" :tags {:language "eng" :title "Signs"}}
                            {:codec_type "subtitle" :codec_name "mov_text"}))]
    (is (= [[0 "eng" true "subs-eng.vtt"] [1 "eng" false nil] [2 "eng" true "subs-eng-2.vtt"] [3 "und" true "subs-und.vtt"]]
           (mapv (juxt :i :lang :text :file) (:subs m)))
        "the bitmap track in between takes no number; no language tag is und")
    (is (= [{:i 0 :lang "und" :title nil :channels 2 :default false}] (:audio m)))))

(deftest probe-media-without-audio-has-no-tracks
  (is (= {:audio? false :audio [] :subs []}
         (select-keys (#'transcode/probe-media {:streams [{:codec_type "video"}] :format {}})
                      [:audio? :audio :subs]))))

(defn- cmd-for [media ladder]
  (#'transcode/ffmpeg-cmd (io/file "uploads/v1.mkv") (io/file "dash/v1/manifest.mpd") media ladder))

(defn- flags-with [cmd flag] (map second (filter #(= flag (first %)) (partition 2 1 cmd))))

(deftest ffmpeg-cmd-maps-every-audio-track-into-its-own-adaptation-set
  (let [media (#'transcode/probe-media @nabari)
        cmd   (cmd-for media [{:h 544 :v 1700} {:h 480 :v 1400} {:h 360 :v 600}])
        after-dash (drop-while #(not= "dash/v1/manifest.mpd" %) cmd)]
    (is (= ["[v0" "[v1" "[v2" "0:a:0" "0:a:1" "0:s:0"] (flags-with cmd "-map")))
    (is (= ["-c:a" "aac" "-b:a" "128k" "-ac" "2"
            "-metadata:s:a:0" "language=eng" "-metadata:s:a:0" "title=AAC"
            "-metadata:s:a:1" "language=jpn" "-metadata:s:a:1" "title=ASS"
            "-f" "dash"]
           (take 16 (drop-while #(not= "-c:a" %) cmd)))
        "one stereo AAC per track, each tagged with its language and title")
    (is (= "id=0,streams=0,1,2 id=1,streams=3 id=2,streams=4" (after cmd "-adaptation_sets"))
        "video rungs are outputs 0..2, the audio tracks 3 and 4, each its own set")
    (is (= ["dash/v1/manifest.mpd" "-map" "0:s:0" "-c:s" "webvtt" "dash/v1/subs-eng.vtt"] after-dash)
        "the subtitle sidecar is a second output after the manifest")))

(deftest ffmpeg-cmd-for-one-audio-track-is-todays-command-plus-metadata-and-explicit-set-indices
  ;; what main produced before #85 for a one-rung, one-audio source, with the
  ;; two changes a reviewer should see: the language/title metadata and the
  ;; adaptation sets by output index instead of the v/a shorthand
  (let [media (#'transcode/probe-media
               {:streams [{:codec_type "video" :height 360 :width 640}
                          {:codec_type "audio" :channels 2 :tags {:language "eng"}}]
                :format {:duration "10.0"}})
        today (concat (when-not transcode/windows? ["nice" "-n" "10"])
                      ["ffmpeg" "-hide_banner" "-loglevel" "warning" "-nostdin" "-y"
                       "-i" "uploads/v1.mkv"
                       "-filter_complex" "[0:v]split=1[s0];[s0]scale=-2:360[v0]"
                       "-map" "[v0" "-map" "0:a:0"
                       "-c:v" "libx264" "-preset" "veryfast" "-profile:v" "high" "-pix_fmt" "yuv420p" "-sc_threshold" "0"
                       "-force_key_frames" "expr:gte(t,n_forced*4)"
                       "-b:v:0" "600k"
                       "-c:a" "aac" "-b:a" "128k" "-ac" "2"
                       "-metadata:s:a:0" "language=eng"                   ; new
                       "-f" "dash" "-seg_duration" "4" "-use_template" "1" "-use_timeline" "1"
                       "-adaptation_sets" "id=0,streams=0 id=1,streams=1" ; was id=0,streams=v id=1,streams=a
                       "-progress" "pipe:1" "-nostats"
                       "dash/v1/manifest.mpd"])]
    (is (= today (cmd-for media [{:h 360 :v 600}])))
    (is (= 1 (count (filter #{"0:a:0"} (cmd-for media config/default-ladder)))) "still mapped once per source track")))

(deftest ffmpeg-cmd-without-audio-has-video-only-sets-and-no-sidecar
  (let [media (#'transcode/probe-media {:streams [{:codec_type "video" :height 720 :width 1280}] :format {}})
        cmd   (cmd-for media [{:h 720 :v 2800} {:h 360 :v 600}])]
    (is (= ["[v0" "[v1"] (flags-with cmd "-map")))
    (is (not-any? #{"-c:a" "-c:s" "webvtt"} cmd))
    (is (not-any? #(str/starts-with? % "-metadata") cmd))
    (is (= "id=0,streams=0,1" (after cmd "-adaptation_sets")))
    (is (= "dash/v1/manifest.mpd" (last cmd)))))

(deftest ffmpeg-cmd-skips-bitmap-subtitles-and-numbers-sidecars
  (let [media (#'transcode/probe-media
               (probe-with-subs {:codec_type "subtitle" :codec_name "subrip" :tags {:language "eng"}}
                                {:codec_type "subtitle" :codec_name "hdmv_pgs_subtitle" :tags {:language "eng"}}
                                {:codec_type "subtitle" :codec_name "ass" :tags {:language "eng"}}))
        cmd   (cmd-for media [{:h 360 :v 600}])]
    (is (= ["[v0" "0:a:0" "0:s:0" "0:s:2"] (flags-with cmd "-map")) "0:s:1 is the bitmap track")
    (is (= ["dash/v1/subs-eng.vtt" "dash/v1/subs-eng-2.vtt"] (flags-with cmd "webvtt")))))

(deftest sidecar-paths-use-forward-slashes-like-the-manifest
  (let [cmd (#'transcode/ffmpeg-cmd
             (io/file "C:\\Users\\goob\\videos\\uploads\\v1.mkv")
             (io/file "C:\\Users\\goob\\videos\\dash\\v1\\manifest.mpd")
             {:audio? true :subs [{:i 0 :lang "eng" :text true :file "subs-eng.vtt"}]} [{:h 360 :v 600}])]
    (is (= "C:/Users/goob/videos/dash/v1/subs-eng.vtt" (last cmd)))))

(deftest the-gpu-tiers-keep-the-same-audio-and-sidecar-side
  (let [media (#'transcode/probe-media @nabari)
        cpu   (cmd-for media [{:h 480 :v 1400} {:h 360 :v 600}])
        gpu   (#'transcode/ffmpeg-cmd (io/file "uploads/v1.mkv") (io/file "dash/v1/manifest.mpd") media
                                      [{:h 480 :v 1400} {:h 360 :v 600}] [:nvenc :full])
        tail  #(drop-while (fn [x] (not= "-c:a" x)) %)]
    (is (= (tail cpu) (tail gpu)))
    (is (= "h264_nvenc" (after gpu "-c:v")))))

(deftest process-job-stores-the-tracks-before-the-encode
  (fake-ffmpeg! 0)
  (video-with-original! "v1")
  (let [order (atom [])]
    (with-redefs [transcode/run-ffmpeg (let [f @#'transcode/run-ffmpeg]
                                         (fn [& args] (swap! order conj :ffmpeg) (apply f args)))
                  videos/set-tracks! (let [f videos/set-tracks!]
                                       (fn [& args] (swap! order conj :tracks) (apply f args)))]
      (#'transcode/process-job "v1"))
    (is (= [:tracks :ffmpeg] @order))
    (is (= {:audio [{:i 0 :lang "und" :title nil :channels nil :default false}] :subs []}
           (:tracks (videos/get-by-id "v1"))))
    (is (= "ready" (:status (videos/get-by-id "v1"))))))
