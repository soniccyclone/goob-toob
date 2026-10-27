(ns goob-toob.repos.storage
  "Filesystem repo: everything lives under STORAGE_DIR. uploads/<id>.<ext>
  for originals, dash/<id>/ for the DASH tree. All paths are checked to stay
  inside the storage root."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [goob-toob.config :as config])
  (:import [java.nio.file Path Files StandardCopyOption CopyOption]
           [java.time LocalDate]))

(defn root [] (io/file (config/storage-dir)))

(defn- inside-root? [^java.io.File f]
  (.startsWith (.toPath (.getCanonicalFile f))
               (.toPath (.getCanonicalFile (root)))))

(defn uploads-dir [] (doto (io/file (root) "uploads") (.mkdirs)))

(defn dash-dir [video-id]
  (doto (io/file (root) "dash" video-id) (.mkdirs)))

(defn dash-file
  "Safe lookup of a file inside a video's DASH dir (blocks ../ and absolute
  path tricks)."
  [video-id ^String rel]
  (when-not (str/starts-with? rel "/")
    (let [dir  (.getCanonicalFile (dash-dir video-id))
          f    (.getCanonicalFile (io/file dir rel))]
      (when (and (.startsWith (.toPath f) (.toPath dir)) (.exists f) (.isFile f))
        f))))

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
    (Files/move (.toPath tmp) (.toPath dest)
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
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

;; ---- telemetry (#57) ---------------------------------------------------------
;; telemetry/<yyyy-mm-dd>/<room-id>/<session>-<nick>.jsonl, one JSON line per
;; chunk the watch page uploads. Every path part is made here from the room
;; row and sanitised fields; a client never names a path. The day is the
;; server's at receipt, so a party across midnight spans two day dirs.

(def ^:private day-re  #"\d{4}-\d{2}-\d{2}")
(def ^:private room-re #"[A-Za-z0-9_-]{1,64}")
(def ^:private file-re #"[A-Za-z0-9_-]{1,64}\.jsonl")

(defonce ^:private telemetry-lock (Object.))

(defn telemetry-dir [] (io/file (root) "telemetry"))

(defn- safe-part
  "s with anything outside [A-Za-z0-9_-] turned into a dash, dashes trimmed
  at both ends, at most n chars; default when nothing is left."
  [s n default]
  (let [s (-> (str s) (str/replace #"[^A-Za-z0-9_-]+" "-") (str/replace #"^-+|-+$" ""))
        s (subs s 0 (min n (count s)))]
    (if (str/blank? s) default s)))

(defn append-telemetry!
  "Appends line to the session's file for today; {:file f :new? true} the
  first time a file is written."
  [room-id session nick ^String line]
  (let [dir  (doto (io/file (telemetry-dir) (str (LocalDate/now)) (safe-part room-id 64 "room"))
               (.mkdirs))
        f    (io/file dir (str (safe-part session 16 "s") "-" (safe-part nick 24 "anon") ".jsonl"))
        new? (not (.exists f))]
    (locking telemetry-lock
      (spit f (str line "\n") :append true))
    {:file f :new? new?}))

(defn- subdirs [^java.io.File d]
  (sort-by #(.getName ^java.io.File %) (filter #(.isDirectory ^java.io.File %) (or (.listFiles d) []))))

(defn- chunk-files [^java.io.File d]
  (sort-by #(.getName ^java.io.File %)
           (filter #(and (.isFile ^java.io.File %) (re-matches file-re (.getName ^java.io.File %)))
                   (or (.listFiles d) []))))

(defn list-telemetry
  "Days newest first, each with its rooms and their files:
  [{:day \"2026-10-27\" :rooms [{:id room-id :files [File ...]}]}]."
  []
  (vec (for [^java.io.File day (reverse (subdirs (telemetry-dir)))
             :when (re-matches day-re (.getName day))]
         {:day (.getName day)
          :rooms (vec (for [^java.io.File room (subdirs day)]
                        {:id (.getName room) :files (vec (chunk-files room))}))})))

(defn telemetry-files
  "The files of one room on one day, or nil for a name that is not ours
  or a dir that is not there."
  [day room]
  (when (and (re-matches day-re (str day)) (re-matches room-re (str room)))
    (let [d (io/file (telemetry-dir) day room)]
      (when (and (inside-root? d) (.isDirectory d))
        (vec (chunk-files d))))))

(defn telemetry-file
  "One chunk file by its three names, or nil (bad names, outside the root,
  missing)."
  [day room name]
  (when (and (telemetry-files day room) (re-matches file-re (str name)))
    (let [f (io/file (telemetry-dir) day room name)]
      (when (and (inside-root? f) (.isFile f)) f))))

(defn sweep-telemetry!
  "Deletes the day dirs older than keep-days, by the date in their name;
  the names of the days removed."
  [keep-days]
  (let [cutoff (.minusDays (LocalDate/now) (long keep-days))]
    (vec (for [^java.io.File day (subdirs (telemetry-dir))
               :let [d (try (LocalDate/parse (.getName day)) (catch Exception _ nil))]
               :when (and d (.isBefore d cutoff) (inside-root? day))]
           (do (run! #(.delete ^java.io.File %) (reverse (file-seq day)))
               (.getName day))))))
