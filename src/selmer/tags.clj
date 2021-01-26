(ns selmer.tags
  (:require
    selmer.node
    [selmer.filter-parser :refer [safe-filter compile-filter-body get-accessor]]
    [selmer.filters :refer [filters]]
    [selmer.util :refer :all]
    [json-html.core :refer [edn->html]])
  (:import [selmer.node TextNode]))

;; A tag can modify the context map for its body
;; It has full control of its body which means that it has to
;; take care of its compilation.

(defn create-value-mappings [context-map ids value]
  (if (= 1 (count ids))
    (assoc-in context-map (first ids) value)
    (reduce
      (fn [m [path value]] (assoc-in m path value))
      context-map (map vector ids value))))

(defn aggregate-args [args]
  (->> args
       (map #(.split ^String % ","))
       (apply concat)
       (split-with (partial not= "in"))))

(defn compile-filters [items filter-names]
  (map #(compile-filter-body (str items "|" %) false) filter-names))

(defn apply-filters [item filters context-map items]
  (reduce
    (fn [value filter]
      (filter (assoc context-map
                (keyword items) value
                (name items) value)))
    item filters))

(defn for-handler [args tag-content render rdr]
  (let [content       (tag-content rdr :for :empty :endfor)
        for-content   (get-in content [:for :content])
        empty-content (get-in content [:empty :content])
        [ids [_ items]] (aggregate-args args)
        ids           (map parse-accessor ids)
        [items & filter-names] (if items (.split ^String items "\\|"))
        filters       (compile-filters items filter-names)
        item-keys     (parse-accessor items)]
    (fn [context-map]
      (let [buf              (StringBuilder.)
            unfiltered-items (reduce get-accessor context-map item-keys)]

        (if (and (nil? unfiltered-items) (not empty-content))
          ;item was not in the context map and it didn't have an {% empty %} fallback
          (.append buf (*missing-value-formatter* {:tag-name :for :args item-keys} context-map))
          ;item was in context map, keep going
          (let [items  (apply-filters unfiltered-items filters context-map items)
                length (count items)]
            (if (and empty-content (empty? items))
              (.append buf (render empty-content context-map))
              (let [item-mappings (map #(create-value-mappings context-map ids %) items)]
                (doseq [[counter [value previous]]
                        (map-indexed vector
                                     (map vector item-mappings (cons nil item-mappings)))]
                  (let [loop-info
                        {:length      length
                         :counter0    counter
                         :counter     (inc counter)
                         :revcounter  (- length (inc counter))
                         :revcounter0 (- length counter)
                         :first       (= counter 0)
                         :last        (= counter (dec length))
                         :parentloop  (:forloop context-map)
                         :previous    previous}]
                    (->> (assoc value :forloop loop-info)
                         (render for-content)
                         (.append buf))))))))
        (.toString buf)))))

(defn render-if [render context-map condition first-block second-block]
  (render
    (cond
      (and condition first-block)
      (:content first-block)

      (and (not condition) first-block)
      (:content second-block)

      condition
      (:content second-block)

      :else [(TextNode. "")])
    context-map))

(defn if-result [value]
  (condp = value
    nil false
    "" false
    "false" false
    false false
    true))

(defn match-comparator [op]
  (condp = op ">" > "<" < "=" == ">=" >= "<=" <=
              (exception "Unrecognized operator in 'if' statement: " op)))

(defn- num? [v]
  (and v (re-matches #"[0-9]*\.?[0-9]+" v)))

(defn- parse-double [v]
  (java.lang.Double/parseDouble v))

(defn parse-numeric-params [p1 op p2]
  (let [comparator (match-comparator op)]
    (cond
      (and (not (num? p1)) (not (num? p2)))
      [#(comparator (parse-double %1) (parse-double %2)) p1 p2]

      (num? p1)
      [#(comparator (parse-double p1) (parse-double %)) nil p2]

      (num? p2)
      [#(comparator (parse-double %) (parse-double p2)) p1 nil])))

(defn numeric-expression-evaluation [[comparator context-key1 context-key2]]
  ; Parse the filter bodies first and close over them.
  ; This makes them cached.
  (let [l (when context-key1 (compile-filter-body context-key1))
        r (when context-key2 (compile-filter-body context-key2))]
    (fn [context-map]
      (let [value1 (when context-key1 (not-empty (l context-map)))
            value2 (when context-key2 (not-empty (r context-map)))]
        (cond
          (and value1 value2)
          (comparator value1 value2)

          value1
          (comparator value1)

          value2
          (comparator value2))))))

(defn if-any-all-fn [op params]
  ; op is either the function "some" or "any"
  (let [filters (map compile-filter-body params)]
    (fn if-any-all-runtime-test [context-map]
      ; We want to short-circuit here, in case
      ; the first arg is true for ANY, or false for ALL.
      (op (fn [f] (-> context-map (f) (if-result)))
          filters))))

(defn parse-eq-arg [^String arg-string]
  (cond
    (= \" (first arg-string))
    (.substring arg-string 1 (dec (.length arg-string)))

    (= \: (first arg-string))
    arg-string

    (num? arg-string)
    arg-string

    :else
    (compile-filter-body arg-string)))

(defn if-condition-fn
  "Compiles an if form into a function that takes a context-map, and returns true or false."
  [params]
  (let [negate  (= "not" (first params))
        params  (if negate (rest params)
                           params)
        eval-fn (cond
                  ; just a normal, single argument if.
                  (= (count params) 1)
                  (compile-filter-body (first params))

                  ; the any/all version, like {% if any a b c %}
                  (#{"any" "all"} (first params))
                  (let [op     (first params)
                        params (rest params)]
                    (if-any-all-fn (if (= "any" op) some every?) params))

                  ; Do an equals comparison, for instance with a string comparison
                  (and (= 3 (count params)) (= (second params) "="))
                  (let [[p1 _ p3] params]
                    (fn [context-map]
                      (let [lookup-if-needed (fn [arg] (if (fn? arg)
                                                         (arg context-map)
                                                         arg))
                            a                (lookup-if-needed (parse-eq-arg p1))
                            b                (lookup-if-needed (parse-eq-arg p3))]
                        (if (and (num? a) (num? b))
                          ; Special case for when both are numbers -
                          ; since we want 2 = 2.0 to be true and in clojure (= 2 2.0) => false
                          (== (parse-double a)
                              (parse-double b))
                          (= a b)))))

                  ; it has to be a numeric expression like 1 > 2
                  (= 3 (count params))
                  (let [[p1 p2 p3] params]
                    (numeric-expression-evaluation (parse-numeric-params p1 p2 p3))))]
    (if negate
      (fn if-cond-fn-negated [context-map] (not (if-result (eval-fn context-map))))
      (fn if-cond-fn [context-map] (if-result (eval-fn context-map))))))

(defn compare-tag [args comparator render success failure]
  (fn [context-map]
    (let [condition (apply comparator (map #(if (fn? %) (% context-map) %) args))]
      (render-if render context-map condition success failure))))

(defn if-handler [params tag-content render rdr]
  ; The main idea of this function is to generate a list of test conditions and corresponding content,
  ; then going though them in order until a test is successful, and then returning the contents belonging to
  ; that test.

  ; tag-content is key here as it's in charge of parsing the template.
  ; The rest just renders out based on what it generates
  (let [{if-tags :if elif-tags-list :elif else-tags :else} (tag-content rdr :if :elif :else :endif)
        ; Conditions is a list of tests with their corresponding content.
        ; First comes the if clause
        conditions (->> [{:args params :content (:content if-tags)}
                         ; then any elifs
                         elif-tags-list
                         ; then the else clause. The "test" of the else clause just always returns true.
                         (when else-tags (assoc else-tags :args ["\"true\""]))]
                        ; Remove a hole created if there is no else clause.
                        (filter identity)
                        ; Unnest the elifs
                        (flatten)
                        ; Compile the args into a test function
                        (map (fn [{args :args content :content}]
                               {:test    (if-condition-fn args)
                                :content content})))]

    ; Returns anonymous fn that will expect runtime context-map. (Separate from compile-time).
    (fn render-if [context-map]
      (let [content-to-use (:content (ffind (fn [{test :test}] (test context-map))
                                            conditions))]
        (render content-to-use context-map)))))

(defn parse-eq-args [args]
  (for [^String arg args]
    (parse-eq-arg arg)))


(defn ifequal-handler [args tag-content render rdr]
  (let [{:keys [ifequal else]} (tag-content rdr :ifequal :else :endifequal)
        args (parse-eq-args args)]
    (compare-tag args = render ifequal else)))

(defn ifunequal-handler [args tag-content render rdr]
  (let [{:keys [ifunequal else]} (tag-content rdr :ifunequal :else :endifunequal)
        args (parse-eq-args args)]
    (compare-tag args not= render ifunequal else)))

(defn block-handler [args tag-content render rdr]
  (let [content (get-in (tag-content rdr :block :endblock) [:block :content])]
    (fn [context-map] (render content context-map))))

(defn sum-handler [args _ _ _]
  (fn [context-map]
    (reduce + (map (fn [val]
                     (let [accessor (parse-accessor val)]
                       (get-in context-map accessor))) args))))

(defn now-handler [args _ _ _]
  (fn [context-map]
    ((:date @filters) (java.util.Date.) (clojure.string/join " " args))))

(defn comment-handler [args tag-content render rdr]
  (let [content (tag-content rdr :comment :endcomment)]
    (fn [_]
      (render (filter (partial instance? selmer.node.TextNode) content) {}))))

(defn first-of-handler [args _ _ _]
  (let [args (map compile-filter-body args)]
    (fn [context-map]
      (let [first-true (->> args (map #(% context-map)) (remove empty?) (drop-while false?) first)]
        (or first-true "")))))

(defn read-verbatim [rdr]
  (->buf [buf]
         (loop [ch (read-char rdr)]
           (when ch
             (cond
               (open-tag? ch rdr)
               (let [tag (read-tag-content rdr)]
                 (if-not (re-matches #"\{\%\s*endverbatim\s*\%\}" tag)
                   (do (.append buf tag)
                       (recur (read-char rdr)))))
               :else
               (do
                 (.append buf ch)
                 (recur (read-char rdr))))))))

(defn verbatim-handler [args _ render rdr]
  (let [content (read-verbatim rdr)]
    (fn [context-map] content)))

(defn compile-args [args]
  (when-not (even? (count args))
    (exception "invalid arguments passed to 'with' tag: " args))
  (for [[id value] (partition 2 args)]
    [(map keyword (clojure.string/split id #"\.")) (compile-filter-body value false)]))

(defn with-handler [args tag-content render rdr]
  (let [content (get-in (tag-content rdr :with :endwith) [:with :content])
        args    (->> args
                     ; Split on = signs, but preserving those = signs that occur inside quotes.
                     ; Inspiration from https://stackoverflow.com/a/24450127/1568714
                     (mapcat #(re-seq #"\"[^\"]*\"|[^= ]+" %))
                     (remove nil?)
                     (compile-args))]
    (fn [context-map]
      (render content
              (reduce
               (fn [context-map [k v]]
                  (assoc-in context-map k (v context-map)))
                context-map args)))))

(defn- build-uri-for-script-or-style-tag
  "Accepts `uri` passed in as first argument to {% script %} or {% style %} tag
  and context map. Returns string - new URI built with value of
  `selmer/context` context parameter in mind. `uri` can be a string literal or
  name of context parameter (filters also supported)."
  [^String uri {context :selmer/context :as context-map}]
  (let [literal? (and (.startsWith uri "\"") (.endsWith uri "\""))
        uri
                 (if literal?
                   (.replace uri "\"" "")                   ; case of {% style "/css/foo.css" %}
                   (-> uri                                  ; case of {% style context-param|some-filter:arg1:arg2 %}
                       (compile-filter-body)
                       (apply [context-map])))]
    (-> context (str uri) (.replace "//" "/"))))

(defn script-handler
  "Returns function that renders HTML `<SCRIPT/>` tag. Accepts `uri` that would
  be used to build value for 'src' attribute of generated tag and variable
  number of optional arguments. Value for 'src' attribute is built accounting
  value of `selmer/context` context parameter and `uri` can be a string literal
  or name of context parameter (filters also supported). Optional arguments are:
  * `async` - when evaluates to logical true then 'async' attribute would be
    added to generated tag."
  [[^String uri & args] _ _ _]
  (let [args
        (->> args
             (mapcat #(.split ^String % "="))
             (remove #{"="})
             (compile-args))]
    (fn [context-map]
      (let [args
                         (reduce
                           (fn [context-map [k v]]
                             (assoc-in context-map k (v context-map)))
                           context-map
                           args)
            async-attr   (when (:async args) "async ")
            src-attr-val (build-uri-for-script-or-style-tag uri context-map)]
        (str "<script " async-attr "src=\"" src-attr-val "\" type=\"application/javascript\"></script>")))))

(defn style-handler
  "Returns function that renders HTML `<LINK/>` tag. Accepts `uri` that would
  be used to build value for 'href' attribute of generated tag. Value for 'href'
  attribute is built accounting value of `selmer/context` context parameter and
  `uri` can be a string literal or name of context parameter (filters also
  supported)."
  [[^String uri] _ _ _]
  (fn [context-map]
    (let [href-attr-val (build-uri-for-script-or-style-tag uri context-map)]
      (str "<link href=\"" href-attr-val "\" rel=\"stylesheet\" type=\"text/css\" />"))))

(defn cycle-handler [args _ _ _]
  (let [fields (vec args)
        length (dec (count fields))
        i      (int-array [0])]
    (fn [_]
      (let [cur-i (aget i 0)
            val   (fields cur-i)]
        (aset i 0 (if (< cur-i length) (inc cur-i) 0))
        val))))

(defn safe-handler [args tag-content render rdr]
  (let [content (get-in (tag-content rdr :safe :endsafe) [:safe :content])]
    (fn [context-map]
      (render content (assoc context-map safe-filter true)))))

(defn debug-handler [_ _ _ _]
  (fn [context-map]
    (str
      "<style>"
      (-> "json.human.css" clojure.java.io/resource slurp)
      "</style>"
      (edn->html context-map))))

;; expr-tags are {% if ... %}, {% ifequal ... %},
;; {% for ... %}, and {% block blockname %}

(defonce expr-tags
         (atom {:if        if-handler
                :ifequal   ifequal-handler
                :ifunequal ifunequal-handler
                :sum       sum-handler
                :for       for-handler
                :block     block-handler
                :cycle     cycle-handler
                :now       now-handler
                :comment   comment-handler
                :firstof   first-of-handler
                :verbatim  verbatim-handler
                :with      with-handler
                :script    script-handler
                :style     style-handler
                :safe      safe-handler
                :debug     debug-handler
                :extends   nil
                :include   nil}))

; For each tag, does it have any follow-up tags that are part of the same tag construct? If so it goes here.
(defonce closing-tags
         (atom {:if        [:elif :else :endif]
                :elif      [:elif :else :endif]
                :else      [:endif :endifequal :endifunequal]
                :ifequal   [:else :endifequal]
                :ifunequal [:else :endifunequal]
                :block     [:endblock]
                :for       [:empty :endfor]
                :empty     [:endfor]
                :comment   [:endcomment]
                :safe      [:endsafe]
                :verbatim  [:endverbatim]
                :with      [:endwith]}))

;;helpers for custom tag definition
(defn render-tags [context-map tags]
  (into {}
        (for [[tag content] tags]
          [tag
           (update-in content [:content]
                      (fn [^selmer.node.INode node]
                        (clojure.string/join (map #(.render-node ^selmer.node.INode % context-map) node))))])))

(defn tag-handler [handler & tags]
  (fn [args tag-content render rdr]
    (if-let [content (if (> (count tags) 1) (apply (partial tag-content rdr) tags))]
      (fn [context-map]
        (render
          [(->> content (render-tags context-map) (handler args context-map) (TextNode.))]
          context-map))
      (fn [context-map]
        (handler args context-map)))))
