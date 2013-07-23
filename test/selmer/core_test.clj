(ns selmer.core-test
  (:use clojure.test selmer.parser)
  (:import java.io.File))

(def path (str "test" File/separator "templates" File/separator))

(deftest nested-for-test
  (= "<html>\n<body>\n<ul>\n\n\t<li>\n\t\n\ttest\n\t\t</li>\n\n\t<li>\n\t\n\ttest1\n\t\t</li>\n\n</ul>\n</body>\n</html>"
    (render (parse (str path "nested-for.html")) 
            {:name "Bob" :users [[{:name "test" }] [{:name "test1" }]]})))

(deftest tag-info-test
  (= {:args ["i" "in" "nums"], :tag-name :for, :tag-type :expr}
     (read-tag-info (java.io.StringReader. "% for i in nums %}")))
  (= {:tag-value "nums", :tag-type :filter}
     (read-tag-info (java.io.StringReader. "{ nums }}"))))

(deftest if-tag-test
  (= "\n\n<h1>NOT BAR!</h1>\n"
     (render (parse (str path "if.html")) {:foo false}))
  (= "\n<h1>FOO!</h1>\n\n\n<h1>NOT BAR!</h1>\n"
     (render (parse (str path "if.html")) {:foo true}))
  (= "\n<h1>FOO!</h1>\n\n\n<h1>BAR!</h1>\n"
     (render (parse (str path "if.html")) {:foo true :bar "test"})))

(deftest filter-tag-test
  (= "ok"
     ((filter-tag {:tag-value "foo.bar.baz"}) {:foo {:bar {:baz "ok"}}}))
  (= "ok"
     ((filter-tag {:tag-value "foo"}) {:foo "ok"})))

(deftest tag-content-test
  (= {:endif [" baz"], :else ["foo bar "]} 
     (tag-content (java.io.StringReader. "foo bar {%else%} baz{% endif %}") :else :endif))
  (= {:endfor ["foo bar  baz"]}
    (tag-content (java.io.StringReader. "foo bar  baz{% endfor %}") :endfor)))


#_(spit "out.html" (render (parse (str path "nested-for.html")) {:name "Bob" :users [[{:name "test" }] [{:name "test1" }]]}))
#_(println (render (parse (str path "nested-for.html")) {:name "Bob" :users [[{:name "test" }] [{:name "test1" }]]}))

