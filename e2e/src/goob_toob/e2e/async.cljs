(ns goob-toob.e2e.async
  (:require-macros [goob-toob.e2e.async])
  (:require [cljs.core.async :as a]))

(defn unwrap [e]
  (if (= :promise-error (:error (ex-data e))) (ex-cause e) e))

(defn throw-err [v]
  (if (instance? js/Error v) (throw v) v))

(defn chan->promise [ch]
  (js/Promise. (fn [res rej]
                 (a/take! ch (fn [v] (if (instance? js/Error v) (rej v) (res v)))))))
