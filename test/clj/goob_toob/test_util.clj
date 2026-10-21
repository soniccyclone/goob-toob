(ns goob-toob.test-util
  (:require [clojure.java.io :as io]
            [goob-toob.config :as config]
            [goob-toob.db :as db]
            [goob-toob.services.sync :as sync]))

(defn tmp-dir []
  (str (java.nio.file.Files/createTempDirectory
         "goob-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn with-tmp
  "Fresh storage dir + db + empty sync state around f. Sets admin-pass so
  container-mode routes can log in."
  [f]
  (let [dir (tmp-dir)]
    (swap! config/config merge {:storage-dir dir :ladder config/default-ladder
                                :keep-original true :workers 1
                                :admin-pass "pw" :ffmpeg-path "ffmpeg"})
    (db/init! (str dir "/test.db"))
    (reset! sync/rooms* {})
    (reset! sync/conns* {})
    (f)
    (reset! sync/rooms* {})
    (reset! sync/conns* {})
    (run! #(.delete ^java.io.File %) (reverse (file-seq (io/file dir))))))
