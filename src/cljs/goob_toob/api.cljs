(ns goob-toob.api
  "fetch helpers; every call resolves to {:status s :data d}")

(defn- ->result [resp]
  (-> (.text resp)
      (.then (fn [txt]
               {:status (.-status resp)
                :data (try (js->clj (js/JSON.parse txt) :keywordize-keys true)
                           (catch js/Error _ nil))}))))

(defn get-json [url]
  (-> (js/fetch url) .then (fn [r] (->result r))))

(defn req [method url body]
  (-> (js/fetch url #js {:method method
                         :headers #js {"Content-Type" "application/json"}
                         :body (js/JSON.stringify (clj->js body))})
      .then (fn [r] (->result r))))

(def post! (partial req "POST"))
(def delete! (partial req "DELETE"))

(defn upload-file
  "Uploads one File with progress callback [loaded total]; resolves to status."
  [file on-progress]
  (js/Promise.
    (fn [resolve _reject]
      (let [form (js/FormData.)
            xhr  (js/XMLHttpRequest.)]
        (.append form "file" file)
        (.open xhr "POST" "/api/admin/upload")
        (set! (.-onprogress (.-upload xhr))
              (fn [e] (on-progress (.-loaded e) (.-total e))))
        (set! (.-onload xhr) #(resolve (.-status xhr)))
        (set! (.-onerror xhr) #(resolve 0))
        (.send xhr form)))))
