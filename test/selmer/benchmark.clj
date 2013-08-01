(ns selmer.benchmark
  (:require [clojure.test :refer :all]
            [selmer.parser :refer :all]
            [selmer.util :refer :all]
            [criterium.core :as criterium])
  (:import java.io.StringReader))

(def user (repeat 10 [{:name "test" }]))

(def nested-for-context {:users (repeat 10 user)})

(def big-for-context {:users (repeat 100 user)})

(def large-content (apply str (repeat 2500 "bitemyapp > * ")))

(def filter-chain (apply str (repeat 100 "|inc")))

(deftest ^:benchmark bench-for-typical []
  (println "BENCH: bench-for-typical")
  (criterium/quick-bench
   (render-file "templates/nested-for.html"
                nested-for-context)))

(deftest ^:benchmark bench-for-huge []
  (println "BENCH: bench-for-huge")
  (criterium/quick-bench
   (render-file "templates/nested-for.html"
                big-for-context)))

(deftest ^:benchmark bench-assoc-in []
  (println "BENCH: bench-assoc-in")
  (criterium/quick-bench
   (assoc-in {:a {:b {:c 0}}} [:a :b :c] 1)))

(deftest ^:benchmark bench-assoc-in* []
  (println "BENCH: bench-assoc-in*")
  (criterium/quick-bench
   (assoc-in* {:a {:b {:c 0}}} [:a :b :c] 1)))

(deftest ^:benchmark bench-inject []
  (println "BENCH: bench-inject")
  (criterium/quick-bench
   (render-file "templates/child.html" {:content large-content})))

(deftest ^:benchmark bench-filter-hot-potato []
  (println "BENCH: bench-filter-hot-potato")
  (criterium/quick-bench
   (render-template (parse (java.io.StringReader. (str "{{bar" filter-chain "}}"))
                        {:custom-filters
                         {:inc (fn [s] (inc (Integer. s)))}}) {:bar "0"})))
