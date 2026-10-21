(ns goob-toob.transcode-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [goob-toob.config :as config]
            [goob-toob.repos.storage :as storage]
            [goob-toob.repos.video :as videos]
            [goob-toob.services.transcode :as transcode]
            [goob-toob.test-util :refer [with-tmp]]))

(use-fixtures :each with-tmp)

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
  (is (= {:height 696 :audio? true :duration 12.5}
         (#'transcode/probe-media {:streams [{:codec_type "video" :height 696} {:codec_type "audio"}]
                                   :format {:duration "12.5"}}))))

(deftest probe-media-defaults-when-fields-are-missing
  (is (= {:height 1080 :audio? false :duration 1.0}
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
    (is (= "id=0,streams=v" (second (drop-while #(not= "-adaptation_sets" %) cmd))))))

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

(defn- manifest [id] (io/file (storage/root) "dash" id "manifest.mpd"))

(deftest process-job-marks-the-video-ready-and-keeps-the-original
  (fake-ffmpeg! 0)
  (let [orig (video-with-original! "v1")]
    (#'transcode/process-job "v1")
    (let [v (videos/get-by-id "v1")]
      (is (= "ready" (:status v)))
      (is (= 1.0 (:progress v)))
      (is (= 2.0 (:duration v)))
      (is (nil? (:error v))))
    (is (.exists (manifest "v1")))
    (is (.exists orig))))

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

(defn- ffmpeg-on-path? []
  (try (zero? (.waitFor (.start (doto (ProcessBuilder. ["ffmpeg" "-version"])
                                  (.redirectErrorStream true)
                                  (.redirectOutput (java.io.File. "/dev/null"))))))
       (catch java.io.IOException _ false)))

(deftest real-ffmpeg-produces-a-dash-ladder
  (if-not (ffmpeg-on-path?)
    (is false "ffmpeg is not on PATH: run `make test` (the container has it) or install ffmpeg")
    (let [id "real"
          clip (io/file (storage/uploads-dir) (str id ".mkv"))
          gen (-> (ProcessBuilder. ["ffmpeg" "-hide_banner" "-loglevel" "error" "-y"
                                    "-f" "lavfi" "-i" "testsrc2=size=1280x720:rate=30:duration=2"
                                    "-f" "lavfi" "-i" "sine=duration=2"
                                    "-c:v" "libx264" "-preset" "ultrafast" "-c:a" "aac"
                                    "-shortest" (str clip)])
                  .inheritIO .start .waitFor)]
      (is (zero? gen) "generating the lavfi clip failed")
      (videos/insert! {:id id :title "Real" :original-path (str clip)})
      (#'transcode/process-job id)
      (is (= "ready" (:status (videos/get-by-id id))) (str (:error (videos/get-by-id id))))
      (is (some #(str/ends-with? (.getName ^java.io.File %) ".m4s")
                (file-seq (io/file (storage/root) "dash" id))))
      (let [mpd (slurp (manifest id))]
        (is (= 2 (count (re-seq #"<AdaptationSet" mpd))))
        (is (= 4 (count (re-seq #"<Representation" mpd))))))))
