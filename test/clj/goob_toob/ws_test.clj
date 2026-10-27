(ns goob-toob.ws-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.core.async :as a]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ring.adapter.jetty :refer [run-jetty]]
            [goob-toob.repos.room :as rooms]
            [goob-toob.repos.video :as videos]
            [goob-toob.routes :as routes]
            [goob-toob.services.sync :as sync]
            [goob-toob.test-util :refer [with-tmp]])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers WebSocket WebSocket$Listener]
           [java.util.concurrent CompletionException]))

(defonce ^:private port (atom nil))
(defonce ^:private opened (atom []))

(defn- with-server [f]
  (let [server (run-jetty (routes/app {:admin? true :login? true}) {:port 0 :join? false})]
    (reset! port (.getPort (.getURI server)))
    (try (f) (finally (.stop server)))))

(defn- wait-until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

(defn- close-clients [f]
  (try (f)
       (finally
         (doseq [^WebSocket ws @opened]
           (try (.join (.sendClose ws WebSocket/NORMAL_CLOSURE "")) (catch Exception _ nil)))
         (reset! opened [])
         (wait-until #(empty? @sync/conns*) 2000))))

(use-fixtures :once with-server)
(use-fixtures :each with-tmp close-clients)

(defn- ws-client
  "Opens a socket; returns {:ws ws :in chan}. Every complete text frame is
  parsed and put on :in."
  [port token nick cookie]
  (let [in (a/chan 64)
        buf (StringBuilder.)
        listener (reify WebSocket$Listener
                   (onOpen [_ ws] (.request ws 1))
                   (onText [_ ws data last?]
                     (.append buf data)
                     (when last?
                       (a/put! in (json/parse-string (str buf) true))
                       (.setLength buf 0))
                     (.request ws 1)
                     nil))
        b (cond-> (.newWebSocketBuilder (HttpClient/newHttpClient))
            cookie (.header "Cookie" cookie))
        ws (.join (.buildAsync b (URI. (str "ws://127.0.0.1:" port "/ws/room/" token
                                            "?nick=" (URLEncoder/encode (str nick) "UTF-8")))
                               listener))]
    (swap! opened conj ws)
    {:ws ws :in in}))

(defn- send! [^WebSocket ws m] (.join (.sendText ws (json/generate-string m) true)))
(defn- recv! [in] (let [[v _] (a/alts!! [in (a/timeout 3000)])] v))
(defn- recv-type! [in t] (loop [] (when-let [m (recv! in)] (if (= t (:type m)) m (recur)))))

(defn- recv-all! [in ms]
  (let [deadline (a/timeout ms)]
    (loop [acc []]
      (let [[v _] (a/alts!! [in deadline])]
        (if v (recur (conj acc v)) acc)))))

(defn- login! []
  (let [resp (.send (HttpClient/newHttpClient)
                    (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" @port "/api/login")))
                        (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString
                                (json/generate-string {:password "pw"})))
                        .build)
                    (HttpResponse$BodyHandlers/ofString))]
    (some-> (.firstValue (.headers resp) "set-cookie") (.orElse nil) (str/split #";") first)))

(defn- room! []
  (videos/insert! {:id "v1" :title "Clip" :original-path "/tmp/v1.mp4"})
  (videos/set-duration! "v1" 42.0)
  (rooms/create! "v1"))

(defn- connect! [room nick & [cookie]]
  (let [c (ws-client @port (:token room) nick cookie)]
    (assoc c :welcome (recv-type! (:in c) "welcome"))))

(deftest unknown-token-is-refused
  (is (thrown? CompletionException (ws-client @port "nope" "x" nil))))

(deftest nick-is-trimmed-and-clamped
  (let [room (room!)]
    (is (= [(apply str (repeat 32 "a"))]
           (map :nick (:peers (:welcome (connect! room (str "  " (apply str (repeat 40 "a")) "  ")))))))
    (is (some #{"guest"} (map :nick (:peers (:welcome (connect! room ""))))))))

(deftest viewer-commands-are-ignored-without-co-host-control
  (let [room (room!)
        v1 (connect! room "v1")
        v2 (connect! room "v2")]
    (send! (:ws v1) {:type "cmd" :op "play" :pos 0})
    (is (not-any? #(= "state" (:type %)) (recv-all! (:in v2) 1000)))
    (is (false? (get-in @sync/rooms* [(:id room) :playing?])))))

(deftest chat-is-clamped-and-blank-dropped
  (let [room (room!)
        {:keys [ws in]} (connect! room "ann")]
    (send! ws {:type "chat" :text (apply str (repeat 600 "x"))})
    (let [m (recv-type! in "chat")]
      (is (= 500 (count (:text m))))
      (is (= "ann" (:from m)))
      (is (number? (:ts m))))
    (send! ws {:type "chat" :text "   "})
    (is (not-any? #(= "chat" (:type %)) (recv-all! in 500)))))

(deftest non-numeric-positions-are-dropped-at-the-socket
  (let [room (room!)
        host (connect! room "host" (login!))]
    (send! (:ws host) {:type "cmd" :op "pause" :pos 7.0})
    (recv-type! (:in host) "state")
    (send! (:ws host) {:type "cmd" :op "play" :pos "x"})
    (is (= {:op "play" :pos 7.0 :playing true}
           (select-keys (recv-type! (:in host) "state") [:op :pos :playing])))))

(deftest a-malformed-status-does-not-ghost-the-peer
  (let [room (room!)
        v1 (connect! room "v1")
        v2 (connect! room "v2")]
    (send! (:ws v1) {:type "status" :pos "x" :buffering false})
    (.join (.sendClose ^WebSocket (:ws v1) WebSocket/NORMAL_CLOSURE ""))
    (is (= ["v2"]
           (loop []
             (when-let [m (recv-type! (:in v2) "peers")]
               (if (= 1 (count (:peers m))) (map :nick (:peers m)) (recur))))))))

(deftest a-non-object-frame-does-not-kill-the-socket
  (let [{:keys [ws in]} (connect! (room!) "ann")]
    (.join (.sendText ^WebSocket ws "5" true))
    (.join (.sendText ^WebSocket ws "\"abc\"" true))
    (send! ws {:type "ping" :t 7})
    (is (= 7 (:t (recv-type! in "pong"))))))

;; ---- queues -----------------------------------------------------------------

(defn- http!
  "An admin JSON request to the running server; {:status :body}."
  [method path body]
  (let [req  (-> (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" @port path)))
                 (.header "Content-Type" "application/json")
                 (.header "Cookie" (login!))
                 (.method method (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                 .build)
        resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (json/parse-string (.body resp) true)}))

(defn- two-item-room! []
  (doseq [[vid title dur] [["v1" "Ep 1" 42.0] ["v2" "Ep 2" 50.0]]]
    (videos/insert! {:id vid :title title :original-path (str "/tmp/" vid ".mp4")})
    (videos/set-duration! vid dur))
  (let [room (rooms/create! "v1")]
    (rooms/add-item! (:id room) "v2")
    room))

(defn- recv-item! [in]
  (loop [] (when-let [m (recv-type! in "state")] (if (= "item" (:op m)) m (recur)))))

(deftest welcome-carries-the-queue-and-current-video
  (let [room (two-item-room!)
        w    (:welcome (connect! room "ann"))]
    (is (= [["v1" "Ep 1" 0] ["v2" "Ep 2" 1]] (map (juxt :video-id :title :position) (:queue w))))
    (is (= "v1" (:video-id w)))
    (is (= "v1" (get-in w [:state :video-id])))
    (is (= {:id "v1" :title "Ep 1" :duration 42.0 :dash "/dash/v1/manifest.mpd"} (:video w)))))

(deftest host-next-switches-every-viewer
  (let [room   (two-item-room!)
        host   (connect! room "host" (login!))
        viewer (connect! room "viewer")]
    (send! (:ws host) {:type "cmd" :op "next"})
    (let [m (recv-item! (:in viewer))]
      (is (= "v2" (get-in m [:video :id])))
      (is (= "v2" (:video-id m)))
      (is (true? (:playing m)))
      (is (= 0.0 (:pos m))))
    (let [w (:welcome (connect! room "late"))]
      (is (= "v2" (:video-id w)))
      (is (= "Ep 2" (get-in w [:video :title]))))))

(deftest viewer-next-is-ignored-without-control
  (let [room (two-item-room!)
        v1   (connect! room "v1")
        v2   (connect! room "v2")]
    (send! (:ws v1) {:type "cmd" :op "next"})
    (is (not-any? #(= "item" (:op %)) (recv-all! (:in v2) 1000)))
    (is (= "v1" (get-in @sync/rooms* [(:id room) :video-id])))))

(deftest queue-edit-reaches-the-room
  (videos/insert! {:id "v1" :title "Ep 1" :original-path "/tmp/v1.mp4"})
  (videos/insert! {:id "v2" :title "Ep 2" :original-path "/tmp/v2.mp4"})
  (let [room   (rooms/create! "v1")
        viewer (connect! room "viewer")
        resp   (http! "POST" (str "/api/admin/rooms/" (:id room) "/items") {:video-id "v2"})
        m      (recv-type! (:in viewer) "queue")]
    (is (= 201 (:status resp)))
    (is (= ["v1" "v2"] (map :video-id (:queue m))))
    (is (= "v1" (:video-id m)))))

(deftest removing-the-only-item-empties-the-room-on-the-wire
  (videos/insert! {:id "v1" :title "Ep 1" :original-path "/tmp/v1.mp4"})
  (videos/insert! {:id "v2" :title "Ep 2" :original-path "/tmp/v2.mp4"})
  (videos/set-duration! "v2" 50.0)
  (let [room   (rooms/create! "v1")
        host   (connect! room "host" (login!))
        viewer (connect! room "viewer")
        resp   (http! "DELETE" (str "/api/admin/rooms/" (:id room) "/items/v1") {})
        item   (recv-item! (:in viewer))
        queue  (recv-type! (:in viewer) "queue")]
    (is (= 200 (:status resp)))
    (is (= {:queue [] :video-id nil} (:body resp)))
    (is (contains? item :video) "the item message names the (absent) video")
    (is (nil? (:video item)))
    (is (nil? (:video-id item)))
    (is (false? (:playing item)))
    (is (= 0.0 (:pos item)))
    (is (= [] (:queue queue)))
    (is (nil? (:video-id queue)))
    ;; a fresh connect sees the blank stage
    (let [w (:welcome (connect! room "late"))]
      (is (contains? w :video))
      (is (nil? (:video w)))
      (is (nil? (:video-id w)))
      (is (nil? (get-in w [:state :video-id])))
      (is (false? (get-in w [:state :playing])))
      (is (= [] (:queue w))))
    ;; the host cannot start what is not there
    (send! (:ws host) {:type "cmd" :op "play" :pos 0})
    (is (not-any? #(= "state" (:type %)) (recv-all! (:in viewer) 1000)))
    (is (false? (get-in @sync/rooms* [(:id room) :playing?])))
    ;; adding an item back lands it current and paused
    (let [resp (http! "POST" (str "/api/admin/rooms/" (:id room) "/items") {:video-id "v2"})
          item (recv-item! (:in viewer))]
      (is (= 201 (:status resp)))
      (is (= "v2" (get-in resp [:body :video-id])))
      (is (= {:id "v2" :title "Ep 2" :duration 50.0 :dash "/dash/v2/manifest.mpd"} (:video item)))
      (is (= "v2" (:video-id item)))
      (is (false? (:playing item)))
      (is (= 0.0 (:pos item))))
    (send! (:ws host) {:type "cmd" :op "play" :pos 0})
    (is (= {:op "play" :video-id "v2" :playing true}
           (select-keys (recv-type! (:in viewer) "state") [:op :video-id :playing])))))

(deftest removing-the-current-item-switches-every-viewer
  (let [room   (two-item-room!)
        viewer (connect! room "viewer")
        resp   (http! "DELETE" (str "/api/admin/rooms/" (:id room) "/items/v1") {})
        item   (recv-item! (:in viewer))
        queue  (recv-type! (:in viewer) "queue")]
    (is (= 200 (:status resp)))
    (is (= "v2" (get-in item [:video :id])))
    (is (true? (:playing item)))
    (is (= 0.0 (:pos item)))
    (is (= ["v2"] (map :video-id (:queue queue))))
    (is (= "v2" (:video-id queue)))
    (is (= "v2" (:video-id (:welcome (connect! room "late")))))))
