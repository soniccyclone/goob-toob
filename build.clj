(ns build
  "Uberjar for the desktop bundle: clojure -T:build uberjar -> target/goob-toob.jar.
   Assumes resources/public/js has been shadow-released already (make uberjar)."
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file "target/goob-toob.jar")

(defn- version []
  (or (some-> (System/getenv "VERSION") (not-empty))
      (some-> (:out (b/process {:command-args ["git" "describe" "--tags"]}))
              str/trim (not-empty))
      "dev"))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uberjar [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src/clj" "src/cljc" "resources"]
               :target-dir class-dir})
  (spit (io/file class-dir "goob-toob.version") (version)) ; b/write-file would pr-print the string
  (b/compile-clj {:basis basis
                  :src-dirs ["src/clj" "src/cljc"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file jar-file
           :basis basis
           :main 'goob-toob.core}))
