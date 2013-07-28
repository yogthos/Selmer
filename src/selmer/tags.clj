(ns selmer.tags
  (:require selmer.node)
  (:import [selmer.node INode TextNode FunctionNode]))

(def valid-tags (atom {}))
(def tags (atom {}))

;;; A tag can modify the context map for its body
;;; It has full control of its body which means that it has to
;;; take care of its compilation.

(defn for-handler [[^String id _ items] tag-content render rdr]
  (let [content (:content (:for (tag-content rdr :for :endfor)))
        id (map keyword (.split id "\\."))
        items (keyword items)]
    (fn [context-map]
      (let [buf (StringBuilder.)
            items (get context-map items)
            length (count items)
            parentloop (:parentloop context-map)]
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
              (render content)
              (.append buf))))
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

(defn parse-if-arg [^String arg]
  (map keyword (.split arg "\\.")))

(defn if-result [value]  
  (condp = value
    nil     false
    "false" false
    false   false
    true))

(defn if-handler [[condition1 condition2] tag-content render rdr]
  (let [tags (tag-content rdr :if :else :endif)
        not? (and condition1 condition2 (= condition1 "not"))
        condition (parse-if-arg (or condition2 condition1))]
    (fn [context-map]
      (let [condition (if-result (get-in context-map condition))]
        (render-if render context-map (if not? (not condition) condition) (:if tags) (:else tags))))))

(defn ifequal-handler [args tag-content render rdr]
  (let [tags (tag-content rdr :ifequal :else :endifequal)
        args (for [^String arg args]
               (if (= \" (first arg)) 
                 (.substring arg 1 (dec (.length arg))) 
                 (parse-if-arg arg)))]
    (fn [context-map]
      (let [condition (apply = (map #(if (coll? %) (get-in context-map %) %) args))]
        (render-if render context-map condition (:ifequal  tags) (:else tags))))))

(defn block-handler [args tag-content render rdr]
  (let [content (get-in (tag-content rdr :block :endblock) [:block :content])]
    (fn [context-map] (render content context-map))))

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