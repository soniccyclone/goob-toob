(ns goob-toob.transcode-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [goob-toob.config :as config]
            [goob-toob.services.transcode :as transcode]))

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
