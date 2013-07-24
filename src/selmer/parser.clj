(ns selmer.parser
  (:require [selmer.filter-parser :refer [compile-filter-body]]))

(def ^Character tag-open \{)
(def ^Character tag-close \})
(def ^Character filter-open \{)
(def ^Character filter-close \})
(def ^Character tag-second \%)

(def templates (atom {}))

(declare parse expr-tag tag-content)

(defn render [template args]
  (let [buf (StringBuilder.)]
    (doseq [element template]
      (.append buf (if (string? element) element (element args))))
    (.toString buf)))

(defn render-file [filename args]
  (let [{:keys [template last-modified]} (get @templates filename)
        last-modified-file (.lastModified (java.io.File. filename))]
    (if (and last-modified (= last-modified last-modified-file))
      (render template args)
      (let [template (parse filename)]
        (swap! templates assoc filename {:template template
                                         :last-modified last-modified-file})
        (render template args)))))

(defn read-char [rdr]
  (let [ch (.read rdr)]
    (if-not (== -1 ch) (char ch))))

#_(defn tag-handler [handler open-tag & end-tags]
  (fn [args rdr]
    (if (not-empty end-tags)
      (let [content (apply (partial tag-content rdr) end-tags)]
        (handler args content)))))

(defn for-handler [[id _ items] rdr]
  (let [content (:content (:endfor (tag-content rdr :endfor)))
        id (map keyword (.split id "\\."))
        items (keyword items)]    
    (fn [context-map]
      (let [buf (StringBuilder.)]
        (doseq [value (get context-map items)]
          (.append buf (render content (assoc-in context-map id value))))
        (.toString buf)))))

(defn render-if [context-map condition first-block second-block]  
  (render
    (cond 
      (and condition first-block)
      (:content first-block)
      
      (and (not condition) first-block)
      (:content second-block)
      
      condition
      (:content second-block)
      
      :else [""])
    context-map))

(defn if-handler [[condition] rdr]
  (let [tags (tag-content rdr :else :endif)
        condition (keyword condition)]
    (fn [context-map]
      (render-if context-map (condition context-map) (:else tags) (:endif tags)))))

(defn ifequal-handler [args rdr]
  (let [tags (tag-content rdr :else :endifequal)
        args (for [arg args]
               (if (= \" (first arg)) 
                 (.substring arg 1 (dec (count arg))) 
                 (keyword arg)))]
    (fn [context-map]
      (let [condition (apply = (map #(if (keyword? %) (% context-map) %) args))]        
        (render-if context-map condition (:else tags) (:endifequal tags))))))

(defn block-handler [args rdr]
  (let [content (tag-content rdr :endblock)]
    (fn [args] (render content args))))

(def expr-tags
  {:if if-handler
   :ifequal ifequal-handler
   :for for-handler
   :block block-handler})

(defn expr-tag [{:keys [tag-name args] :as tag} rdr]
  (if-let [handler (tag-name expr-tags)]
    (handler args rdr)
    (throw (Exception. (str "unrecognized tag: " tag-name)))))

(defn filter-tag [{:keys [tag-value]}]
  (compile-filter-body tag-value))

(defn read-tag-info [rdr]
  (let [buf (StringBuilder.)
        tag-type (if (= filter-open (read-char rdr)) :filter :expr)]
    (loop [ch1 (read-char rdr)
           ch2 (read-char rdr)]
      (when-not (and (or (= filter-close ch1) (= tag-second ch1))
                     (= tag-close ch2))
        (.append buf ch1)
        (recur ch2 (read-char rdr))))
    (let [content (->>  (.split (.toString buf ) " ") (remove empty?) (map (memfn trim)))]
      (merge {:tag-type tag-type}
             (if (= :filter tag-type)
               {:tag-value (first content)}
               {:tag-name (keyword (first content))
                :args (rest content)})))))

(defn parse-tag [{:keys [tag-type] :as tag} rdr]
  (if (= :filter tag-type)
    (filter-tag tag)
    (expr-tag tag rdr)))

(defn tag-content [rdr & end-tags]
  (let [buf (StringBuilder.)]    
    (loop [ch       (read-char rdr)
           tags     {}
           content  []
           end-tags end-tags]
      (cond 
        (nil? ch)
        tags 
        
        (= tag-open ch)
        (let [{:keys [tag-name args] :as tag} (read-tag-info rdr)]            
          (if (and tag-name (some #{tag-name} end-tags))              
            (let [tags     (assoc tags tag-name 
                                  {:args args 
                                   :content (conj content (.toString buf))})
                  end-tags (rest (drop-while #(not= tag-name %) end-tags))]                                
              (.setLength buf 0)
              (recur (if (empty? end-tags) nil (read-char rdr)) tags [] end-tags))
            (let [content (-> content 
                              (conj (.toString buf))
                              (conj (parse-tag tag rdr)))]
              (.setLength buf 0)
              (recur (read-char rdr) tags content end-tags))))
        :else
        (do
          (.append buf ch)
          (recur (read-char rdr) tags content end-tags))))))

(defn handle-tag [rdr]
  (let [tag (read-tag-info rdr)]
    (parse-tag tag rdr)))

(defn parse [file]
  (with-open [rdr (clojure.java.io/reader file)]
      (let [template (transient [])
            buf      (StringBuilder.)]
        (loop [ch (read-char rdr)]
          (when ch
            (if (= tag-open ch)
              (do
                ;we hit a tag so we append the buffer content to the template
                ; and empty the buffer, then we proceed to parse the tag
                (conj! template (.toString buf))
                (.setLength buf 0)
                (conj! template (handle-tag rdr))
                (recur (read-char rdr)))
              (do
                ;default case, here we simply append the character and
                ;go to read the next one
                (.append buf ch)
                (recur (read-char rdr))))))
        ;add the leftover content of the buffer and return the template
        (conj! template (.toString buf))
        (persistent! template))))

