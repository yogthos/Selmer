(ns selmer.core-test
  (:use clojure.test selmer.parser)
  (:import java.io.File))

(def path (str "test" File/separator "templates" File/separator))

(deftest test-for
  (is (= (render-string "{% for ele in foo %}<<{{ele}}>>{%endfor%}"
                 {:foo [1 2 3]})
         "<<1>><<2>><<3>>")))

(deftest nested-for-test
  (= "<html>\n<body>\n<ul>\n\n\t<li>\n\t\n\ttest\n\t\t</li>\n\n\t<li>\n\t\n\ttest1\n\t\t</li>\n\n</ul>\n</body>\n</html>"
    (render (parse (str path "nested-for.html")) 
            {:name "Bob" :users [[{:name "test" }] [{:name "test1" }]]})))

(deftest render-test
  (= "<ul><li>0</li><li>1</li><li>2</li><li>3</li><li>4</li></ul>"
     (render (parse (java.io.StringReader. "<ul>{% for item in items %}<li>{{item}}</li>{% endfor %}</ul>"))
             {:items (range 5)})))

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
     (render (parse (str path "if.html")) {:foo true :bar "test"}))
  
  (is (= (render-string "{% if foo %}foo is true{% endif %}" {:foo true})
         "foo is true"))
  (is (= (render-string "{% if foo %}foo is true{% endif %}" {:foo false})
         ""))
  (is (= (render-string "{% if foo %}foo is true{% else %}foo is false{% endif %}"
                 {:foo true})
         "foo is true"))
  (is (= (render-string "{% if foo %}foo is true{% else %}foo is false{% endif %}"
                 {:foo false})
         "foo is false"))
  
  (let [template
        (parse
          (java.io.StringReader.
            "{% if foo %}
             foo is true
             {% if bar %}bar is also true{% endif %}
             {% else %} foo is false
             {% if baz %}but baz is true {% else %}baz is also false{% endif %}
             {% endif %}"))]
    (is (= (render template {:foo true :bar true :baz false})
           "\n             foo is true\n             bar is also true\n             "))
    (is (= (render template {:foo false :bar true :baz false})
           " foo is false\n             baz is also false\n             "))
    (is (= (render template {:foo false :bar true :baz true})
           " foo is false\n             but baz is true \n             ")))
  (is (thrown? Exception (render-string "foo {% else %} bar" {}))))

(deftest test-if-not
  (is (= (render-string "{% if not foo %}foo is true{% endif %}" {:foo true})
         ""))
  (is (= (render-string "{% if not foo %}foo is true{% endif %}" {:foo false})
         "foo is true")))

(deftest test-nested-if
  (is (= (render-string (str "{% if foo %}before bar {% if bar %}"
                      "foo & bar are true"
                      "{% endif %} after bar{% endif %}")
                 {:foo true
                  :bar true})
         "before bar foo & bar are true after bar")))

(deftest ifequal-tag-test
  (= "\n<h1>equal!</h1>\n\n\n\n<p>not equal</p>\n"
     (render (parse (str path "ifequal.html")) {:foo "bar"}))
  (= "\n\n<h1>equal!</h1>\n\n\n<p>not equal</p>\n"
     (render (parse (str path "ifequal.html")) {:foo "baz" :bar "baz"}))
  (= "\n\n<h1>equal!</h1>\n\n\n<h1>equal!</h1>\n"
     (render (parse (str path "ifequal.html")) {:baz "test"}))
  (= "\n\n<h1>equal!</h1>\n\n\n<p>not equal</p>\n"
     (render (parse (str path "ifequal.html")) {:baz "fail"}))
  
  (is (= (render-string "{% ifequal foo \"foo\" %}yez{% endifequal %}" {:foo "foo"})
         "yez"))
  (is (= (render-string "{% ifequal foo \"foo\" bar %}yez{% endifequal %}"
                 {:foo "foo"
                  :bar "foo"})
         "yez"))
  (is (= (render-string "{% ifequal foo \"foo\" bar %}yez{% endifequal %}"
                 {:foo "foo"
                  :bar "bar"})
         ""))
  (is (= (render-string "{% ifequal foo \"foo\" %}foo{% else %}no foo{% endifequal %}"
                 {:foo "foo"})
         "foo"))
  (is (= (render-string "{% ifequal foo \"foo\" %}foo{% else %}no foo{% endifequal %}"
                 {:foo false})
         "no foo")))

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
