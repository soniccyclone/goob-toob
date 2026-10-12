(ns goob-toob.lobby
  "Public lobby: rooms the admin has opened, ready to slime into."
  (:require [goob-toob.ui :as ui :refer [h]]
            [goob-toob.api :as api]))

(defn- render [grid rooms]
  (ui/clear! grid)
  (if (empty? rooms)
    (ui/add! grid (h :p.muted "no rooms open right now — ask your host to queue something up."))
    (doseq [r rooms]
      (ui/add! grid
        (h :div.card.row-gap
           (h :h3 (:title r))
           (h :a {:href (str "/watch/" (:token r))}
              (h :button.btn "▶ watch together"))))))

(defn page []
  (let [grid (h :div.grid {})
        root (h :div.tiles {} grid)
        refresh #(-> (api/get-json "/api/rooms")
                     (.then (fn [{:keys [data]}] (render grid data))))]
    (refresh)
    (js/setInterval refresh 10000)
    root))
