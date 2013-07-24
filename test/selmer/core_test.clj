(ns selmer.core-test
  (:use clojure.test selmer.parser)
  (:import java.io.File))

(def path (str "test" File/separator "templates" File/separator))

(deftest custom-tags
  (render-string "[% for ele in foo %]<<[{ele}]>>[%endfor%]"
                 {:foo [1 2 3]}
                 {:tag-open \[
                  :tag-close \]}))

(deftest test-for
  (is (= (render-string "{% for ele in foo %}<<{{ele}}>>{%endfor%}"
                 {:foo [1 2 3]})
         "<<1>><<2>><<3>>"))
  (is (= (render-string "{% for ele in foo %}{{ele}}-{{forloop.counter}}-{{forloop.counter0}}-{{forloop.revcounter}}-{{forloop.revcounter0}};{%endfor%}"
                 {:foo [1 2 3]})
         "1-1-0-2-3;2-2-1-1-2;3-3-2-0-1;")))

(deftest nested-for-test
  (= "<html>\n<body>\n<ul>\n\n\t<li>\n\t\n\ttest\n\t\t</li>\n\n\t<li>\n\t\n\ttest1\n\t\t</li>\n\n</ul>\n</body>\n</html>"
    (render (parse (str path "nested-for.html")) 
            {:name "Bob" :users [[{:name "test" }] [{:name "test1" }]]})))

(deftest test-map-lookup
  (is (= (render-string "{{foo}}" {:foo {:bar 42}})
         "{:bar 42}"))
  (is (= (render-string "{{foo.bar}}" {:foo {:bar 42}})
         "42")))

(deftest render-test
  (= "<ul><li>0</li><li>1</li><li>2</li><li>3</li><li>4</li></ul>"
     (render (parse (java.io.StringReader. "<ul>{% for item in items %}<li>{{item}}</li>{% endfor %}</ul>"))
             {:items (range 5)})))

;;TODO add forloop.first forloop.last support to if
#_(deftest nested-forloop-first
  (is (= (render-string (str "{% for x in list1 %}"
                      "{% for y in list2 %}"
                      "{{x}}-{{y}}"
                      "{% if forloop.first %}'{% endif %} "
                      "{% endfor %}{% endfor %}")
                 {:list1 '[a b c]
                  :list2 '[1 2 3]})
         "a-1' a-2 a-3 b-1' b-2 b-3 c-1' c-2 c-3 ")))

(deftest forloop-with-one-element
  (is (= (render-string (str "{% for x in list %}"
                      "-{{x}}"
                      "{% endfor %}")
                 {:list '[a]})
         "-a")))

(deftest forloop-with-no-elements
  (is (= (render-string (str "before{% for x in list %}"
                      "-{{x}}"
                      "{% endfor %}after")
                 {:list '[]})
         "beforeafter")))

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

(deftest filter-upper
  (is (= "FOO" (render-string "{{f|upper}}" {:f "foo"}))))

;;TODO
#_(deftest filter-no-value
  (is (= "" (render-string "{{f|upper}}" {}))))

;;TODO
#_(deftest filter-date
  (is (= "1970-01-01_00:00:00"
         (render-string "{{f|date:\"yyyy-MM-dd_HH:mm:ss\"}}" {:f (java.util.Date. (long 0))}))))

;;TODO
#_(deftest filter-hash-md5
  (is (= "acbd18db4cc2f85cedef654fccc4a4d8"
         (render-string "{{f|hash:\"md5\"}}" {:f "foo"}))))

;;TODO
#_(deftest filter-hash-sha512
  (is (= (str "f7fbba6e0636f890e56fbbf3283e524c6fa3204ae298382d624741d"
              "0dc6638326e282c41be5e4254d8820772c5518a2c5a8c0c7f7eda19"
              "594a7eb539453e1ed7")
         (render-string "{{f|hash:\"sha512\"}}" {:f "foo"}))))

(deftest filter-hash-invalid-hash
  (is (thrown? Exception (render-string "{{f|hash:\"foo\"}}" {:f "foo"}))))


;;TODO
#_(deftest filter-count
  (is (= "3" (render-string "{{f|count}}" {:f "foo"})))
  (is (= "4" (render-string "{{f|count}}" {:f [1 2 3 4]})))
  (is (= "0" (render-string "{{f|count}}" {:f []})))
  (is (= "0" (render-string "{{f|count}}" {}))))

;;TODO
#_(deftest filter-pluralize
  (is (= "s" (render-string "{{f|pluralize}}" {:f []})))
  (is (= "" (render-string "{{f|pluralize}}" {:f [1]})))
  (is (= "s" (render-string "{{f|pluralize}}" {:f [1 2 3]})))

  (is (= "ies" (render-string "{{f|pluralize:\"ies\"}}" {:f []})))
  (is (= "" (render-string "{{f|pluralize:\"ies\"}}" {:f [1]})))
  (is (= "ies" (render-string "{{f|pluralize:\"ies\"}}" {:f [1 2 3]})))

  (is (= "ies" (render-string "{{f|pluralize:\"y,ies\"}}" {:f []})))
  (is (= "y" (render-string "{{f|pluralize:\"y,ies\"}}" {:f [1]})))
  (is (= "ies" (render-string "{{f|pluralize:\"y,ies\"}}" {:f [1 2 3]}))))

;;TODO
#_(deftest filter-to-json
  (is (= "1" (render-string "{{f|to-json}}" {:f 1})))
  (is (= "[1]" (render-string "{{f|to-json}}" {:f [1]})))
  (is (= "{&quot;foo&quot;:27,&quot;dan&quot;:&quot;awesome&quot;}"
         (render-string "{{f|to-json}}" {:f {:foo 27 :dan "awesome"}})))
  (is (= "{\"foo\":27,\"dan\":\"awesome\"}"
         (render-string "{{f|to-json|safe}}" {:f {:foo 27 :dan "awesome"}})))
  (is (= "{\"foo\":27,\"dan\":\"awesome\"}"
         (render-string "{{f|safe|to-json}}" {:f {:foo 27 :dan "awesome"}})))
  (is (= "null" (render-string "{{f|to-json}}" {}))))

;;TODO
#_(deftest filter-chaining
  (is (= "ACBD18DB4CC2F85CEDEF654FCCC4A4D8"
         (render-string "{{f|hash:\"md5\"|upper}}" {:f "foo"}))))

(deftest test-escaping
  (is (= "<tag>&lt;foo bar=&quot;baz&quot;&gt;\\&gt;</tag>"
         (render-string "<tag>{{f}}</tag>" {:f "<foo bar=\"baz\">\\>"})))
  ;;TODO?
  #_(is (= "&amp;&trade;&eacute;"
         (render-string "{{f}}" {:f "&™é"}))))

;;TODO
#_(deftest test-safe-filter
  (is (= "&lt;foo&gt;"
         (render-string "{{f}}" {:f "<foo>"})))
  (is (= "<foo>"
         (render-string "{{f|safe}}" {:f "<foo>"})))
  (is (= "<FOO>"
         (render-string "{{f|upper|safe}}" {:f "<foo>"})))
  (is (= "<FOO>"
         (render-string "{{f|safe|upper}}" {:f "<foo>"})))
  (is (= "<FOO>"
         (render-string "{{f|safe|upper|safe}}" {:f "<foo>"}))))
