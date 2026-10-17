(ns goob-toob.config-test
  (:require [clojure.test :refer [deftest is]]
            [goob-toob.config :as config]))

(deftest parse-ladder-reads-the-env-format
  (is (= [{:h 1080 :v 4000} {:h 720 :v 2000}] (config/parse-ladder "1080:4000,720:2000")))
  (is (= config/default-ladder (config/parse-ladder nil)))
  (is (= config/default-ladder (config/parse-ladder "")))
  (is (thrown? clojure.lang.ExceptionInfo (config/parse-ladder "junk"))))
