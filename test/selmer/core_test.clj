(ns selmer.core-test
  (:use clojure.test selmer.parser selmer.template-parser selmer.tags selmer.util)
  (:import java.io.File))

(def path (str "test/templates" File/separator))

(deftest custom-handler-test
  (let [handler (tag-handler
                  (fn [args context-map content]
                    (get-in content [:foo :content]))
                  :foo :endfoo)]
    (is 
      (= "some bar content" 
         (render-template (parse (java.io.StringReader. "{% foo %}some {{bar}} content{% endfoo %}")
                        {:custom-tags {:foo handler}}) {:bar "bar"}))))
  
  (let [handler (tag-handler
                  (fn [args context-map] (clojure.string/join "," args))
                  :bar)]
    (is (= "arg1,arg2"
           (render-template (parse (java.io.StringReader. "{% bar arg1 arg2 %}")
                          {:custom-tags {:bar handler}}) {}))))
  
  (deftag :bar (fn [args context-map] (clojure.string/join "," args)))
  (render-template (parse (java.io.StringReader. "{% bar arg1 arg2 %}")) {}))

(deftest custom-filter-test
  (is (= "BAR"
         (render-template (parse (java.io.StringReader. "{{bar|embiginate}}")
                        {:custom-filters
                         {:embiginate (fn [^String s] (.toUpperCase s))}}) {:bar "bar"}))))

(deftest passthrough
  (let [s "a b c d"]
    (is (= s (render s {}))))
  (let [s "{{blah}} a b c d"]
    (is (= " a b c d" (render s {}))))
  (let [s "{{blah}} a b c d"]
    (is (= "blah a b c d" (render s {:blah "blah"}))))
  ;; Invalid tags are now ignored ;)
  (let [s "{a b c} \nd"]
    (is (= s (render s {})))))

(deftest inheritance
  (is
    (= "<html>\n    <head></head>\n    <body>\n        {% block hello %}\n\n            Hello \n         World\n{% endblock %}    </body>\n</html>"
       (preprocess-template "templates/inheritance/super-b.html")))
  (is
    (= "<html>\n    <head></head>\n    <body>\n        {% block hello %}\n\n            Hello \n        Cruel World\n{% endblock %}    </body>\n</html>"
       (preprocess-template "templates/inheritance/super-c.html")))  
  (is
    (= "start a\n{% block a %}{% endblock %}stop a\n\n{% block content %}{% endblock %}\nHello, {{name}}!\n"
       (preprocess-template "templates/inheritance/inherit-a.html")))
  (is
    (= "start a\n{% block a %}\nstart b\n{% block b %}{% endblock %}\nstop b\n{% endblock %}stop a\n\n{% block content %}content{% endblock %}\nHello, {{name}}!\n"
       (preprocess-template "templates/inheritance/inherit-b.html")))
  (is
    (= "start a\n{% block a %}\nstart b\n{% block b %}\nstart c\nstop c\n{% endblock %}stop b\n{% endblock %}stop a\n\n{% block content %}content{% endblock %}\nHello, {{name}}!\n"
      (preprocess-template "templates/inheritance/inherit-c.html")))
  (is
    (= "Base template.\n\n\t\n<p></p>\n"
       (render-file "templates/child.html" {})))
  (is
    (= "Base template.\n\n\t\n<p>blah</p>\n"
       (render-file "templates/child.html" {:content "blah"}))))

(deftest custom-tags
  (is
    (= "<<1>><<2>><<3>>"
       (render "[% for ele in foo %]<<[{ele}]>>[%endfor%]"
               {:foo [1 2 3]}
               {:tag-open \[
                :tag-close \]}))))

(deftest test-for
  (is (= (render "{% for ele in foo %}<<{{ele}}>>{%endfor%}"
                 {:foo [1 2 3]})
         "<<1>><<2>><<3>>"))
  (is (= (render "{% for ele in foo %}{{ele}}-{{forloop.counter}}-{{forloop.counter0}}-{{forloop.revcounter}}-{{forloop.revcounter0}};{%endfor%}"
                 {:foo [1 2 3]})
         "1-1-0-2-3;2-2-1-1-2;3-3-2-0-1;")))

(deftest nested-for-test
  (= "<html>\n<body>\n<ul>\n\n\t<li>\n\t\n\ttest\n\t\t</li>\n\n\t<li>\n\t\n\ttest1\n\t\t</li>\n\n</ul>\n</body>\n</html>"
    (render-template (parse (str path "nested-for.html"))
            {:name "Bob" :users [[{:name "test" }] [{:name "test1" }]]})))

(deftest test-map-lookup
  (is (= (render "{{foo}}" {:foo {:bar 42}})
         "{:bar 42}"))
  (is (= (render "{{foo.bar}}" {:foo {:bar 42}})
         "42")))

(deftest render-test
  (= "<ul><li>0</li><li>1</li><li>2</li><li>3</li><li>4</li></ul>"
     (render-template (parse (java.io.StringReader. "<ul>{% for item in items %}<li>{{item}}</li>{% endfor %}</ul>"))
             {:items (range 5)})))

(deftest nested-forloop-first
  (is (= (render (str "{% for x in list1 %}"
                             "{% for y in list2 %}"
                             "{{x}}-{{y}}"
                             "{% if forloop.first %}'{% endif %} "
                             "{% endfor %}{% endfor %}")
                        {:list1 '[a b c]
                         :list2 '[1 2 3]})
         "a-1' a-2 a-3 b-1' b-2 b-3 c-1' c-2 c-3 ")))

(deftest forloop-with-one-element
  (is (= (render (str "{% for x in list %}"
                      "-{{x}}"
                      "{% endfor %}")
                 {:list '[a]})
         "-a")))

(deftest forloop-with-no-elements
  (is (= (render (str "before{% for x in list %}"
                      "-{{x}}"
                      "{% endfor %}after")
                 {:list '[]})
         "beforeafter")))

(deftest tag-info-test
  (is
    (= {:args ["i" "in" "nums"], :tag-name :for, :tag-type :expr}
       (read-tag-info (java.io.StringReader. "% for i in nums %}"))))
  (is
    (= {:tag-value "nums", :tag-type :filter}
       (read-tag-info (java.io.StringReader. "{ nums }}")))))

(deftest if-tag-test
  (is
    (= "\n\n\n\n<h1>NOT BAR!</h1>\n\n\n\n\"bar\"\n\n\n\n\t\n\tinner\n\t\n"
       (render-template (parse (str path "if.html")) {:nested "x" :inner "y"})))
  
  (is
    (= "\n\n\n\n<h1>NOT BAR!</h1>\n\n\n\n\"foo\"\n\n\n"
       (render-template (parse (str path "if.html")) {:user-id "bob"})))  
  (is
    (= "\n\n\n\n<h1>NOT BAR!</h1>\n\n\n\n\"bar\"\n\n\n"
       (render-template (parse (str path "if.html")) {:foo false})))
  (is
    (= "\n<h1>FOO!</h1>\n\n\n\n\n<h1>NOT BAR!</h1>\n\n\n\n\"bar\"\n\n\n"
       (render-template (parse (str path "if.html")) {:foo true})))
  (is
    (= "\n<h1>FOO!</h1>\n\n\n\n\n<h1>BAR!</h1>\n\n\n\n\"bar\"\n\n\n"
       (render-template (parse (str path "if.html")) {:foo true :bar "test"})))

  (is
    (= " no value "
       (render "{% if user-id %} has value {% else %} no value {% endif %}"  {})))
  (is (= (render "{% if foo %}foo is true{% endif %}" {:foo true})
         "foo is true"))
  (is (= (render "{% if foo %}foo is true{% endif %}" {:foo false})
         ""))
  (is (= (render "{% if foo %}foo is true{% else %}foo is false{% endif %}"
                 {:foo true})
         "foo is true"))
  (is (= (render "{% if foo %}foo is true{% else %}foo is false{% endif %}"
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
    (is (= (render-template template {:foo true :bar true :baz false})
           "\n             foo is true\n             bar is also true\n             "))
    (is (= (render-template template {:foo false :bar true :baz false})
           " foo is false\n             baz is also false\n             "))
    (is (= (render-template template {:foo false :bar true :baz true})
           " foo is false\n             but baz is true \n             ")))
  (is (thrown? Exception (render "foo {% else %} bar" {}))))

(deftest test-if-not
  (is (= (render "{% if not foo %}foo is true{% endif %}" {:foo true})
         ""))
  (is (= (render "{% if not foo %}foo is true{% endif %}" {:foo false})
         "foo is true")))

(deftest test-nested-if
  (is (= (render (str "{% if foo %}before bar {% if bar %}"
                      "foo & bar are true"
                      "{% endif %} after bar{% endif %}")
                 {:foo true
                  :bar true})
         "before bar foo & bar are true after bar")))

(deftest ifequal-tag-test  
  (= "\n<h1>equal!</h1>\n\n\n\n<p>not equal</p>\n"
     (render-template (parse (str path "ifequal.html")) {:foo "bar"}))
  (= "\n\n<h1>equal!</h1>\n\n\n<p>not equal</p>\n"
     (render-template (parse (str path "ifequal.html")) {:foo "baz" :bar "baz"}))
  (= "\n\n<h1>equal!</h1>\n\n\n<h1>equal!</h1>\n"
     (render-template (parse (str path "ifequal.html")) {:baz "test"}))
  (= "\n\n<h1>equal!</h1>\n\n\n<p>not equal</p>\n"
     (render-template (parse (str path "ifequal.html")) {:baz "fail"}))

  (is (= (render "{% ifequal foo|upper \"FOO\" %}yez{% endifequal %}" {:foo "foo"})
         "yez"))
  
  (is (= (render "{% ifequal foo \"foo\" %}yez{% endifequal %}" {:foo "foo"})
         "yez"))
  (is (= (render "{% ifequal foo \"foo\" bar %}yez{% endifequal %}"
                 {:foo "foo"
                  :bar "foo"})
         "yez"))
  (is (= (render "{% ifequal foo \"foo\" bar %}yez{% endifequal %}"
                 {:foo "foo"
                  :bar "bar"})
         ""))
  (is (= (render "{% ifequal foo \"foo\" %}foo{% else %}no foo{% endifequal %}"
                 {:foo "foo"})
         "foo"))
  (is (= (render "{% ifequal foo \"foo\" %}foo{% else %}no foo{% endifequal %}"
                 {:foo false})
         "no foo")))

(deftest filter-tag-test
  (is
    (= "ok"
       ((filter-tag {:tag-value "foo.bar.baz"}) {:foo {:bar {:baz "ok"}}})))
  (is
    (= "ok"
       ((filter-tag {:tag-value "foo"}) {:foo "ok"}))))

(deftest tag-content-test
  (is
    (= {:if {:args nil :content ["foo bar "]}
        :else {:args nil :content [" baz"]}}
       (into {}
             (map
               (fn [[k v]]
                 [k (update-in v [:content]  #(map (fn [node] (.render-node node {})) %))])
               (tag-content (java.io.StringReader. "foo bar {%else%} baz{% endif %}") :if :else :endif)))))
  (is
    (= {:for {:args nil, :content ["foo bar  baz"]}}
       (update-in (tag-content (java.io.StringReader. "foo bar  baz{% endfor %}") :for :endfor)
                  [:for :content 0] #(.render-node % {})))))

(deftest filter-upper
  (is (= "FOO" (render "{{f|upper}}" {:f "foo"}))))

;; How do we handle nils ?
;; nils should return empty strings at the point of injection in a DTL library. - cma
(deftest filter-no-value
  (is (= "" (render "{{f|upper}}" {}))))

(deftest filter-date
  (let [date (java.util.Date.)]
    (is (= (.format (java.text.SimpleDateFormat. "yyyy-MM-dd_HH:mm:ss") date)
           (render "{{f|date:\"yyyy-MM-dd_HH:mm:ss\"}}" {:f date})))))

(deftest filter-hash-md5
  (is (= "acbd18db4cc2f85cedef654fccc4a4d8"
         (render "{{f|hash:\"md5\"}}" {:f "foo"}))))

(deftest filter-hash-sha512
  (is (= (str "f7fbba6e0636f890e56fbbf3283e524c6fa3204ae298382d624741d"
              "0dc6638326e282c41be5e4254d8820772c5518a2c5a8c0c7f7eda19"
              "594a7eb539453e1ed7")
         (render "{{f|hash:\"sha512\"}}" {:f "foo"}))))

(deftest filter-hash-invalid-hash
  (is (thrown? Exception (render "{{f|hash:\"foo\"}}" {:f "foo"}))))


(deftest filter-count
  (is (= "3" (render "{{f|count}}" {:f "foo"})))
  (is (= "4" (render "{{f|count}}" {:f [1 2 3 4]})))
  (is (= "0" (render "{{f|count}}" {:f []})))
  (is (= "0" (render "{{f|count}}" {}))))

;; switched commas + doublequotes for colons
;; TODO - maybe remain consistent with django's only 1 argument allowed.
;; I like being able to accept multiple arguments.
;; Alternatively, we could have curried filters and just chain
;; it into a val and apply it Haskell-style.
;; I think that could surprise users. (which is bad)
(deftest filter-pluralize
  (is (= "s" (render "{{f|pluralize}}" {:f []})))
  (is (= "" (render "{{f|pluralize}}" {:f [1]})))
  (is (= "s" (render "{{f|pluralize}}" {:f [1 2 3]})))

  (is (= "ies" (render "{{f|pluralize:\"ies\"}}" {:f []})))
  (is (= "" (render "{{f|pluralize:\"ies\"}}" {:f [1]})))
  (is (= "ies" (render "{{f|pluralize:\"ies\"}}" {:f [1 2 3]})))

  (is (= "ies" (render "{{f|pluralize:y:ies}}" {:f []})))
  (is (= "y" (render "{{f|pluralize:y:ies}}" {:f [1]})))
  (is (= "ies" (render "{{f|pluralize:y:ies}}" {:f [1 2 3]})))

  (is (= "s" (render "{{f|pluralize}}" {:f 0})))
  (is (= "" (render "{{f|pluralize}}" {:f 1})))
  (is (= "s" (render "{{f|pluralize}}" {:f 3})))

  (is (= "ies" (render "{{f|pluralize:\"ies\"}}" {:f 0})))
  (is (= "" (render "{{f|pluralize:\"ies\"}}" {:f 1})))
  (is (= "ies" (render "{{f|pluralize:\"ies\"}}" {:f 3})))

  (is (= "ies" (render "{{f|pluralize:y:ies}}" {:f 0})))
  (is (= "y" (render "{{f|pluralize:y:ies}}" {:f 1})))
  (is (= "ies" (render "{{f|pluralize:y:ies}}" {:f 3}))))

;; to-json is simply json here
(deftest filter-to-json
  (is (= "1" (render "{{f|json}}" {:f 1})))
  (is (= "[1]" (render "{{f|json}}" {:f [1]})))
  (is (= "{&quot;foo&quot;:27,&quot;dan&quot;:&quot;awesome&quot;}"
         (render "{{f|json}}" {:f {:foo 27 :dan "awesome"}})))
  (is (= "{\"foo\":27,\"dan\":\"awesome\"}"
         (render "{{f|json|safe}}" {:f {:foo 27 :dan "awesome"}})))
  ;; safe only works at the end
  #_(is (= "{\"foo\":27,\"dan\":\"awesome\"}"
         (render "{{f|safe|json}}" {:f {:foo 27 :dan "awesome"}})))
  ;; Do we really want to nil-pun the empty map?
  ;; Is that going to surprise the user?
  (is (= "null" (render "{{f|json}}" {}))))

;; TODO
(deftest filter-chaining
  (is (= "ACBD18DB4CC2F85CEDEF654FCCC4A4D8"
         (render "{{f|hash:\"md5\"|upper}}" {:f "foo"}))))

(deftest test-escaping
  (is (= "<tag>&lt;foo bar=&quot;baz&quot;&gt;\\&gt;</tag>"
         (render "<tag>{{f}}</tag>" {:f "<foo bar=\"baz\">\\>"})))
  ;; Escapes the same chars as django's escape
  (is (= "&amp;&quot;&#39;&lt;&gt;"
         (render "{{f}}" {:f "&\"'<>"}))))

;; Safe only works at the end.
;; Don't think it should work anywhere else :-) - cbp (agreed, - cma)
(deftest test-safe-filter
  (is (= "&lt;foo&gt;"
         (render "{{f}}" {:f "<foo>"})))
  (is (= "<foo>"
         (render "{{f|safe}}" {:f "<foo>"})))
  (is (= "<FOO>"
         (render "{{f|upper|safe}}" {:f "<foo>"})))
  #_(is (= "<FOO>"
         (render "{{f|safe|upper}}" {:f "<foo>"})))
  #_(is (= "<FOO>"
         (render "{{f|safe|upper|safe}}" {:f "<foo>"}))))
