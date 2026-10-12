(ns goob-toob.ui
  "Tiny vanilla-DOM helpers: no framework, we patch what changes."
  (:require [clojure.string :as str])
  (:refer-clojure :exclude [next]))

(defn $ [sel] (.querySelector js/document sel))
(defn $$ [sel] (array-seq (.querySelectorAll js/document sel)))

(defn h
  "h tag [attrs] & children — tag is :div or :button.btn.aqua (dot shorthand
  for classes), attrs a map (:class :onclick :oninput :onchange :onkeydown
  :value :href :type :id :placeholder :title :disabled), children are strings
  or nodes."
  [tag & args]
  (let [[tag-shorthand & class-shorthands] (-> tag name (str/split #"\."))
        [attrs kids] (if (map? (first args)) [(first args) (rest args)] [{} args])
        attrs (if (seq class-shorthands)
                (update attrs :class
                        #(str/join " " (remove nil? (cons % class-shorthands))))
                attrs)
        node (.createElement js/document tag-shorthand)]
    (doseq [[k v] attrs]
      (cond
        (nil? v)      nil
        (= k :class)  (set! (.-className node) v)
        (= k :id)     (set! (.-id node) v)
        (= k :value)  (set! (.-value node) v)
        (= k :href)   (.setAttribute node "href" v)
        (= k :type)   (.setAttribute node "type" v)
        (= k :placeholder) (.setAttribute node "placeholder" v)
        (= k :title)  (.setAttribute node "title" v)
        (= k :disabled) (set! (.-disabled node) (boolean v))
        (= k :checked)  (set! (.-checked node) (boolean v))
        (= k :width)    (.setAttribute node "width" v)
        (fn? v)       (.addEventListener node (apply str (drop 2 (name k))) v)
        :else         (.setAttribute node (name k) (str v))))
    (doseq [kid (remove nil? kids)]
      (.appendChild node
        (if (or (string? kid) (number? kid))
          (.createTextNode js/document (str kid))
          kid)))
    node))

(defn clear! [node] (set! (.-innerHTML node) "") node)

(defn add! [node & kids]
  (doseq [k (remove nil? kids)] (.appendChild node k))
  node)

(defn now [] (.now js/Date))
(defn perf-now [] (.now (.-performance js/window)))

(defn- pad2 [n] (if (< n 10) (str "0" n) (str n)))

(defn fmt-time [secs]
  (when (number? secs)
    (let [s (int secs) m (int (/ s 60))]
      (if (>= m 60)
        (str (int (/ m 60)) ":" (pad2 (mod m 60)) ":" (pad2 (mod s 60)))
        (str m ":" (pad2 (mod s 60)))))))

(defn fmt-lag [secs]
  (when (number? secs)
    (let [a (abs secs)]
      (if (< a 2) "synced"
        (str (int (js/Math.round a)) "s " (if (neg? secs) "behind" "ahead"))))))

(defn store-get [k] (.getItem js/localStorage k))
(defn store-set! [k v] (.setItem js/localStorage k v))

(defn copy! [text]
  (-> (.-clipboard (.-navigator js/window))
      (.writeText text)))

(defn nick-color [nick]
  (let [colors ["#8fe032" "#1fc8db" "#7fe7f2" "#b3ff4d" "#5ce6b0" "#a0e8ff"]
        n (reduce (fn [a c] (+ a (int c))) 0 nick)]
    (nth colors (mod n (count colors)))))
