(ns selmer.benchmark
  (:require [clojure.test :refer :all]
            [selmer.parser :refer :all]
            [criterium.core :as criterium]))

(def user (repeat 10 [{:name "test" }]))

(def nested-for-context {:users (repeat 10 user)})

(deftest ^:benchmark bench-site []
  (criterium/quick-bench
   (render-file "test/templates/nested-for.html"
                nested-for-context)))

