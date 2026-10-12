(ns goob-toob.config
  "Env-driven config. STORAGE_DIR and ADMIN_PASSWORD are required; the app
  refuses to start without them so nobody runs it naked by accident."
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
    (when (str/blank? pass)
      (throw (ex-info "ADMIN_PASSWORD is required" {})))
    (let [cfg {:storage-dir  storage
               :db-path      (or (System/getenv "DB_PATH")
                                 (str storage "/goob-toob.db"))
               :admin-pass   pass
               :port         (env-int "PORT" 8080)
               :workers      (env-int "TRANSCODE_WORKERS" 1)
               :keep-original (env-bool "KEEP_ORIGINAL" true)
               :ladder       default-ladder}]
      (doseq [d ["" "uploads" "dash"]]
        (io/make-parents (io/file storage d "x")))
      (reset! config cfg))))

(defn cfg []
  (or @config (load!)))

(defn storage-dir [] (:storage-dir (cfg)))
