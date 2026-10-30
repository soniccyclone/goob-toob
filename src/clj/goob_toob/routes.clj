(ns goob-toob.routes
  "HTTP API + DASH serving + static SPA shell."
  (:require [reitit.ring :as ring]
            [ring.middleware.json :refer [wrap-json-params wrap-json-response]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.response :refer [response file-response content-type header status]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [goob-toob.config :as config]
            [goob-toob.log :as log]
            [goob-toob.repos.video :as videos]
            [goob-toob.repos.room :as rooms]
            [goob-toob.repos.storage :as storage]
            [goob-toob.repos.settings :as settings]
            [goob-toob.db :as db]
            [goob-toob.services.sync :as sync]
            [goob-toob.services.transcode :as transcode]
            [goob-toob.session-store :as session-store]
            [goob-toob.telemetry :as telemetry]
            [goob-toob.ws :as ws])
  (:import [java.io RandomAccessFile]
           [java.security MessageDigest]))

;; ---- auth -----------------------------------------------------------------

(defonce login-attempts (atom {})) ; ip -> [epoch-ms ...]

(defn- allow-login? [ip]
  (let [now (System/currentTimeMillis)
        recent (filterv #(< (- now %) 60000) (get @login-attempts ip []))
        ok? (< (count recent) 5)]
    (swap! login-attempts assoc ip (if ok? (conj recent now) recent))
    ok?))

(defn- pw-equal? [^String a ^String b]
  (MessageDigest/isEqual (.getBytes a) (.getBytes b)))

(defn login [{:keys [params remote-addr session]}]
  (cond
    (not (allow-login? remote-addr))
    (-> (response {:error "too many login attempts, wait a minute"}) (status 429))
    (pw-equal? (str (:password params "")) (:admin-pass (config/cfg)))
    (-> (response {:ok true}) (assoc :session (assoc session :admin true)))
    :else
    (-> (response {:error "wrong password"}) (status 401))))

(defn logout [{:keys [session]}]
  (-> (response {:ok true}) (assoc :session (dissoc session :admin))))

(defn whoami [req]
  (response {:admin (boolean (or (:goob/admin req) (get-in req [:session :admin])))}))

(defn require-admin [handler]
  (fn [req]
    (if (or (:goob/admin req) (get-in req [:session :admin]))
      (handler req)
      (-> (response {:error "admin only"}) (status 401)))))

(defn wrap-desktop-admin
  "Wraps the admin-port handler (desktop mode). The port binds 127.0.0.1 and
  is never tunneled, so every request on it is the machine's owner."
  [handler]
  (fn [req] (handler (assoc req :goob/admin true))))

;; ---- videos / rooms --------------------------------------------------------

(defn- public-video [v]
  (select-keys v [:id :title :duration :status :progress]))

(defn public-videos [_] (response (map public-video (videos/list-ready))))
(defn admin-videos [_] (response (map #(update % :error (fn [e] (some->> e (take 800) str/join)))
                                       (videos/list-all))))

(defn public-rooms
  "Rooms whose current video is ready, plus empty ones (:now nil, :items 0):
  the lobby lists those as \"nothing queued yet\" rather than hiding a room
  the host has open."
  [_]
  (response (for [r (rooms/list-all)
                  :when (or (= "ready" (:status r)) (nil? (:video-id r)))]
              {:token (:token r) :title (:title r) :now (:video-title r) :items (:items r)})))

(defn admin-rooms [_]
  (response (map #(assoc % :queue (rooms/queue (:id %))) (rooms/list-all))))

(defn create-room [{:keys [params]}]
  (let [r (rooms/create! (:video-id params) :title (:title params))]
    (-> (response {:token (:token r) :url (str "/watch/" (:token r))}) (status 201))))

(defn delete-room [{:keys [params]}]
  (rooms/delete! (:id params))
  (response {:ok true}))

(defn- room-or-404
  "The room row, or a :not-found that wrap-conflicts turns into a 404 (an
  unknown room would otherwise surface as a raw SQLite foreign-key error
  from the item repos)."
  [id]
  (or (rooms/get-by-id id)
      (throw (ex-info "no such room" {:type :not-found :room-id id}))))

(defn update-room
  "Each key optional: :allow-control flips co-host control, :title renames
  (blank clears it)."
  [{:keys [params]}]
  (let [id (:id (room-or-404 (:id params)))]
    (when (contains? params :allow-control)
      (rooms/set-control! id (boolean (:allow-control params))))
    (when (contains? params :title)
      (rooms/set-title! id (:title params)))
    (response {:ok true})))

(defn- queue-response [room-id code]
  (-> (response {:queue (rooms/queue room-id)
                 :video-id (:video-id (rooms/get-by-id room-id))})
      (status code)))

(defn add-item
  "Appends; in an empty room the new item becomes current, paused."
  [{:keys [params]}]
  (let [id (:id (room-or-404 (:id params)))]
    (sync/add-item! id (:video-id params))
    (queue-response id 201)))

(defn remove-item
  "Removing the current item skips the room; removing the only one empties
  it (:video-id nil in the response)."
  [{:keys [params]}]
  (let [id (:id (room-or-404 (:id params)))]
    (sync/remove-item! id (:video-id params))
    (queue-response id 200)))

(defn reorder-items [{:keys [params]}]
  (let [id (:id (room-or-404 (:id params)))]
    (rooms/reorder! id (vec (:order params)))
    (sync/queue-changed! id)
    (queue-response id 200)))

(defn room-live [{:keys [params]}]
  (if-let [view (sync/live-view (:id params))]
    (response view)
    (-> (response {:error "no such room"}) (status 404))))

(defn delete-video
  "409 (from wrap-conflicts) while any room has the video queued."
  [{:keys [params]}]
  (let [id (:id params)]
    (when-let [v (videos/get-by-id id)]
      (videos/delete! id)
      (storage/delete-video-files! id))
    (response {:ok true})))

(defn wrap-conflicts
  "The repos refuse with ex-info: :conflict -> 409, :bad-request -> 400,
  :not-found -> 404, each as {:error message}. Anything else propagates."
  [handler]
  (fn [req]
    (try (handler req)
         (catch clojure.lang.ExceptionInfo e
           (if-let [code ({:conflict 409 :bad-request 400 :not-found 404}
                          (:type (ex-data e)))]
             (-> (response {:error (ex-message e)}) (status code))
             (throw e))))))

(defn upload [{:keys [params]}]
  (let [{:keys [tempfile filename size]} (:file params)]
    (if-not (and tempfile (.exists (io/file tempfile)))
      (-> (response {:error "multipart 'file' field required"}) (status 400))
      (let [ext   (some-> filename (str/split #"\.") last str/lower-case
                          (#(when (re-matches #"[a-z0-9]{1,5}" %) %)))
            id    (str (java.util.UUID/randomUUID))
            title (or (some-> filename (str/replace #"\.[^.]*$" "") (not-empty)) id)
            dest  (storage/save-upload! (io/file tempfile) id ext)]
        (videos/insert! {:id id :title title :original-path (.getPath dest)})
        (log/info (format "upload accepted: %s (%.1f MB) -> %s" filename (/ (or size 0) 1048576.0) id))
        (transcode/submit! id)
        (-> (response {:id id :status "queued" :free-mb (storage/free-space-mb)}) (status 202))))))

(defn storage-info [_]
  (let [cfg (config/cfg)]
    (response {:storage-dir (:storage-dir cfg)
               :free-mb (storage/free-space-mb)
               :workers (:workers cfg)
               :encoder (transcode/encoder-info)})))

;; ---- client playback diagnostics ---------------------------------------------
;; The watch page POSTs dash.js/video faults and stall reports here so that
;; server.log holds what the browser actually saw — the user sends the log
;; file, no devtools required. Rate-limited per room token (public endpoint).

(def clientlog-hits (atom {})) ;; token -> [count window-start-ms]

(defn- clip [s n] (subs s 0 (min n (count s))))

(defn- allow?
  "At most limit hits per key per minute, counted in hits (key -> [count
  window-start-ms]); the window restarts a minute after its first hit."
  [hits k now limit]
  (let [[n _] (get (swap! hits
                          (fn [m]
                            (let [[n t] (get m k [0 now])
                                  fresh (>= (- now t) 60000)]
                              (assoc m k [(if fresh 1 (inc n)) (if fresh now t)]))))
                   k)]
    (<= n limit)))

(defn- clientlog-allow? [tok now] (allow? clientlog-hits tok now 120))

(defn clientlog [{:keys [params]}]
  (let [{:keys [token msg]} params
        tok (clip (str token) 64)]
    (when (and msg (clientlog-allow? tok (System/currentTimeMillis)))
      (log/info "client" (clip tok 8) ":" (clip (str msg) 400)))
    (response {:ok true})))

;; ---- telemetry (#57) -----------------------------------------------------------
;; Every started viewer uploads one chunk of 1 Hz samples per 5 min (the
;; telemetry block in watch.cljs has the budget); each lands as one line in
;; <storage>/telemetry/<day>/<room-id>/<session>-<nick>.jsonl, named from
;; the room row and sanitised fields, never from a client path. A chunk
;; over 64 KB is 413; more than two a minute per viewer (token + session:
;; the 5 min chunk and a pagehide beacon that may follow it inside the
;; minute; one room's viewers do not hold each other back) is 429. The
;; client drops either and carries on. One log line per new file, none per
;; chunk. goob-toob.telemetry reads the files back.

(def telemetry-hits (atom {})) ;; token/session -> [count window-start-ms]
(def ^:private telemetry-max-bytes 65536)

(defn- telemetry-allow? [k now] (allow? telemetry-hits k now 2))

(defn- wrap-body-limit
  "413 for a request body over max-bytes, read before any JSON parsing."
  [handler max-bytes]
  (fn [req]
    (if-let [^java.io.InputStream in (:body req)]
      (let [bytes (.readNBytes in (inc (int max-bytes)))]
        (if (> (alength bytes) max-bytes)
          (-> (response {:error "chunk too big"}) (status 413))
          (handler (assoc req :body (java.io.ByteArrayInputStream. bytes)))))
      (handler req))))

(defn admin-settings [_]
  (response (settings/all)))

(defn update-settings
  "Each known key optional; :telemetry true/false switches the playback
  recording globally (the welcome tells every viewer, the route refuses)."
  [{:keys [params]}]
  (when-let [unknown (seq (remove (set (keys settings/defaults)) (keys params)))]
    (throw (ex-info (str "unknown setting " (name (first unknown))) {:type :bad-request})))
  (doseq [[k v] params]
    (settings/set! k v))
  (response (settings/all)))

(defn probe-encoder
  "Re-runs the GPU probe (a GPU that woke up, a driver installed) and
  answers with what the admin console shows."
  [_]
  (transcode/probe-engines!)
  (response (transcode/encoder-info)))

(defn telemetry [{:keys [params]}]
  (let [{:keys [token session nick samples]} params]
    (if-not (settings/telemetry?)
      (-> (response {:error "telemetry is off"}) (status 403))
    (if-not (and (string? token) (string? session) (vector? samples) (seq samples))
      (-> (response {:error "token, session and samples required"}) (status 400))
      (if-let [room (rooms/get-by-token token)]
        (if (telemetry-allow? (str (clip token 64) "/" (clip session 32)) (System/currentTimeMillis))
          (let [line (json/generate-string (-> params
                                               (dissoc :token)
                                               (assoc :room (:id room)
                                                      :received (System/currentTimeMillis))))
                {:keys [file new?]} (storage/append-telemetry! (:id room) session nick line)]
            (when new? (log/info "telemetry: new file" (.getPath ^java.io.File file)))
            (response {:ok true}))
          (-> (response {:error "two chunks a minute per viewer"}) (status 429)))
        (-> (response {:error "no such room"}) (status 404)))))))

(defn admin-telemetry
  "Days newest first, their rooms (with the title while the room exists)
  and each file's size, chunks, samples, first and last sample time."
  [_]
  (response (for [{:keys [day rooms]} (storage/list-telemetry)]
              {:day day
               :rooms (for [{:keys [id files]} rooms]
                        {:id id
                         :title (:title (rooms/get-by-id id))
                         :files (map telemetry/file-info files)})})))

(defn admin-telemetry-file
  "GET .../<day>/<room>/<file>.jsonl downloads the file; .../analysis runs
  goob-toob.telemetry over the room's files for that day."
  [{:keys [params]}]
  (let [{:keys [day room file]} params]
    (if (= "analysis" file)
      (if-let [files (seq (storage/telemetry-files day room))]
        (response (telemetry/analyse (mapcat telemetry/read-file files)))
        (-> (response {:error "no telemetry there"}) (status 404)))
      (if-let [f (storage/telemetry-file day room file)]
        (-> (response (io/input-stream f))
            (content-type "application/x-ndjson")
            (header "Content-Length" (str (.length ^java.io.File f)))
            (header "Content-Disposition" (str "attachment; filename=\"" file "\"")))
        (-> (response {:error "no such file"}) (status 404))))))

;; ---- DASH serving (with Range support) -------------------------------------

(defn- content-type-for [^String name]
  (cond
    (str/ends-with? name ".mpd") "application/dash+xml"
    (str/ends-with? name ".m4s") "video/mp4"
    (str/ends-with? name ".init") "video/mp4"
    (str/ends-with? name ".m4a") "audio/mp4"
    :else "application/octet-stream"))

(defn- parse-range [^String hdr len]
  (when-let [[_ a b] (re-find #"^bytes=(\d*)-(\d*)$" hdr)]
    (if (str/blank? a)
      ;; suffix form: last N bytes
      (when-not (str/blank? b)
        (let [n (Long/parseLong b)]
          (when (pos? n) [(max 0 (- len n)) (dec len)])))
      (let [start (Long/parseLong a)
            end   (if (str/blank? b) (dec len) (min (dec len) (Long/parseLong b)))]
        (when (<= start end) [start end])))))

(defn- limited-stream [^java.io.File f start end]
  (let [len (inc (- end start))
        buf (byte-array len)]
    (with-open [raf (doto (RandomAccessFile. f "r") (.seek start))]
      (.readFully raf buf))
    (java.io.ByteArrayInputStream. buf)))

(defn- serve-file [^java.io.File f req]
  (let [len (.length f)
        ctype (content-type-for (.getName f))]
    (if-let [[start end] (some-> (get-in req [:headers "range"]) (parse-range len))]
      (-> (response (limited-stream f start end))
          (content-type ctype)
          (header "Content-Length" (str (inc (- end start))))
          (header "Content-Range" (str "bytes " start "-" end "/" len))
          (header "Accept-Ranges" "bytes")
          (status 206))
      (-> (response (io/input-stream f))
          (content-type ctype)
          (header "Content-Length" (str len))
          (header "Accept-Ranges" "bytes")))))

(defn dash-file [{:keys [params headers] :as req}]
  (let [vid (:video-id params)
        rel (or (:path params) "manifest.mpd")]
    (if-let [f (storage/dash-file vid rel)]
      (serve-file f req)
      (-> (response "not found") (status 404)))))

;; ---- SPA shell --------------------------------------------------------------

(defn spa [_]
  (-> (slurp (io/resource "public/index.html"))
      response
      (content-type "text/html; charset=utf-8")))

;; ---- router ------------------------------------------------------------------

;; reitit 0.7 puts path params only under :path-params; merge them into
;; :params so handlers can read them uniformly (query params win on clash).
(defn- wrap-path-params [handler]
  (fn [req]
    (handler (if-let [pp (:path-params req)]
               (update req :params merge pp)
               req))))

;; One session store per app: reitit compiles the middleware chain per
;; endpoint, so wrap-session's default store would give every endpoint its
;; own isolated store (login writes to one, /api/me reads another). The
;; store itself is the sessions table (#71), so a restart keeps every login.
(defn- common [store]
  [wrap-path-params wrap-params wrap-keyword-params
   #(wrap-session % {:store store})])
(def json-body #(wrap-json-params % {:key-fn keyword}))

(defn app [{:keys [admin? login?]}]
  "admin? false = the public port: no login, no admin API, no admin SPA —
  whatever the tunnel can reach. admin? true = full app (container port or
  desktop loopback admin port). login? true = container mode only; the
  desktop admin port has no password to log in with."
  (let [api (cond-> ["/api" {:middleware [json-body wrap-json-response]}]
              true   (into [["/me" {:get whoami}]
                            ["/videos" {:get public-videos}]
                            ["/rooms" {:get public-rooms}]
                             ["/clientlog" {:post clientlog}]])
              login? (into [["/login" {:post login}]
                            ["/logout" {:post logout}]])
              admin? (into [["/admin" {:middleware [require-admin wrap-conflicts]}
                             ["/me" {:get whoami}]
                             ["/videos" {:get admin-videos}]
                             ["/videos/:id" {:delete delete-video}]
                             ["/upload" {:post upload
                                         :middleware [wrap-multipart-params wrap-keyword-params]}]
                             ["/info" {:get storage-info}]
                             ["/settings" {:get admin-settings :put update-settings}]
                             ["/encoder/probe" {:post probe-encoder}]
                             ["/rooms" {:get admin-rooms :post create-room}]
                             ["/rooms/:id" {:delete delete-room :patch update-room}]
                             ["/rooms/:id/items" {:post add-item :put reorder-items}]
                             ["/rooms/:id/items/:video-id" {:delete remove-item}]
                             ["/rooms/:id/live" {:get room-live}]
                             ["/telemetry" {:get admin-telemetry}]
                             ["/telemetry/:day/:room/:file" {:get admin-telemetry-file}]]]))
        ;; outside the /api group so the size cap runs before the JSON parse
        telemetry-route ["/api/telemetry" {:post telemetry
                                           :middleware [wrap-json-response
                                                        #(wrap-body-limit % telemetry-max-bytes)
                                                        json-body]}]
        router
        (ring/router
         (cond-> ["" {:middleware (common (session-store/sql-store db/db))}
                  api
                  telemetry-route
                  ["/dash/:video-id/*path" {:get dash-file}]
                  ["/ws/room/:token" {:get ws/endpoint}]
                  ["/" {:get spa}]
                  ["/watch/*" {:get spa}]]
           admin? (conj ["/admin" {:get spa}])))
        not-found (ring/routes
                   (wrap-resource (constantly nil) "public")
                   (constantly (-> (response "not found") (status 404))))]
    (ring/ring-handler router not-found)))
