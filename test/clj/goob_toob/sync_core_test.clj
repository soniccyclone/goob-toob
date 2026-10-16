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

(deftest policy-seeks-back-only-when-far-ahead
  (is (= :seek-back (sc/policy :none 1.6 20)))
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
