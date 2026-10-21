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

(deftest welcome-describes-the-room-and-the-viewer
  (let [room (room!)
        {:keys [welcome]} (connect! room "ann")]
    (is (= "welcome" (:type welcome)))
    (is (false? (:is-host welcome)))
    (is (false? (:can-control welcome)))
    (is (= {:id "v1" :title "Clip" :duration 42.0 :dash "/dash/v1/manifest.mpd"}
           (:video welcome)))
    (is (false? (get-in welcome [:state :playing])))
    (is (= 0.0 (get-in welcome [:state :pos])))
    (is (= 2000 (:start-kbps welcome)))
    (is (= ["ann"] (map :nick (:peers welcome))))))

(deftest nick-is-trimmed-and-clamped
  (let [room (room!)]
    (is (= [(apply str (repeat 32 "a"))]
           (map :nick (:peers (:welcome (connect! room (str "  " (apply str (repeat 40 "a")) "  ")))))))
    (is (some #{"guest"} (map :nick (:peers (:welcome (connect! room ""))))))))

(deftest admin-session-makes-the-host
  (let [room (room!)
        {:keys [welcome]} (connect! room "host" (login!))]
    (is (true? (:is-host welcome)))
    (is (true? (:can-control welcome)))))

(deftest viewer-commands-are-ignored-without-co-host-control
  (let [room (room!)
        v1 (connect! room "v1")
        v2 (connect! room "v2")]
    (send! (:ws v1) {:type "cmd" :op "play" :pos 0})
    (is (not-any? #(= "state" (:type %)) (recv-all! (:in v2) 1000)))
    (is (false? (get-in @sync/rooms* [(:id room) :playing?])))))

(deftest co-host-control-opens-commands-live
  (let [room (room!)
        v1 (connect! room "v1")
        v2 (connect! room "v2")]
    (rooms/set-control! (:id room) true)
    (send! (:ws v1) {:type "cmd" :op "play" :pos 0})
    (is (= "play" (:op (recv-type! (:in v1) "state"))))
    (is (= "play" (:op (recv-type! (:in v2) "state"))))))

(deftest host-play-reaches-every-viewer
  (let [room (room!)
        host (connect! room "host" (login!))
        viewer (connect! room "viewer")]
    (send! (:ws host) {:type "cmd" :op "play" :pos 3.0})
    (let [m (recv-type! (:in viewer) "state")]
      (is (= "play" (:op m)))
      (is (= 3.0 (:pos m))))))

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

(deftest ping-answers-with-both-clocks
  (let [room (room!)
        {:keys [ws in]} (connect! room "ann")]
    (send! ws {:type "ping" :t 123})
    (let [m (recv-type! in "pong")]
      (is (= 123 (:t m)))
      (is (number? (:st m))))))

(deftest leaving-updates-the-peer-list
  (let [room (room!)
        v1 (connect! room "v1")
        v2 (connect! room "v2")]
    (.join (.sendClose ^WebSocket (:ws v1) WebSocket/NORMAL_CLOSURE ""))
    (is (= ["v2"]
           (loop []
             (when-let [m (recv-type! (:in v2) "peers")]
               (if (= 1 (count (:peers m))) (map :nick (:peers m)) (recur))))))))

(deftest status-is-recorded
  (let [room (room!)
        {:keys [ws]} (connect! room "ann")
        clients #(get-in @sync/rooms* [(:id room) :clients])]
    (send! ws {:type "status" :pos 0 :buffering true})
    (is (wait-until #(:buffering (val (first (clients)))) 1000))))
