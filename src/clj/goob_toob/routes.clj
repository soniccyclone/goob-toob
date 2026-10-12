(ns goob-toob.routes
  "HTTP API + DASH serving + static SPA shell."
  (:require [reitit.ring :as ring]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.session :refer [wrap-session]]
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
    (pw-equal? (:password params) (:admin-pass (config/cfg)))
    (-> (response {:ok true}) (assoc :session (assoc session :admin true)))
    :else
    (-> (response {:error "wrong password"}) (status 401))))

(defn logout [{:keys [session]}]
  (-> (response {:ok true}) (assoc :session (dissoc session :admin))))

(defn whoami [{:keys [session]}]
  (response {:admin (boolean (:admin session))}))

(defn require-admin [handler]
  (fn [req]
    (if (get-in req [:session :admin])
      (handler req)
      (-> (response {:error "admin only"}) (status 401)))))

;; ---- videos / rooms --------------------------------------------------------

(defn- public-video [v]
  (select-keys v [:id :title :duration :status :progress]))

(defn public-videos [_] (response (map public-video (videos/list-ready))))
(defn admin-videos [_] (response (map #(update % :error #(some-> % (str/join (take 800))))
                                       (videos/list-all))))

(defn public-rooms [_]
  (response (for [r (rooms/list-all) :when (= "ready" (:status r))]
              {:token (:token r) :title (:title r)})))

(defn create-room [{:keys [params]}]
  (try
    (let [r (rooms/create! (:video-id params))]
      (response {:token (:token r) :url (str "/watch/" (:token r))} 201))
    (catch Exception e
      (-> (response {:error (.getMessage e)}) (status 400)))))

(defn delete-room [{:keys [params]}]
  (rooms/delete! (:id params))
  (response {:ok true}))

(defn delete-video [{:keys [params]}]
  (let [id (:id params)]
    (when-let [v (videos/get-by-id id)]
      (videos/delete! id)
      (storage/delete-video-files! id))
    (response {:ok true}))

(defn upload [{:keys [params]}]
  (let [{:keys [tempfile filename size]} (:file params)]
    (if-not (and tempfile (.exists (io/file tempfile)))
      (-> (response {:error "multipart 'file' field required"}) (status 400))
      (let [ext   (some-> filename (str/split #"\.") last str/lower-case
                          #(when (re-matches #"[a-z0-9]{1,5}" %) %))
            id    (str (java.util.UUID/randomUUID))
            title (or (some-> filename (str/replace #"\.[^.]*$" "") (not-empty)) id)
            dest  (storage/save-upload! (io/file tempfile) id ext)]
        (videos/insert! {:id id :title title :original-path (.getPath dest)})
        (transcode/submit! id)
        (response {:id id :status "queued" :free-mb (storage/free-space-mb)} 202)))))

(defn storage-info [_]
  (let [cfg (config/cfg)]
    (response {:storage-dir (:storage-dir cfg)
               :free-mb (storage/free-space-mb)
               :workers (:workers cfg)})))

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
    (let [start (if (str/blank? a)
                  (max 0 (- len (Long/parseLong b)))
                  (Long/parseLong a))
          end   (if (str/blank? b) (dec len) (min (dec len) (Long/parseLong b)))]
      (when (<= start end) [start end]))))

(defn- limited-stream [^java.io.File f start end]
  (let [raf (doto (RandomAccessFile. f "r") (.seek start))
        remaining (long (- (inc end) start))]
    (proxy [java.io.InputStream] []
      (read []
        (if (pos? remaining)
          (let [b (.read raf)] (when (pos? b) (set! remaining (dec remaining))) b)
          -1))
      (read [^bytes bs off len]
        (if (zero? remaining)
          -1
          (let [n (.read raf bs off (int (min len remaining)))]
            (when (pos? n) (set! remaining (- remaining n)))
            n)))
      (close [] (.close raf)))))

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

(def common [wrap-keyword-params wrap-params wrap-session])
(def json-body (partial wrap-json-body {:keyword? true}))

(defn app []
  (let [router
        (ring/router
         ["" {:middlewares common}]
         ["/api" {:middlewares [json-body wrap-json-response]}
          ["/login" {:post login}]
          ["/logout" {:post logout}]
          ["/me" {:get whoami}]
          ["/videos" {:get public-videos}]
          ["/rooms" {:get public-rooms}]
          ["/admin" {:middlewares [require-admin]}
           ["/me" {:get whoami}]
           ["/videos" {:get admin-videos}]
           ["/videos/:id" {:delete delete-video}]
           ["/upload" {:post upload
                       :middlewares [wrap-multipart-params]}]
           ["/info" {:get storage-info}]
           ["/rooms" {:post create-room}]
           ["/rooms/:id" {:delete delete-room}]]]
         ["/dash/:video-id/manifest.mpd" {:get dash-file}]
         ["/dash/:video-id/*path" {:get dash-file}]
         ["/ws/room/:token" {:get ws/endpoint}]
         ["/" {:get spa}]
         ["/watch/*" {:get spa}]
         ["/admin" {:get spa}])]
        not-found (ring/routes
                   (wrap-resource (constantly nil) "public")
                   (constantly (-> (response "not found") (status 404))))]
    (ring/ring-handler router not-found)))
