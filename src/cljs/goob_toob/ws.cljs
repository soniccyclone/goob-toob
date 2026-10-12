(ns goob-toob.ws
  "WebSocket with auto-reconnect; on-open fires on every (re)connect so the
  watch page can resync after a blip.")

(defn connect
  "Returns a close! fn."
  [url on-msg on-open]
  (let [state (js-obj "closed" false "ws" nil)
        attempt
        (fn attempt []
          (when-not (.-closed state)
            (let [ws (js/WebSocket. url)]
              (set! (.-ws state) ws)
              (set! (.-onopen ws) #(on-open ws))
              (set! (.-onmessage ws)
                    (fn [e]
                      (when-let [m (try (js->clj (js/JSON.parse (.-data e))
                                                :keywordize-keys true)
                                        (catch js/Error _ nil))]
                        (on-msg m ws))))
              (set! (.-onclose ws)
                    #(when-not (.-closed state)
                       (js/setTimeout attempt 2000))))))
        _ (attempt)]
    (fn [] (set! (.-closed state) true) (.close (.-ws state)))))

(defn send! [ws msg]
  (when (and ws (= 1 (.-readyState ws)))
    (.send ws (js/JSON.stringify (clj->js msg)))))
