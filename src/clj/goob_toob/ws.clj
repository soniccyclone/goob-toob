(ns goob-toob.ws
  "WebSocket endpoint for a watch room. Viewers authenticate with the room
  token in the URL; an admin session cookie makes that connection the host
  (playback controller). Protocol is JSON messages; see services/sync."
  (:require [ring.websocket :as ws]
            [clojure.core.async :as a]
            [cheshire.core :as json]
            [clojure.string :as str]
            [goob-toob.services.sync :as sync]
            [goob-toob.repos.room :as rooms]
            [goob-toob.repos.video :as videos]))

(defn- clamp-text [s n] (when (string? s) (str/join (take n s))))

(defn- handle-msg [{:keys [room-id cid admin? send!]} m]
  (case (:type m)
    "ping"  (send! {:type "pong" :t (:t m) :st (sync/mono-ms)})
    "cmd"   (when admin? (sync/host-cmd! room-id (:op m) (:pos m)))
    "chat"  (let [text (clamp-text (:text m) 500)]
              (when-not (str/blank? text)
                (sync/broadcast room-id {:type "chat" :from (:nick m) :text text
                                         :ts (System/currentTimeMillis)})))
    "status" (sync/status! room-id cid (:pos m) (:buffering m))
    nil))

(def endpoint
  (ws/wrap-handler
    (fn [{:keys [params query-params session
                 ws/send-msg-fn ws/receive-msg-ch]}]
      (let [room-row (rooms/get-by-token (:token params))]
        (if-not room-row
          (do (send-msg-fn (json/generate-string {:type "error" :message "no such room"}))
              (ws/close send-msg-fn))
          (let [cid     (str (java.util.UUID/randomUUID))
                nick    (or (some-> (:nick query-params) str/trim not-empty
                                     (#(apply str (take 32 %)))) "guest")
                admin?  (boolean (:admin session))
                room-id (:id room-row)
                video   (videos/get-by-id (:video_id room-row))
                send!   #(send-msg-fn (json/generate-string %))]
            (swap! sync/conns* assoc cid {:send send-msg-fn :room-id room-id
                                          :host? admin? :nick nick})
            (sync/join! room-id cid nick)
            (let [room (sync/room-for room-id)]
              (send! {:type "welcome" :you cid :is-host admin?
                      :video {:id (:id video) :title (:title video)
                              :duration (:duration video)
                              :dash (str "/dash/" (:id video) "/manifest.mpd")}
                      :state (sync/state-msg room)
                      :peers (sync/peers-list room)}))
            (sync/broadcast-peers room-id)
            (a/go-loop []
              (when-let [msg (a/<! receive-msg-ch)]
                (if (= :open (:type msg))
                  (do (when-let [m (try (json/parse-string (:body msg) true)
                                        (catch Exception _ nil))]
                        (handle-msg {:room-id room-id :cid cid :admin? admin? :nick nick
                                     :send! send!}
                                    (assoc m :nick nick)))
                      (recur))
                  (do (swap! sync/conns* dissoc cid)
                      (sync/leave! room-id cid)
                      (sync/broadcast-peers room-id)))))))))))
