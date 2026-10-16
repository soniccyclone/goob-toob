(ns goob-toob.transcode-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [goob-toob.services.transcode :as transcode]))

(deftest dash-output-path-uses-forward-slashes-so-ffmpeg-finds-the-segment-dir
  (let [cmd (#'transcode/ffmpeg-cmd
             (io/file "C:\\Users\\goob\\videos\\uploads\\v1.mkv")
             (io/file "C:\\Users\\goob\\videos\\dash\\v1\\manifest.mpd")
             {:audio? true} [{:h 360 :v 600}])]
    (is (= "C:/Users/goob/videos/dash/v1/manifest.mpd" (last cmd)))))
