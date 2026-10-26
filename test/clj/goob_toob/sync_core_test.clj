(ns goob-toob.sync-core-test
  (:require [clojure.test :refer [deftest is]]
            [goob-toob.sync-core :as sc]))

(deftest expected-pos-moves-with-clock
  (is (= 10.0 (sc/expected-pos {:pos 10 :t0 1000 :now 5000 :playing? false :rate 1})))
  (is (= 14.0 (sc/expected-pos {:pos 10 :t0 1000 :now 5000 :playing? true :rate 1})))
  (is (= 12.0 (sc/expected-pos {:pos 10 :t0 1000 :now 3000 :playing? true :rate 1})))
  ;; never goes back in time
  (is (= 10.0 (sc/expected-pos {:pos 10 :t0 1000 :now 500 :playing? true :rate 1}))))

(deftest policy-ignores-drift-inside-the-dead-band
  (is (= :none (sc/policy :none -0.9 20)))
  (is (= :none (sc/policy :none 0.4 20))))

(deftest policy-catches-up-only-with-runway
  (is (= :catch-up (sc/policy :none -2.0 20)))
  (is (= :none (sc/policy :none -2.0 3))))

(deftest policy-keeps-catching-up-until-settled
  (is (= :catch-up (sc/policy :catch-up -0.5 20)))
  (is (= :none (sc/policy :catch-up -0.1 20))))

(deftest policy-never-seeks-forward
  (is (= :catch-up (sc/policy :none -100.0 20)))
  (is (= :none (sc/policy :none -100.0 0))))

(deftest policy-nudges-when-ahead-and-exits-at-settle
  (is (= :nudge-slow (sc/policy :none 0.6 20)))
  (is (= :nudge-slow (sc/policy :nudge-slow 0.2 20)))
  (is (= :none (sc/policy :nudge-slow 0.1 20))))

(deftest policy-never-seeks-however-far-ahead
  (is (= :nudge-slow (sc/policy :none 1.6 20)))
  (is (= :nudge-slow (sc/policy :none 100.0 20)))
  (is (= :nudge-slow (sc/policy :none 1.4 20))))

(deftest rate-for-is-relative-to-the-room-rate
  (is (= 0.97 (sc/rate-for :none 0.97)))
  (is (= 1.05 (sc/rate-for :catch-up 0.97)))
  (is (= 0.95 (sc/rate-for :nudge-slow 1.0))))

(deftest room-rate-slows-at-three-seconds-behind
  (is (= {:rate 0.97 :for "slow"}
         (sc/room-rate 1.0 [{:nick "slow" :drift -3.0 :age-ms 0 :behind-ms 0}]))))

(deftest room-rate-holds-between-exit-and-enter
  (let [clients [{:nick "mid" :drift -2.0 :age-ms 0 :behind-ms 0}]]
    (is (= 0.97 (:rate (sc/room-rate 0.97 clients))))
    (is (= 1.0 (:rate (sc/room-rate 1.0 clients))))))

(deftest room-rate-recovers-when-everyone-is-within-a-second
  (is (= {:rate 1.0 :for nil}
         (sc/room-rate 0.97 [{:nick "a" :drift -0.8 :age-ms 0 :behind-ms 0}
                             {:nick "b" :drift 0.2 :age-ms 0 :behind-ms 0}]))))

(deftest room-rate-ignores-stale-and-gone-clients
  (is (= 1.0 (:rate (sc/room-rate 1.0 [{:nick "stale" :drift -20.0 :age-ms 20000 :behind-ms 0}]))))
  (is (= 1.0 (:rate (sc/room-rate 1.0 [{:nick "gone" :drift -70.0 :age-ms 0 :behind-ms 0}])))))

(deftest room-rate-gives-up-on-a-chronic-laggard
  (is (= {:rate 1.0 :for nil}
         (sc/room-rate 0.97 [{:nick "chronic" :drift -4.0 :age-ms 0 :behind-ms 200000}])))
  (is (= 0.97 (:rate (sc/room-rate 0.97 [{:nick "chronic" :drift -4.0 :age-ms 0 :behind-ms 100000}])))))

(deftest reanchor-does-not-move-the-position
  (let [r (sc/reanchor {:pos 10 :t0 1000 :playing? true :rate 1.0} 5000 0.97)]
    (is (= 14.0 (:pos r)))
    (is (= 5000 (:t0 r)))
    (is (= 0.97 (:rate r)))))

(deftest ntp-offset-estimates-server-skew
  ;; client sent at 0, server stamped 100, client received at 20 (rtt 20)
  (is (= 90.0 (sc/ntp-offset 0 100 20)))
  ;; symmetric: no skew
  (is (= 0.0 (sc/ntp-offset 1000 1010 1020))))

(deftest best-offset-prefers-lowest-rtt
  (is (= 42
         (sc/best-offset [{:rtt 80 :offset 30}
                          {:rtt 12 :offset 42}
                          {:rtt 55 :offset 50}]))))

(deftest quality-cap-steps-down-one-rung-on-stall
  (is (= {:cap 2 :at 1000} (sc/quality-cap nil :stall 3 3 1000))))

(deftest quality-cap-steps-down-on-catch-up-entry
  (is (= {:cap 1 :at 1000} (sc/quality-cap nil :catch-up 2 3 1000))))

(deftest quality-cap-floors-at-the-lowest-rung
  (is (= {:cap 0 :at 20000} (sc/quality-cap {:cap 0 :at 0} :stall 0 3 20000))))

(deftest quality-cap-ignores-events-within-min-step
  (is (= {:cap 2 :at 0} (sc/quality-cap {:cap 2 :at 0} :stall 2 3 5000))))

(deftest quality-cap-steps-down-from-the-playing-rung
  (is (= {:cap 0 :at 20000} (sc/quality-cap {:cap 3 :at 0} :stall 1 3 20000))))

(deftest quality-cap-holds-until-a-clean-minute
  (is (= {:cap 1 :at 0} (sc/quality-cap {:cap 1 :at 0} :tick 1 3 59000)))
  (is (= {:cap 2 :at 60000} (sc/quality-cap {:cap 1 :at 0} :tick 1 3 60000))))

(deftest quality-cap-clears-at-the-top
  (is (nil? (sc/quality-cap {:cap 2 :at 0} :tick 2 3 60000)))
  (is (nil? (sc/quality-cap nil :tick 3 3 60000))))

(deftest cap-kbps-reads-the-capped-rung
  (let [reps [{:idx 0 :bitrate 450000} {:idx 1 :bitrate 900000}]]
    (is (= 900 (sc/cap-kbps {:cap 1 :at 0} reps)))
    (is (= -1 (sc/cap-kbps nil reps)))))

(deftest start-kbps-prefers-the-720-rung
  (is (= 2000 (sc/start-kbps [{:h 1080 :v 4000} {:h 720 :v 2000} {:h 480 :v 900} {:h 360 :v 450}])))
  (is (= 450 (sc/start-kbps [{:h 360 :v 450}]))))

(deftest expected-pos-honours-the-room-rate
  (is (< (Math/abs (- 19.7 (sc/expected-pos {:pos 10 :t0 0 :now 10000 :playing? true :rate 0.97})))
         1e-9)))

(deftest drift-is-local-minus-expected
  (is (= -0.5 (sc/drift 9.5 10.0))))

(deftest room-rate-picks-the-worst-laggard
  (is (= {:rate 0.97 :for "b"}
         (sc/room-rate 1.0 [{:nick "a" :drift -3.5 :age-ms 0 :behind-ms 0}
                            {:nick "b" :drift -5.0 :age-ms 0 :behind-ms 0}]))))

(deftest start-action-autostarts-into-a-playing-room
  (is (= :autostart (sc/start-action :state {:started? false :playing? true}))))

(deftest start-action-parks-on-a-paused-room
  (is (= :park (sc/start-action :state {:started? false :playing? false :can-control? true}))))

(deftest start-action-does-nothing-once-started
  (is (= :none (sc/start-action :state {:started? true :playing? true}))))

(deftest start-action-refuses-a-play-without-a-gesture
  (is (= :park (sc/start-action :play {:started? false :gesture? false :playing? true}))))

(deftest start-action-starts-into-a-playing-room
  (is (= :start (sc/start-action :play {:started? false :gesture? true :playing? true}))))

(deftest start-action-lets-a-controller-start-the-room
  (is (= :start-room (sc/start-action :play {:started? false :gesture? true :playing? false
                                             :can-control? true})))
  (is (= :park (sc/start-action :play {:started? false :gesture? true :playing? false
                                       :can-control? false}))))

(deftest start-action-blocks-solo-play-after-joining
  (is (= :park (sc/start-action :play {:started? true :playing? false})))
  (is (= :none (sc/start-action :play {:started? true :playing? true}))))
