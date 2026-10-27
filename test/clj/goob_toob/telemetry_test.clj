(ns goob-toob.telemetry-test
  "The episode detectors on synthetic 1 Hz series: each catches its own
  shape and every one stays quiet on clean playback; the chunk format
  round-trips from the watch page's positional rows."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [goob-toob.telemetry :as tele]
            [goob-toob.test-util :refer [tmp-dir]]))

(def t0 1761500000000)

(defn- clean
  "Sample i of a viewer in sync at 1.0x with full buffers and no drops."
  [i]
  {:t (+ t0 (* 1000 i)) :current-time (+ 10.0 i) :expected (+ 10.0 i) :drift 0.0
   :rate 1.0 :room-rate 1.0 :paused false :buffering false :ready-state 4
   :audio-buffer 12.0 :video-buffer 10.0 :dropped-frames 3 :total-frames (* 30 i)
   :quality 2 :qcap nil :fullscreen false})

(defn- series
  "n clean samples, each passed through f with its index."
  [n f]
  (mapv #(f % (clean %)) (range n)))

(defn- between [from to] (fn [i] (and (<= from i) (< i to))))

(defn- client [samples]
  {:session "s1" :nick "viewer" :vids ["v1"] :samples samples :events []})

(defn- flagged [samples]
  (mapv :type (tele/episodes (client samples))))

(deftest clean-playback-flags-nothing
  (is (= [] (flagged (series 180 (fn [_ s] s))))))

(deftest a-long-stretch-off-rate-is-an-episode
  (let [off (between 20 50)
        eps (tele/episodes (client (series 90 (fn [i s] (if (off i) (assoc s :rate 1.05 :drift -0.9) s)))))]
    (is (= [:off-rate] (mapv :type eps)))
    (is (= (+ t0 20000) (:start (first eps))))
    (is (= (+ t0 49000) (:end (first eps))))
    (is (= 29.0 (:duration-s (first eps))))
    (is (= 1.05 (get-in eps [0 :evidence :rate-max]))))
  (testing "a short nudge is not"
    (is (= [] (flagged (series 60 (fn [i s] (if ((between 20 30) i) (assoc s :rate 0.95) s))))))))

(deftest drift-beyond-the-dead-band-is-an-episode
  (let [eps (tele/episodes (client (series 60 (fn [i s] (if ((between 10 20) i) (assoc s :drift -1.6) s)))))]
    (is (= [:drift] (mapv :type eps)))
    (is (= -1.6 (get-in eps [0 :evidence :drift-min]))))
  (testing "inside the band, or briefly outside, is not"
    (is (= [] (flagged (series 60 (fn [_ s] (assoc s :drift 0.8))))))
    (is (= [] (flagged (series 60 (fn [i s] (if ((between 10 13) i) (assoc s :drift 2.0) s))))))))

(deftest two-stalls-close-together-are-a-cluster
  (let [stall? (fn [i] (or ((between 10 13) i) ((between 30 32) i)))
        eps (tele/episodes (client (series 90 (fn [i s] (if (stall? i) (assoc s :buffering true :video-buffer 0.0) s)))))]
    (is (= [:stalls] (mapv :type eps)))
    (is (= 2 (get-in eps [0 :evidence :stalls])))
    (is (= (+ t0 10000) (:start (first eps))))
    (is (= (+ t0 31000) (:end (first eps)))))
  (testing "one stall alone, or two a minute apart, is not"
    (is (= [] (flagged (series 90 (fn [i s] (if ((between 10 13) i) (assoc s :buffering true) s))))))
    (is (= [] (flagged (series 120 (fn [i s] (if (or (= i 10) (= i 80)) (assoc s :buffering true) s))))))))

(deftest a-dropped-frame-burst-is-an-episode
  (let [eps (tele/episodes (client (series 60 (fn [i s] (assoc s :dropped-frames (if (< i 20) 3 (+ 3 (* 10 (- (min i 24) 19)))))))))]
    (is (= [:dropped-frames] (mapv :type eps)))
    (is (= 50 (get-in eps [0 :evidence :dropped])))
    (is (= 10.0 (get-in eps [0 :evidence :peak-per-s]))))
  (testing "a trickle under the per-second threshold is not a burst"
    (is (= [] (flagged (series 20 (fn [i s] (assoc s :dropped-frames (* 2 i)))))))))

(deftest a-starved-video-buffer-while-playing-is-an-episode
  (let [eps (tele/episodes (client (series 60 (fn [i s] (if ((between 10 14) i) (assoc s :video-buffer 0.4) s)))))]
    (is (= [:buffer-starved] (mapv :type eps)))
    (is (= 0.4 (get-in eps [0 :evidence :video-buffer-min]))))
  (testing "a low buffer while paused is not"
    (is (= [] (flagged (series 60 (fn [i s] (if ((between 10 14) i) (assoc s :video-buffer 0.4 :paused true) s))))))))

(deftest one-track-starving-while-the-other-is-full-is-a-desync-signature
  (let [eps (tele/episodes (client (series 60 (fn [i s] (if ((between 10 20) i) (assoc s :audio-buffer 0.3 :video-buffer 12.0) s)))))]
    (is (= [:av-gap] (mapv :type eps)))
    (is (= "audio" (get-in eps [0 :evidence :starving])))
    (is (= 0.3 (get-in eps [0 :evidence :audio-buffer-min]))))
  (testing "levels far apart with both tracks fed is routine dash.js, not a signature"
    (is (= [] (flagged (series 60 (fn [i s] (if ((between 10 40) i) (assoc s :audio-buffer 2.5 :video-buffer 30.0) s)))))))
  (testing "a brief dip is not"
    (is (= [] (flagged (series 60 (fn [i s] (if ((between 10 13) i) (assoc s :audio-buffer 0.3) s))))))))

(deftest dropped-frames-climbing-with-the-sound-fine-is-a-desync-signature
  (let [eps (tele/episodes (client (series 90 (fn [i s] (assoc s :dropped-frames (if (< i 20) 3 (+ 3 (* 2 (- i 19)))))))))]
    (is (= [:av-climb] (mapv :type eps)))
    (is (= 140 (get-in eps [0 :evidence :dropped])))
    (is (= (+ t0 20000) (:start (first eps)))))
  (testing "the same climb off 1.0x, or with the audio buffer starving, is not that signature"
    (let [climb (fn [i] (if (< i 20) 3 (+ 3 (* 2 (- i 19)))))]
      (is (not (some #{:av-climb} (flagged (series 90 (fn [i s] (assoc s :rate 1.05 :dropped-frames (climb i))))))))
      (is (not (some #{:av-climb} (flagged (series 90 (fn [i s] (assoc s :audio-buffer 0.5 :dropped-frames (climb i)))))))))))

;; ---- the chunk format --------------------------------------------------------

(def header ["t" "ct" "exp" "d" "r" "rr" "p" "b" "rs" "ab" "vb" "df" "tf" "q" "qc" "fs"])

(defn- tele-chunk [seq-no rows events]
  {:v 1 :nick "viewer" :vid "v1" :session "abc123" :seq seq-no :t0 (+ t0 (* 300000 seq-no))
   :ua "test" :header header :samples rows :events events})

(deftest chunks-decode-to-samples
  (let [c (tele/decode-chunk (tele-chunk 0 [[0 10.0 10.1 -0.1 1.0 1.0 0 0 4 12.0 10.0 3 30 2 nil 0]
                                       [1000 11.0 11.1 -0.1 1.05 1.0 0 1 2 12.0 0.5 3 60 2 1 1]]
                                    []))
        [a b] (:samples c)]
    (is (= {:t t0 :current-time 10.0 :expected 10.1 :drift -0.1 :rate 1.0 :room-rate 1.0
            :paused false :buffering false :ready-state 4 :audio-buffer 12.0 :video-buffer 10.0
            :dropped-frames 3 :total-frames 30 :quality 2 :qcap nil :fullscreen false}
           a))
    (is (= (+ t0 1000) (:t b)))
    (is (true? (:buffering b)))
    (is (true? (:fullscreen b)))
    (is (= 1 (:qcap b)))))

(deftest files-read-group-and-summarise
  (let [dir (io/file (tmp-dir) "2026-10-27" "room1")
        rows (fn [n] (mapv (fn [i] [(* 1000 i) (+ 10.0 i) (+ 10.0 i) 0.0 1.0 1.0 0 0 4 12.0 10.0 3 (* 30 i) 2 nil 0]) (range n)))
        line (fn [m] (str (json/generate-string m) "\n"))]
    (.mkdirs dir)
    (spit (io/file dir "abc123-viewer.jsonl")
          (str (line (tele-chunk 0 (rows 5) [{:t (+ t0 2500) :type "mark" :note "viewer pressed the button"}]))
               (line (tele-chunk 1 (rows 3) [{:t (+ t0 300500) :type "idle"}]))))
    (spit (io/file dir "zzz999-host.jsonl")
          (line (assoc (tele-chunk 2 (rows 2) []) :nick "host" :session "zzz999")))
    (let [chunks (tele/read-path (.getParent dir))
          {:keys [clients episodes]} (tele/analyse chunks)
          viewer (first (filter #(= "viewer" (:nick %)) clients))]
      (is (= 3 (count chunks)))
      (is (= ["viewer" "host"] (mapv :nick clients)) "earliest client first")
      (is (= 8 (:samples viewer)))
      (is (= t0 (:from viewer)))
      (is (= (+ t0 300000 2000) (:to viewer)))
      (is (= ["mark" "idle"] (mapv :type (:marks viewer))))
      (is (= 0.0 (:drift-abs-max viewer)))
      (is (= [] episodes))
      (is (= {:name "abc123-viewer.jsonl" :chunks 2 :samples 8 :first t0 :last (+ t0 302000)}
             (dissoc (tele/file-info (io/file dir "abc123-viewer.jsonl")) :size)))
      (testing "the report prints"
        (let [out (with-out-str (tele/print-report (tele/analyse chunks)))]
          (is (str/includes? out "client viewer (abc123)"))
          (is (str/includes? out "mark viewer pressed the button"))
          (is (str/includes? out "no episodes flagged")))))))
