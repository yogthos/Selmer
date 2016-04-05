(ns selmer.filter-parser-test
  (:require [selmer.filter-parser :refer :all]
            [clojure.test :refer :all]))

(def pget-accessor #'selmer.filter-parser/get-accessor)

(deftest test-get-accessor
  (is (= 1 (pget-accessor [1] 0)))
  (is (= 1 (pget-accessor {:foo 1} :foo)))
  (is (= 1 (pget-accessor {"foo" 1} "foo")))
  (is (= 1 (reduce pget-accessor {:foo {:bar 1}} [:foo :bar])))
  (is (= 1 (reduce pget-accessor {:foo {"bar" 1}} [:foo :bar])))
  (is (= 1 (reduce pget-accessor {:foo {:bar [{"baz" 1}]}} [:foo :bar 0 :baz]))))
