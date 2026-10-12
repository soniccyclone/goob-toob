(ns goob-toob.repos.storage
  "Filesystem repo: everything lives under STORAGE_DIR. uploads/<id>.<ext>
  for originals, dash/<id>/ for the DASH tree. All paths are checked to stay
  inside the storage root."
  (:require [clojure.java.io :as io]
            [goob-toob.config :as config])
  (:import [java.nio.file Path Files StandardCopyOption]))

(defn root [] (io/file (config/storage-dir)))

(defn- inside-root? [^java.io.File f]
  (.startsWith (.toPath (.getCanonicalFile f))
               (.toPath (.getCanonicalFile (root)))))

(defn uploads-dir [] (doto (io/file (root) "uploads") (.mkdirs)))

(defn dash-dir [video-id]
  (doto (io/file (root) "dash" video-id) (.mkdirs)))

(defn dash-file
  "Safe lookup of a file inside a video's DASH dir (blocks ../ tricks)."
  [video-id ^String rel]
  (let [dir  (.getCanonicalFile (dash-dir video-id))
        f    (.getCanonicalFile (io/file dir rel))]
    (when (and (.startsWith (.toPath f) (.toPath dir)) (.exists f) (.isFile f))
      f)))

(defn original-file [video-id ext]
  (let [f (io/file (uploads-dir) (str video-id (when (seq ext) (str "." ext))))]
    (when (inside-root? f) f)))

(defn find-original [video-id]
  (first (remove nil? (map (fn [^java.io.File f]
                             (when (.startsWith (.getName f) (str video-id ".")) f))
                           (seq (.listFiles (uploads-dir)))))))

(defn free-space-mb []
  (quot (.getUsableSpace (.getCanonicalFile (root))) (* 1024 1024)))

(defn save-upload! [^java.io.File tmp video-id ^String ext]
  (let [dest (original-file video-id ext)]
    (when-not dest (throw (ex-info "path escapes storage root" {:video-id video-id})))
    (Files/move (.toPath tmp) (.toPath dest) StandardCopyOption/REPLACE_EXISTING)
    dest))

(defn delete-original! [video-id]
  (when-let [orig (find-original video-id)]
    (.delete ^java.io.File orig)))

(defn delete-video-files! [video-id]
  (let [dash (io/file (root) "dash" video-id)]
    (when (inside-root? dash)
      (run! #(.delete ^java.io.File %) (reverse (file-seq dash))))
    (when-let [orig (find-original video-id)]
      (.delete ^java.io.File orig))))
