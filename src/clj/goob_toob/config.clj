(ns goob-toob.config
  "Env-driven config. STORAGE_DIR is always required. ADMIN_PASSWORD selects
  the mode: set = container mode (single port, login-gated admin); unset =
  desktop mode (public port without admin routes + loopback admin port that
  is always admin). The app refuses to start without STORAGE_DIR either way."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(defonce config (atom nil))

(def default-ladder
  "Height -> video bitrate (kbit/s) for the DASH ladder."
  [{:h 1080 :v 6000} {:h 720 :v 3000} {:h 480 :v 1200} {:h 360 :v 600}])

(defn- env-int [k default]
  (if-let [s (System/getenv k)]
    (Long/parseLong s)
    default))

(defn- env-bool [k default]
  (if-let [s (System/getenv k)]
    (#{"1" "true" "yes"} (str/lower-case s))
    default))

(defn load! []
  (let [storage (System/getenv "STORAGE_DIR")
        pass    (System/getenv "ADMIN_PASSWORD")]
    (when (str/blank? storage)
      (throw (ex-info "STORAGE_DIR is required: point it at your video storage location" {})))
    (let [desktop? (str/blank? pass)
          cfg {:storage-dir  storage
               :db-path      (or (System/getenv "DB_PATH")
                                 (str storage "/goob-toob.db"))
               :mode         (if desktop? :desktop :container)
               :admin-pass   pass
               :port         (env-int "PORT" 8080)
               :admin-port   (env-int "GOOB_ADMIN_PORT" 8081)
               :ffmpeg-path  (or (System/getenv "FFMPEG_PATH") "ffmpeg")
               :workers      (env-int "TRANSCODE_WORKERS" 1)
               :keep-original (env-bool "KEEP_ORIGINAL" true)
               :ladder       default-ladder}]
      (doseq [d ["" "uploads" "dash"]]
        (io/make-parents (io/file storage d "x")))
      (reset! config cfg))))

(defn cfg []
  (or @config (load!)))

(defn storage-dir [] (:storage-dir (cfg)))
