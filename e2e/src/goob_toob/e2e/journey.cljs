(ns goob-toob.e2e.journey
  (:require-macros [goob-toob.e2e.journey])
  (:require ["@playwright/test" :refer [test]]
            [goob-toob.e2e.async :as async]
            [goob-toob.e2e.party :as party]))

(def ^:private signature "({browser, browserName, baseURL, request}, testInfo) => {}")

(defn fixtures [^js fx]
  {:browser (.-browser fx) :browser-name (.-browserName fx)
   :base-url (.-baseURL fx) :request (.-request fx)})

(defn journey-fn [body]
  (let [f (fn [fx info]
            (-> (async/chan->promise (body fx info))
                (.finally party/close-all!)))]
    (set! (.-toString f) (constantly signature))
    f))

(defn only-engine! [browser-name engine]
  (.skip test (not= browser-name engine) (str "runs in " engine " only")))
