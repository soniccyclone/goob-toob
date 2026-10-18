(ns goob-toob.log
  "Timestamped server log lines on stdout."
  (:require [clojure.string :as str])
  (:import [java.time LocalTime]
           [java.time.format DateTimeFormatter]))

(def ^:private stamp (DateTimeFormatter/ofPattern "HH:mm:ss.SSS"))

(defn info [& xs]
  (println (str (.format (LocalTime/now) stamp) " " (str/join " " xs))))
