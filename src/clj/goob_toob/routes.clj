(ns goob-toob.routes
  "HTTP API + DASH serving + static SPA shell."
  (:require [reitit.ring :as ring]
            [ring.middleware.json :refer [wrap-json-params wrap-json-response]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :refer [memory-store]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.response :refer [response file-response content-type header status]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [goob-toob.config :as config]
            [goob-toob.repos.video :as videos]
            [goob-toob.repos.room :as rooms]
            [goob-toob.repos.storage :as storage]
            [goob-toob.services.transcode :as transcode]
            [goob-toob.ws :as ws])
  (:import [java.io RandomAccessFile]
           [java.security MessageDigest]))

;; ---- auth -----------------------------------------------------------------

(defonce login-attempts (atom {})) ; ip -> [epoch-ms ...]

(defn- allow-login? [ip]
  (let [now (System/currentTimeMillis)
        recent (filter #(> (- now %) 60000) (get @login-attempts []))
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

(defn public-rooms [_]
  (response (for [r (rooms/list-all) :when (= "ready" (:status r))]
              {:token (:token r) :title (:title r)})))

(defn admin-rooms [_] (response (rooms/list-all)))

(defn create-room [{:keys [params]}]
  (try
    (let [r (rooms/create! (:video-id params))]
      (-> (response {:token (:token r) :url (str "/watch/" (:token r))}) (status 201)))
    (catch Exception e
      (-> (response {:error (.getMessage e)}) (status 400)))))

(defn delete-room [{:keys [params]}]
  (rooms/delete! (:id params))
  (response {:ok true}))

(defn update-room [{:keys [params]}]
  (rooms/set-control! (:id params) (boolean (:allow-control params)))
  (response {:ok true}))

(defn delete-video [{:keys [params]}]
  (let [id (:id params)]
    (when-let [v (videos/get-by-id id)]
      (videos/delete! id)
      (storage/delete-video-files! id))
    (response {:ok true})))

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
        (println (format "upload accepted: %s (%.1f MB) -> %s" filename (/ (or size 0) 1048576.0) id))
        (transcode/submit! id)
        (-> (response {:id id :status "queued" :free-mb (storage/free-space-mb)}) (status 202))))))

(defn storage-info [_]
  (let [cfg (config/cfg)]
    (response {:storage-dir (:storage-dir cfg)
               :free-mb (storage/free-space-mb)
               :workers (:workers cfg)})))

;; ---- client playback diagnostics ---------------------------------------------
;; The watch page POSTs dash.js/video faults and stall reports here so that
;; server.log holds what the browser actually saw — the user sends the log
;; file, no devtools required. Rate-limited per room token (public endpoint).

(def clientlog-hits (atom {})) ;; token -> [count window-start-ms]

(defn- clip [s n] (subs s 0 (min n (count s))))

(defn- clientlog-allow? [tok now]
  (let [[n _] (get (swap! clientlog-hits
                          (fn [m]
                            (let [[n t] (get m tok [0 now])
                                  fresh (>= (- now t) 60000)]
                              (assoc m tok [(if fresh 1 (inc n)) (if fresh now t)]))))
                   tok)]
    (<= n 120)))

(defn clientlog [{:keys [params]}]
  (let [{:keys [token msg]} params
        tok (clip (str token) 64)]
    (when (and msg (clientlog-allow? tok (System/currentTimeMillis)))
      (println "client" (clip tok 8) ":" (clip (str msg) 400)))
    (response {:ok true})))

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

;; One shared session store: reitit compiles the middleware chain per
;; endpoint, so wrap-session's default store would give every endpoint its
;; own isolated store (login writes to one, /api/me reads another).
(def session-store (memory-store))
(def common [wrap-path-params wrap-params wrap-keyword-params
             #(wrap-session % {:store session-store})])
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
              admin? (into [["/admin" {:middleware [require-admin]}
                             ["/me" {:get whoami}]
                             ["/videos" {:get admin-videos}]
                             ["/videos/:id" {:delete delete-video}]
                             ["/upload" {:post upload
                                         :middleware [wrap-multipart-params wrap-keyword-params]}]
                             ["/info" {:get storage-info}]
                             ["/rooms" {:get admin-rooms :post create-room}]
                             ["/rooms/:id" {:delete delete-room :patch update-room}]]]))
        router
        (ring/router
         (cond-> ["" {:middleware common}
                  api
                  ["/dash/:video-id/*path" {:get dash-file}]
                  ["/ws/room/:token" {:get ws/endpoint}]
                  ["/" {:get spa}]
                  ["/watch/*" {:get spa}]]
           admin? (conj ["/admin" {:get spa}])))
        not-found (ring/routes
                   (wrap-resource (constantly nil) "public")
                   (constantly (-> (response "not found") (status 404))))]
    (ring/ring-handler router not-found)))
