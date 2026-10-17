(ns goob-toob.ws
  "WebSocket endpoint for a watch room. Viewers authenticate with the room
  token in the URL; an admin session cookie makes that connection the host
  (playback controller). Protocol is JSON messages; see services/sync.

  ring-websocket-async channel directions (from the listener source):
  `in` receives frames from the client, `out` is what we send to the client;
  closing `out` closes the socket."
  (:require [ring.websocket.async :as wa]
            [clojure.core.async :as a]
            [cheshire.core :as json]
            [clojure.string :as str]
            [goob-toob.config :as config]
            [goob-toob.services.sync :as sync]
            [goob-toob.sync-core :as sc]
            [goob-toob.repos.room :as rooms]
            [goob-toob.repos.video :as videos]))

(defn- clamp-text [s n] (when (string? s) (apply str (take n s))))

(defn- control-allowed?
  ;; re-read per cmd so an admin flipping the room checkbox applies live
  [room-id]
  (= 1 (:allow-control (rooms/get-by-id room-id))))

(defn- handle-msg [{:keys [room-id cid admin? send!]} m]
  (case (:type m)
    "ping"   (send! {:type "pong" :t (:t m) :st (sync/mono-ms)})
    "cmd"    (when (or admin? (control-allowed? room-id))
               (sync/host-cmd! room-id (:op m) (:pos m)))
    "chat"   (let [text (clamp-text (:text m) 500)]
               (when-not (str/blank? text)
                 (sync/broadcast room-id {:type "chat" :from (:nick m) :text text
                                          :ts (System/currentTimeMillis)})))
    "status" (sync/status! room-id cid (:pos m) (:buffering m))
    nil))

(defn endpoint [req]
  (let [room-row (rooms/get-by-token (get-in req [:params :token]))]
    (if-not room-row
      {:status 404 :headers {"Content-Type" "text/plain"} :body "no such room"}
      (let [cid     (str (java.util.UUID/randomUUID))
            nick    (or (some-> (get-in req [:params :nick])
                                str/trim not-empty (#(apply str (take 32 %))))
                        "guest")
            admin?  (boolean (or (:goob/admin req) (get-in req [:session :admin])))
            room-id (:id room-row)
            video   (videos/get-by-id (:video-id room-row))]
        (wa/go-websocket [in out]
          (let [send! #(a/put! out (json/generate-string %))]
            (swap! sync/conns* assoc cid {:send #(a/put! out %) ; takes a json string
                                          :room-id room-id :host? admin? :nick nick})
            (sync/join! room-id cid nick)
            (let [room (sync/room-for room-id)]
              (send! {:type "welcome" :you cid :is-host admin?
                      :can-control (or admin? (= 1 (:allow-control room-row)))
                      :video {:id (:id video) :title (:title video)
                              :duration (:duration video)
                              :dash (str "/dash/" (:id video) "/manifest.mpd")}
                      :start-kbps (sc/start-kbps (:ladder (config/cfg)))
                      :state (sync/state-msg room)
                      :peers (sync/peers-list room)}))
            (sync/broadcast-peers room-id)
            (loop []
              (when-let [msg (a/<! in)]
                (when (string? msg)
                  (when-let [m (try (json/parse-string msg true)
                                    (catch Exception _ nil))]
                    (handle-msg {:room-id room-id :cid cid :admin? admin? :send! send!}
                                (assoc m :nick nick))))
                (recur)))
            ;; reached when the client closes (gracefully or not)
            (swap! sync/conns* dissoc cid)
            (sync/leave! room-id cid)
            (sync/broadcast-peers room-id)))))))
