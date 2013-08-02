(ns selmer.tags
  (:require selmer.node
            [selmer.filter-parser :refer [compile-filter-body]]
            [selmer.filters :refer [filters]]
            [selmer.util :refer :all])
  (:import [selmer.node INode TextNode FunctionNode]))

;; A tag can modify the context map for its body
;; It has full control of its body which means that it has to
;; take care of its compilation.
(defn parse-arg [^String arg]  
  (map keyword (.split arg "\\.")))

(defn for-handler [[^String id _ ^String items] tag-content render rdr]
  (let [content (tag-content rdr :for :empty :endfor)
        for-content (get-in content [:for :content])
        empty-content (get-in content [:empty :content])
        id (parse-arg id)
        item-keys (parse-arg items)]
    (fn [context-map]
      (let [buf (StringBuilder.)
            items (get-in context-map item-keys)
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
              (->> (assoc (assoc-in context-map id value)
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

(defn if-handler [[condition1 condition2] tag-content render rdr]
  " Handler of if-condition tags. Expects conditions, enclosed
  tag-content, render boolean. Returns anonymous fn that will expect
  runtime context-map. (Separate from compile-time) "
  (let [tags (tag-content rdr :if :else :endif)
        not? (and condition1 condition2 (= condition1 "not"))
        condition (compile-filter-body (or condition2 condition1))]
    (fn [context-map]
      (let [condition (if-result (condition context-map))]
        (render-if render context-map (if not? (not condition) condition) (:if tags) (:else tags))))))

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

(defn now-handler [args _ render rdr]
  (fn [context-map]    
    ((:date @filters) (java.util.Date.) (clojure.string/join " " args))))

(defn comment-handler [args tag-content render rdr]
  (do
    (tag-content rdr :comment :endcomment)
    (fn [context-map] "")))

(defn first-of-handler [args _ render rdr]
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
          :else (do
                  (.append buf ch)
                  (recur (read-char rdr))))))))

(defn verbatim-handler [args tag-content render rdr]
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

;;helpers for custom tag definition
(defn render-tags [context-map tags]
  (into {}
    (for [[tag content] tags]
      [tag 
       (update-in content [:content]
         (fn [^selmer.node.INode node]
           (apply str (map #(.render-node ^selmer.node.INode % context-map) node))))])))

(defn tag-handler [handler & tags]
  (fn [args tag-content render rdr]    
     (if-let [content (if (> (count tags) 1) (apply (partial tag-content rdr) tags))]
       (-> (fn [context-map]
             (render
               [(->> content (render-tags context-map) (handler args context-map) (TextNode.))]
               context-map)))
       (fn [context-map]
         (render [(TextNode. (handler args context-map))] context-map)))))
