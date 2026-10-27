(ns goob-toob.repos.settings
  "Global admin settings, one row per key (migration 006). Only known keys
  are stored; everything else is a :bad-request. A key that was never set
  reads as its default."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [goob-toob.db :as db]))

(def defaults
  "Every setting the app knows, with its default. :telemetry is the
  playback recording of #57: off means no viewer sends a chunk and the
  server refuses the route."
  {:telemetry true})

(def ^:private kebab {:builder-fn rs/as-unqualified-kebab-maps})

(defn- parse [k v]
  (case k
    :telemetry (= "true" v)
    v))

(defn all
  "Every known setting, stored value or default."
  []
  (let [rows (jdbc/execute! (db/db) (sql/format {:select [:key :value] :from [:settings]}) kebab)
        stored (into {} (for [{:keys [key value]} rows
                              :let [k (keyword key)]
                              :when (contains? defaults k)]
                          [k (parse k value)]))]
    (merge defaults stored)))

(defn get [k] (clojure.core/get (all) k))

(defn set!
  "Stores a known key; the value is kept as a string."
  [k v]
  (when-not (contains? defaults k)
    (throw (ex-info (str "unknown setting " (name k)) {:type :bad-request :key k})))
  (jdbc/execute! (db/db)
    (sql/format {:insert-into :settings
                 :values [{:key (name k) :value (str v)}]
                 :on-conflict :key
                 :do-update-set [:value]}))
  (get k))

(defn telemetry? [] (boolean (get :telemetry)))
