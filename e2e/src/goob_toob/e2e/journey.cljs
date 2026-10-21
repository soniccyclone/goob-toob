(ns goob-toob.e2e.journey
  (:require-macros [goob-toob.e2e.journey])
  (:require ["@playwright/test" :refer [test]]
            [cljs.core.async :as a]
            [goob-toob.e2e.party :as party]))

(def ^:private signature "({browser, browserName, baseURL, request}, testInfo) => {}")

(defn fixtures [^js fx]
  {:browser (.-browser fx) :browser-name (.-browserName fx)
   :base-url (.-baseURL fx) :request (.-request fx)})

(defn unwrap [e]
  (if (= :promise-error (:error (ex-data e))) (ex-cause e) e))

(defn chan->promise [ch]
  (js/Promise. (fn [res rej]
                 (a/take! ch (fn [v] (if (instance? js/Error v) (rej v) (res v)))))))

(defn journey-fn [body]
  (let [f (fn [fx info]
            (-> (chan->promise (body fx info))
                (.finally party/close-all!)))]
    (set! (.-toString f) (constantly signature))
    f))

(defn only-engine! [browser-name engine]
  (.skip test (not= browser-name engine) (str "runs in " engine " only")))
