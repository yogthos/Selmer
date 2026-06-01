(ns selmer.template-parser-test
  (:require [clojure.test :refer :all]
            [selmer.template-parser :refer :all]
            [selmer.util :as util]))

(defmacro with-default-patterns
  "Binds the dynamic tag/filter patterns the way `selmer.parser/parse` does so
  the pattern-dependent utilities can be exercised in isolation."
  [& body]
  `(binding [util/*tag-second-pattern*   (util/pattern util/*tag-second*)
             util/*filter-open-pattern*  (util/pattern "\\" util/*tag-open* "\\" util/*filter-open* "\\s*")
             util/*filter-close-pattern* (util/pattern "\\s*\\" util/*filter-close* "\\" util/*tag-close*)
             util/*tag-open-pattern*     (util/pattern "\\" util/*tag-open* "\\" util/*tag-second* "\\s*")
             util/*tag-close-pattern*    (util/pattern "\\s*\\" util/*tag-second* "\\" util/*tag-close*)]
     ~@body))

(deftest parse-defaults-test
  (is (nil? (parse-defaults nil)))
  (is (= {"foo" "bar"} (parse-defaults ["foo=\"bar\""])))
  (is (= {"foo" "bar" "baz" "quux"}
         (parse-defaults ["foo=\"bar\"" "baz=\"quux\""]))))

(deftest split-include-tag-test
  (with-default-patterns
    (is (= ["\"templates/foo.html\"" "with" "x=\"1\""]
           (split-include-tag "{% include \"templates/foo.html\" with x=\"1\" %}")))
    ;; backslashes are normalised to forward slashes
    (is (= ["\"templates/foo.html\""]
           (split-include-tag "{% include \"templates\\foo.html\" %}")))))

(deftest wrap-in-variable-tag-test
  (is (= "{{x}}" (wrap-in-variable-tag "x"))))

(deftest trim-regex-test
  (is (= "x" (trim-regex "{{x}}" #"\{\{" #"\}\}"))))

(deftest trim-variable-tag-test
  (with-default-patterns
    (is (= "x" (trim-variable-tag "{{x}}")))))

(deftest trim-expression-tag-test
  (with-default-patterns
    (is (= "if x" (trim-expression-tag "{% if x %}")))))

(deftest add-default-test
  (is (= "x|default:\"y\"" (add-default "x" "y"))))

(deftest try-add-default-test
  (is (= "x|default:\"y\"" (try-add-default "x" {"x" "y"})))
  (is (= "x" (try-add-default "x" {"z" "y"})))
  (is (= "x" (try-add-default "x" nil))))

(deftest to-expression-string-test
  (is (= "{%if x%}" (to-expression-string :if ["x"] nil)))
  (is (= "{%include \"foo.html\" with x=\"1\"%}"
         (to-expression-string :include ["\"foo.html\""] {"x" "1"}))))

(deftest add-defaults-to-variable-tag-test
  (with-default-patterns
    (is (= "{{x|default:\"y\"}}" (add-defaults-to-variable-tag "{{x}}" {"x" "y"})))
    (is (= "{{x}}" (add-defaults-to-variable-tag "{{x}}" {"z" "y"})))))

(deftest add-defaults-to-expression-tag-test
  (with-default-patterns
    (is (= "{%if x|default:\"y\"%}"
           (add-defaults-to-expression-tag "{% if x %}" {"x" "y"})))))
