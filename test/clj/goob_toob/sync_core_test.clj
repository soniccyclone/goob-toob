(ns goob-toob.sync-core-test
  (:require [clojure.test :refer [deftest is are]]
            [goob-toob.sync-core :as sc]))

(deftest expected-pos-moves-with-clock
  (is (= 10.0 (sc/expected-pos {:pos 10 :t0 1000 :now 5000 :playing? false :rate 1})))
  (is (= 14.0 (sc/expected-pos {:pos 10 :t0 1000 :now 5000 :playing? true :rate 1})))
  (is (= 12.0 (sc/expected-pos {:pos 10 :t0 1000 :now 3000 :playing? true :rate 1})))
  ;; never goes back in time
  (is (= 10.0 (sc/expected-pos {:pos 10 :t0 1000 :now 500 :playing? true :rate 1}))))

(deftest policy-thresholds
  (are [d pol] (= pol (sc/policy d))
    0.0   :none
    0.29  :none
    -0.29 :none
    -0.5  :nudge-fast   ; behind a little -> speed up
    0.5   :nudge-slow   ; ahead a little -> slow down
    -1.51 :seek         ; way behind (buffer stall) -> hard seek = catch-up
    1.51  :seek))       ; way ahead -> seek back

(deftest ntp-offset-estimates-server-skew
  ;; client sent at 0, server stamped 100, client received at 20 (rtt 20)
  (is (= 90.0 (sc/ntp-offset 0 100 20)))
  ;; symmetric: no skew
  (is (= 0.0 (sc/ntp-offset 1000 1010 1020))))

(deftest best-offset-prefers-lowest-rtt
  (is (= 42.0
         (sc/best-offset [{:rtt 80 :offset 30}
                          {:rtt 12 :offset 42}
                          {:rtt 55 :offset 50}]))))
