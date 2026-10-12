(ns goob-toob.client
  "Entry point: tiny path router, shared chrome."
  (:require [clojure.string :as str]
            [goob-toob.ui :as ui :refer [h]]
            [goob-toob.lobby :as lobby]
            [goob-toob.watch :as watch]
            [goob-toob.admin :as admin]))

(defn- page-for [path]
  (if-let [[_ token] (re-matches #"^/watch/(.+?)/?$" path)]
    (watch/page token)
    (cond
      (str/starts-with? path "/admin") (admin/page)
      :else (lobby/page))))

(defn init []
  (let [root (ui/clear! (ui/$ "#app"))]
    (ui/add! root
      (h :header.topbar
         (h :a.logo {:href "/" :style "text-decoration:none"} "goob-toob")
         (h :div {:style "margin-left:auto"}
            (h :div.goo {}
              (h :a {:href "/"} (h :button.btn.ghost "lobby"))
              (h :a {:href "/admin"} (h :button.btn.ghost "admin")))))
      (h :div.wave {})
      (h :main.row-gap {} (page-for (.-pathname js/location))))))

(defn reload! []
  (init))
