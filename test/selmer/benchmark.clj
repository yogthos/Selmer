(ns selmer.benchmark
  (:require [clojure.test :refer :all]
            [selmer.parser :refer :all]
            [selmer.filters :as filters]
            [selmer.util :refer :all]
            [criterium.core :as criterium]
            [clojure.string :as string]
            [selmer.parser :as parser]))

(def user (repeat 10 [{:name "test"}]))

(def nested-for-context {:users (repeat 10 user)})

(def big-for-context {:users (repeat 100 user)})

(def large-content (apply str (repeat 2500 "yogthos > * ")))

(def filter-chain (apply str (repeat 100 "|inc")))

(deftest ^:benchmark bench-for-typical
  (println "BENCH: bench-for-typical")
  (criterium/quick-bench
   (render-file "templates/nested-for.html"
                nested-for-context)))

(deftest ^:benchmark bench-for-huge
  (println "BENCH: bench-for-huge")
  (criterium/quick-bench
   (render-file "templates/nested-for.html"
                big-for-context)))

(deftest ^:benchmark bench-assoc-in
  (println "BENCH: bench-assoc-in")
  (criterium/quick-bench
   (assoc-in {:a {:b {:c 0}}} [:a :b :c] 1)))

(deftest ^:benchmark bench-assoc-in*
  (println "BENCH: bench-assoc-in*")
  (criterium/quick-bench
   (assoc-in* {:a {:b {:c 0}}} [:a :b :c] 1)))

(deftest ^:benchmark bench-inject
  (println "BENCH: bench-inject")
  (criterium/quick-bench
   (render-file "templates/child.html" {:content large-content})))

(deftest ^:benchmark bench-filter-hot-potato
  (println "BENCH: bench-filter-hot-potato")
  (filters/add-filter! :inc (fn [^String s] (str (inc (Integer/parseInt s)))))
  (criterium/quick-bench
    (render (str "{{bar" filter-chain "}}") {:bar "0"})))

(deftest ^:benchmark if-bench
  (println "BENCH: Many . acceses in an if clause")
  (criterium/quick-bench
    (render (string/join "" (repeat 1000 "{% if p.a.a.a.a %}{% endif %}"))
            {})))

(deftest ^:benchmark many-numeric-if-clauses-bench
  (println "BENCH: for loop with a numeric if clause in it")
  (reset! parser/templates {})
  (cache-on!)
  (render-file "templates/numerics.html" {:ps []})
  (criterium/quick-bench
    (render-file "templates/numerics.html" {:ps (repeat 10000 "x")})))

(deftest ^:benchmark many-any-if-clauses-bench
  (println "BENCH: for loop with a if any clause in it")
  (reset! parser/templates {})
  (cache-on!)
  (render-file "templates/any.html" {:products []})
  (criterium/quick-bench
    (render-file "templates/any.html" {:products (repeat 10000 {})})))
