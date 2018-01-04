(ns selmer.tags
  (:require selmer.node
            [selmer.filter-parser :refer [split-filter-val safe-filter compile-filter-body fix-accessor get-accessor]]
            [selmer.filters :refer [filters]]
            [selmer.util :refer :all]
            [json-html.core :refer [edn->html]])
  (:import [selmer.node INode TextNode FunctionNode]))

;; A tag can modify the context map for its body
;; It has full control of its body which means that it has to
;; take care of its compilation.
(defn parse-arg [^String arg]
  (fix-accessor (.split arg "\\.")))

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
        ids           (map parse-arg ids)
        [items & filter-names] (if items (.split ^String items "\\|"))
        filters       (compile-filters items filter-names)
        item-keys     (parse-arg items)]
    (fn [context-map]
      (let [buf    (StringBuilder.)
            unfiltered-items (reduce get-accessor context-map item-keys)]

        (if (and (nil? unfiltered-items) (not empty-content))
          ;item was not in the context map and it didn't have an {% empty %} fallback
          (.append buf (*missing-value-formatter* {:tag-name :for :args item-keys} context-map))
          ;item was in context map, keep going
          (let [items  (apply-filters unfiltered-items filters context-map items)
                length (count items)]
            (if (and empty-content (empty? items))
              (.append buf (render empty-content context-map))
              (doseq [[counter value] (map-indexed vector items)]
                (let [loop-info
                      {:length      length
                       :counter0    counter
                       :counter     (inc counter)
                       :revcounter  (- length (inc counter))
                       :revcounter0 (- length counter)
                       :first       (= counter 0)
                       :last        (= counter (dec length))}]
                  (->> (assoc (create-value-mappings context-map ids value)
                         :forloop loop-info
                         :parentloop loop-info)
                       (render for-content)
                       (.append buf)))))))
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

(defn if-default-handler [[condition1 condition2] if-tags else-tags render]
  " Handler of if-condition tags. Expects conditions, enclosed
  tag-content, render boolean. Returns anonymous fn that will expect
  runtime context-map. (Separate from compile-time) "
  (let [not?      (and condition1 condition2 (= condition1 "not"))
        condition (compile-filter-body (or condition2 condition1))]
    (fn [context-map]
      (let [condition (if-result (condition context-map))]
        (render-if render context-map (if not? (not condition) condition) if-tags else-tags)))))

(defn match-comparator [op]
  (condp = op ">" > "<" < "=" == ">=" >= "<=" <=
              (exception "Unrecognized operator in 'if' statement: " op)))

(defn- num? [v]
  (re-matches #"[0-9]*\.?[0-9]+" v))

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

(defn render-if-numeric [render negate? [comparator context-key1 context-key2] context-map if-tags else-tags]
  (render
    (let [[value1 value2]
          (cond
            (and context-key1 context-key2)
            [(not-empty ((compile-filter-body context-key1) context-map))
             (not-empty ((compile-filter-body context-key2) context-map))]
            context-key1
            [(not-empty ((compile-filter-body context-key1) context-map))]
            context-key2
            [(not-empty ((compile-filter-body context-key2) context-map))])
          result (cond
                   (and value1 value2)
                   (comparator value1 value2)
                   value1
                   (comparator value1)
                   value2
                   (comparator value2))]
      (or (:content (if (if negate? (not result) result) if-tags else-tags))
          [(TextNode. "")]))
    context-map))

(defn if-numeric-handler [[p1 p2 p3 p4 :as params] if-tags else-tags render]
  (cond
    (and p4 (not= p1 "not"))
    (exception "invalid params for if-tag: " params)

    (= "not" p1)
    #(render-if-numeric render true (parse-numeric-params p2 p3 p4) % if-tags else-tags)

    :else
    #(render-if-numeric render false (parse-numeric-params p1 p2 p3) % if-tags else-tags)))

(defn render-if-any-all [not? op params if-tags else-tags render]
  (let [filters (map compile-filter-body params)]
    (fn [context-map]
      (render-if
        render
        context-map
        (let [test (op #{true} (map #(if-result (% context-map)) filters))]
          (if not? (not test) test))
        if-tags else-tags))))

(defn if-handler [params tag-content render rdr]
  (let [{if-tags :if else-tags :else} (tag-content rdr :if :else :endif)]
    (cond
      (some #{"any" "all"} (take 2 params))
      (let [[not? op] (if (= "not" (first params))
                        [true (second params)]
                        [false (first params)])
            params (if not? (drop 2 params) (rest params))]
        (render-if-any-all not? (if (= "any" op) some every?) params if-tags else-tags render))
      (< (count params) 3)
      (if-default-handler params if-tags else-tags render)
      :else
      (if-numeric-handler params if-tags else-tags render))))

(defn compare-tag [args comparator render success failure]
  (fn [context-map]
    (let [condition (apply comparator (map #(if (fn? %) (% context-map) %) args))]
      (render-if render context-map condition success failure))))

(defn parse-eq-args [args]
  (for [^String arg args]
    (cond
      (= \" (first arg))
      (.substring arg 1 (dec (.length arg)))

      (= \: (first arg))
      arg

      :else
      (compile-filter-body arg))))

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
                     (let [accessor (split-filter-val val)]
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
    [(keyword id) (compile-filter-body value false)]))

(defn with-handler [args tag-content render rdr]
  (let [content (get-in (tag-content rdr :with :endwith) [:with :content])
        args    (->> args
                     (mapcat #(.split ^String % "="))
                     (remove #{"="})
                     (compile-args))]
    (fn [context-map]
      (render content
              (reduce
                (fn [context-map [k v]]
                  (assoc context-map k (v context-map)))
                context-map args)))))

(defn- build-uri-for-script-or-style-tag
  "Accepts `uri` passed in as first argument to {% script %} or {% style %} tag
  and context map. Returns string - new URI built with value of
  `servlet-context` context parameter in mind. `uri` can be a string literal or
  name of context parameter (filters also supported)."
  [^String uri {:keys [servlet-context] :as context-map}]
  (let [literal? (and (.startsWith uri "\"") (.endsWith uri "\""))
        uri
        (if literal?
          (.replace uri "\"" "")     ; case of {% style "/css/foo.css" %}
          (-> uri                    ; case of {% style context-param|some-filter:arg1:arg2 %}
              (compile-filter-body)
              (apply [context-map])))]
    (-> servlet-context (str uri) (.replace "//" "/"))))

(defn script-handler
  "Returns function that renders HTML `<SCRIPT/>` tag. Accepts `uri` that would
  be used to build value for 'src' attribute of generated tag and variable
  number of optional arguments. Value for 'src' attribute is built accounting
  value of `servlet-context` context parameter and `uri` can be a string literal
  or name of context parameter (filters also supported). Optional arguments are:
  * `async` - when evaluates to logical true then 'async' attribute would be
    added to generated tag."
  [[^String uri & args] _ _ _]
  (let [args
        (->> args
             (mapcat #(.split ^String % "="))
             (remove #{"="})
             (compile-args))]
    (fn [{:keys [servlet-context] :as context-map}]
      (let [args
            (reduce
             (fn [context-map [k v]]
               (assoc context-map k (v context-map)))
             context-map
             args)
            async-attr (when (:async args) "async ")
            src-attr-val (build-uri-for-script-or-style-tag uri context-map)]
        (str "<script " async-attr "src=\"" src-attr-val "\" type=\"text/javascript\"></script>")))))

(defn style-handler
  "Returns function that renders HTML `<LINK/>` tag. Accepts `uri` that would
  be used to build value for 'href' attribute of generated tag. Value for 'href'
  attribute is built accounting value of `servlet-context` context parameter and
  `uri` can be a string literal or name of context parameter (filters also
  supported)."
  [[^String uri] _ _ _]
  (fn [{:keys [servlet-context] :as context-map}]
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

(defonce closing-tags
         (atom {:if        [:else :endif]
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
