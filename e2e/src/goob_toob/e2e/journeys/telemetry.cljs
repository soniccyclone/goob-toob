(ns goob-toob.e2e.journeys.telemetry
  "Every started viewer's playback numbers reach the server on their own
  (#57): a 1 Hz sampler on the watch page, one chunk per five minutes,
  the unsent tail by beacon when the page goes. The journey plays a
  viewer for :telemetry-window, presses the marker button, leaves the
  page (pagehide, so the beacon carries the tail inside the test's
  budget) and reads the file back through the admin listing."
  (:refer-clojure :exclude [test])
  (:require ["@playwright/test" :refer [test expect]]
            [clojure.string :as str]
            [cljs.core.async :as a :refer [<!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goob-toob.e2e.api :as api]
            [goob-toob.e2e.async :refer-macros [go-try <?]]
            [goob-toob.e2e.journey :refer [defjourney]]
            [goob-toob.e2e.metrics :as m]
            [goob-toob.e2e.party :as party]
            [goob-toob.e2e.thresholds :refer [W]]))

(defn- room-entry
  "The listing's entry for room-id, whichever day it landed on."
  [listing room-id]
  (first (for [{:keys [day rooms]} listing
               r rooms
               :when (= room-id (:id r))]
           (assoc r :day day))))

(defn- wait-recorded!
  "Polls the admin listing until room-id has a file of nick's with at
  least n samples; that file (with :day), or nil after timeout."
  [base cookie room-id nick n timeout]
  (go-try
    (let [deadline (+ (js/Date.now) timeout)
          mine? #(str/ends-with? (:name %) (str "-" nick ".jsonl"))]
      (loop []
        (let [r (room-entry (<p! (api/telemetry base cookie)) room-id)
              f (first (filter #(and (mine? %) (>= (:samples %) n)) (:files r)))]
          (cond
            f (assoc f :day (:day r))
            (< (js/Date.now) deadline) (do (<! (a/timeout 1000)) (recur))
            :else nil))))))

(defn- chunks
  "The jsonl text as chunk maps."
  [text]
  (if (string? text)
    (mapv #(js->clj (js/JSON.parse %) :keywordize-keys true)
          (remove str/blank? (str/split-lines text)))
    []))

(defjourney "every session is recorded" [{:keys [browser browser-name base-url]} info]
  (let [{:keys [cookie] :as fixture} (api/fixture info)
        {:keys [room viewer]} (<? (party/pair! browser base-url browser-name fixture))
        ^js vp (:page viewer)
        series (<? (m/sample vp (:telemetry-window W)))
        before (:telemetry (<? (party/probe vp)))
        ;; the viewer leaves for the lobby: pagehide, and the beacon carries the tail
        _ (<p! (.goto vp "/"))
        file (<? (wait-recorded! base-url cookie (:id room) "viewer" (:telemetry-min-samples W) (:ready-timeout W)))
        text (when file (<p! (api/telemetry-file base-url cookie (:day file) (:id room) (:name file))))
        cs (chunks text)
        rows (mapcat :samples cs)
        row-bytes (map #(.-length (js/JSON.stringify (clj->js %))) rows)
        {an-status :status an :data} (if file
                                       (<p! (api/telemetry-analysis base-url cookie (:day file) (:id room)))
                                       {:status 0 :data nil})]
    (<p! (m/record! info (m/summarize series 0) series
                    {:pending (:pending before) :events (:events before)
                     :file (:name file) :bytes (:size file) :chunks (count cs) :samples (:samples file)
                     :bytes-per-sample (when (seq row-bytes) (/ (reduce + row-bytes) (count row-bytes)))
                     :envelope-bytes (when file (- (:size file) (reduce + 0 row-bytes)))
                     :clients (mapv :nick (:clients an))
                     :episodes (mapv :type (:episodes an))}))
    (-> (expect (:pending before) "samples pending on the page before it left") (.toBeGreaterThanOrEqual (:telemetry-min-samples W)))
    (-> (expect (some? file) "a file for the room with enough samples") (.toBe true))
    (-> (expect (count cs) "chunks in the file") (.toBeGreaterThanOrEqual 1))
    (-> (expect (reduce + (map #(count (:samples %)) cs)) "samples in the file") (.toBeGreaterThanOrEqual (:telemetry-min-samples W)))
    (-> (expect an-status "analysis status") (.toBe 200))
    (-> (expect (vector? (:episodes an)) "episodes vector") (.toBe true))
    (-> (expect (clj->js (mapv :nick (:clients an))) "clients analysed") (.toContain "viewer"))))
