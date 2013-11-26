(ns selmer.tags
  (:require selmer.node
            [selmer.filter-parser :refer [compile-filter-body fix-accessor]]
            [selmer.filters :refer [filters]]
            [selmer.util :refer :all])
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


(defn apply-filters [item filter-names]
  (reduce
   (fn [value filter] ((compile-filter-body (str "value|" filter) false) {:value value}))
   item filter-names))

(defn for-handler [args tag-content render rdr]
  (let [content (tag-content rdr :for :empty :endfor)
        for-content (get-in content [:for :content])
        empty-content (get-in content [:empty :content])
        [ids [_ items]] (aggregate-args args)
        ids (map parse-arg ids)
        [items & filter-names] (if items (.split ^String items "\\|"))
        item-keys (parse-arg items)]
    (fn [context-map]
      (let [buf (StringBuilder.)
            items (-> (get-in context-map item-keys) (apply-filters filter-names))
            length (count items)
            parentloop (:parentloop context-map)]
        (if (and empty-content (empty? items))
          (.append buf (render empty-content context-map))
          (doseq [[counter value] (map-indexed vector items)]
            (let [loop-info
                  {:length length
                   :counter0 counter
                   :counter (inc counter)
                   :revcounter (- length (inc counter))
                   :revcounter0 (- length counter)
                   :first (= counter 0)
                   :last (= counter (dec length))}]
              (->> (assoc (create-value-mappings context-map ids value)
                          :forloop loop-info
                          :parentloop loop-info)
                (render for-content)
                (.append buf)))))
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
    nil     false
    ""      false
    "false" false
    false   false
    true))

(defn if-default-handler [[condition1 condition2] if-tags else-tags render]
  " Handler of if-condition tags. Expects conditions, enclosed
  tag-content, render boolean. Returns anonymous fn that will expect
  runtime context-map. (Separate from compile-time) "
  (let [not? (and condition1 condition2 (= condition1 "not"))
        condition (compile-filter-body (or condition2 condition1))]
    (fn [context-map]
      (let [condition (if-result (condition context-map))]
        (render-if render context-map (if not? (not condition) condition) if-tags else-tags)))))

(defn compare-numeric [op value arg-position]
  (let [value (java.lang.Double/parseDouble value)
        op (condp = op ">" > "<" < "=" == ">=" >= "<=" <=
             (exception "Unrecognized operator in 'if' statement: " op))]
    (if (= :first arg-position)
      #(op (java.lang.Double/parseDouble %) value)
      #(op value (java.lang.Double/parseDouble %)))))

(defn parse-numeric-params [p1 op p2]
  (if (re-matches #"[0-9]+" p1)
    [(compare-numeric op p1 :second) p2]
    [(compare-numeric op p2 :first) p1]))

(defn render-if-numeric [render negate? [comparator context-key] context-map if-tags else-tags]
  (render
    (let [value (not-empty ((compile-filter-body context-key) context-map))
          result (if value (comparator value) false)
          result (if negate? (not result) result)]
      (or (:content (if result if-tags else-tags))
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
            params    (if not? (drop 2 params) (rest params))]
        (render-if-any-all not? (if (= "any" op) some every?) params if-tags else-tags render))
      (< (count params) 3)
      (if-default-handler params if-tags else-tags render)
      :else
      (if-numeric-handler params if-tags else-tags render))))

(defn ifequal-handler [args tag-content render rdr]
  (let [tags (tag-content rdr :ifequal :else :endifequal)
        args (for [^String arg args]
               (if (= \" (first arg))
                 (.substring arg 1 (dec (.length arg)))
                 (compile-filter-body arg)))]
    (fn [context-map]
      (let [condition (apply = (map #(if (fn? %) (% context-map) %) args))]
        (render-if render context-map condition (:ifequal  tags) (:else tags))))))

(defn block-handler [args tag-content render rdr]
  (let [content (get-in (tag-content rdr :block :endblock) [:block :content])]
    (fn [context-map] (render content context-map))))

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

(defn parse-with [^String arg]
  (let [[id value] (.split arg "=")]
    [(keyword id) (compile-filter-body value)]))

(defn with-handler [args tag-content render rdr]
  (let [content (get-in (tag-content rdr :with :endwith) [:with :content])
        args (map parse-with args)]
    (fn [context-map] (render content
                              (reduce
                                (fn [context-map [k v]]
                                  (assoc context-map k (v context-map)))
                                context-map args)))))

(defn script-handler [[^String uri] _ _ _]
  (fn [{:keys [servlet-context]}]
    (str "<script src=\"" servlet-context (.substring uri 1) " type=\"text/javascript\"></script>")))

(defn style-handler [[^String uri] _ _ _]
  (fn [{:keys [servlet-context]}]
    (str "<link href=\"" servlet-context (.substring uri 1) " rel=\"stylesheet\" type=\"text/css\" />")))

(defn cycle-handler [args _ _ _]
  (let [fields (vec args)
        length (dec (count fields))
       i       (int-array [0])]
    (fn [_]
      (let [cur-i (aget i 0)
            val   (fields cur-i)]
        (aset i 0 (if (< cur-i length) (inc cur-i) 0))
        val))))

;; expr-tags are {% if ... %}, {% ifequal ... %},
;; {% for ... %}, and {% block blockname %}

(defonce expr-tags
  (atom {:if if-handler
         :ifequal ifequal-handler
         :for for-handler
         :block block-handler
         :cycle cycle-handler
         :now now-handler
         :comment comment-handler
         :firstof first-of-handler
         :verbatim verbatim-handler
         :with with-handler
         :script script-handler
         :style style-handler
         :extends nil
         :include nil}))

(defonce closing-tags
 (atom {:if       [:else :endif]
        :else     [:endif :endifequal]
        :ifequal  [:else :endifequal]
        :block    [:endblock]
        :for      [:endfor]
        :comment  [:endcomment]
        :verbatim [:endverbatim]}))

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
       (-> (fn [context-map]
             (render
               [(->> content (render-tags context-map) (handler args context-map) (TextNode.))]
               context-map)))
       (fn [context-map]
         (render [(TextNode. (handler args context-map))] context-map)))))
