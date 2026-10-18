(ns goob-toob.log-test
  (:require [clojure.test :refer [deftest is]]
            [goob-toob.log :as log]))

(deftest log-lines-start-with-a-timestamp
  (is (re-matches #"^\d\d:\d\d:\d\d\.\d{3} a 1\n$" (with-out-str (log/info "a" 1)))))
