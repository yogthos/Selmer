(ns selmer.parser
  (:require [selmer.filter-parser :refer [compile-filter-body]]))

(set! *warn-on-reflection* true)

(def ^:dynamic ^Character *tag-open* \{)
(def ^:dynamic ^Character *tag-close* \})
(def ^:dynamic ^Character *filter-open* \{)
(def ^:dynamic ^Character *filter-close* \})
(def ^:dynamic ^Character *tag-second* \%)

(defprotocol INode
  (render-node [this context-map] "Renders the context"))

(deftype FunctionNode [handler]
  INode
  (render-node ^String [this context-map]
    (handler context-map)))

(deftype TextNode [text]
  INode
  (render-node ^String [this context-map]
    text))

(def templates (atom {}))

(declare parse expr-tag tag-content)

(defn render [template context-map]  
  (let [buf (StringBuilder.)]
    (doseq [^selmer.parser.INode element template]
        (.append buf (.render-node element context-map)))
    (.toString buf)))

(defn render-string [s context-map & [opts]]
  (render (parse (java.io.StringReader. s) opts) context-map))

(defn render-file [filename context-map & [opts]]
  (let [{:keys [template last-modified]} (get @templates filename)
        last-modified-file (.lastModified (java.io.File. ^String filename))]
    (if (and last-modified (= last-modified last-modified-file))
      (render template context-map)
      (let [template (parse filename opts)]
        (swap! templates assoc filename {:template template
                                         :last-modified last-modified-file})
        (render template context-map)))))

(defn read-char [^java.io.Reader rdr]
  (let [ch (.read rdr)]
    (if-not (== -1 ch) (char ch))))

#_(defn tag-handler [handler open-tag & end-tags]
  (fn [args rdr]
    (if (not-empty end-tags)
      (let [content (apply (partial tag-content rdr) end-tags)]
        (handler args content)))))

(defn assoc-in*
  "Works best for small collections seemingly."
  [m ks v]
  (let [k (first ks)]
    (if (= (count ks) 0)
      (assoc m k (assoc-in* (get m k) (next ks) v))
      (assoc m k v))))

(map-indexed vector ["foo" "Bar"])

(defn for-handler [[^String id _ items] rdr]
  (let [content (:content (:endfor (tag-content rdr :endfor)))
        id (map keyword (.split id "\\."))
        items (keyword items)]    
    (fn [context-map]
      (let [buf (StringBuilder.)
            items (get context-map items)
            length (count items)]
        (doseq [[counter value] (map-indexed vector items)]
          (->> (assoc (assoc-in context-map id value)
                      :forloop
                      {:counter0 counter
                       :counter (inc counter)
                       :revcounter (- length (inc counter))
                       :revcounter0 (- length counter)})               
               (render content)
               (.append buf)))
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
      
      :else [(TextNode. "")])
    context-map))

(defn if-handler [[condition1 condition2] rdr]
  (let [tags (tag-content rdr :else :endif)
        not? (and condition1 condition2 (= condition1 "not"))
        condition (keyword (or condition2 condition1))]    
    (fn [context-map]
      (let [condition (condition context-map)]        
        (render-if context-map (if not? (not condition) condition) (:else tags) (:endif tags))))))

(defn ifequal-handler [args rdr]
  (let [tags (tag-content rdr :else :endifequal)
        args (for [^String arg args]
               (if (= \" (first arg)) 
                 (.substring arg 1 (dec (.length arg))) 
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
        tag-type (if (= *filter-open* (read-char rdr)) :filter :expr)]
    (loop [ch1 (read-char rdr)
           ch2 (read-char rdr)]
      (when-not (and (or (= *filter-close* ch1) (= *tag-second* ch1))
                     (= *tag-close* ch2))
        (.append buf ch1)
        (recur ch2 (read-char rdr))))
    (let [content (->>  (.split (.toString buf ) " ") (remove empty?) (map (fn [^String s] (.trim s))))]
      (merge {:tag-type tag-type}
             (if (= :filter tag-type)
               {:tag-value (first content)}
               {:tag-name (keyword (first content))
                :args (next content)})))))

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
        
        (= *tag-open* ch)
        (let [{:keys [tag-name args] :as tag} (read-tag-info rdr)]
          (if (and tag-name (some #{tag-name} end-tags))
            (let [tags     (assoc tags tag-name 
                                  {:args args 
                                   :content (conj content (TextNode. (.toString buf)))})
                  end-tags (next (drop-while #(not= tag-name %) end-tags))]
              (.setLength buf 0)
              (recur (if (empty? end-tags) nil (read-char rdr)) tags [] end-tags))
            (let [content (-> content 
                              (conj (TextNode. (.toString buf)))
                              (conj (FunctionNode. (parse-tag tag rdr))))]
              (.setLength buf 0)
              (recur (read-char rdr) tags content end-tags))))
        :else
        (do
          (.append buf ch)
          (recur (read-char rdr) tags content end-tags))))))

(defn parse* [file]
  (with-open [rdr (clojure.java.io/reader file)]
      (let [template (transient [])
            buf      (StringBuilder.)]
        (loop [ch (read-char rdr)]
          (when ch
            (if (= *tag-open* ch)
              (do
                ;we hit a tag so we append the buffer content to the template
                ; and empty the buffer, then we proceed to parse the tag
                (conj! template (TextNode. (.toString buf)))
                (.setLength buf 0)
                (conj! template (FunctionNode. (parse-tag (read-tag-info rdr) rdr)))
                (recur (read-char rdr)))
              (do
                ;default case, here we simply append the character and
                ;go to read the next one
                (.append buf ch)
                (recur (read-char rdr))))))
        ;add the leftover content of the buffer and return the template
        (conj! template (TextNode. (.toString buf)))
        (persistent! template))))

(defn parse [file & [{:keys [tag-open tag-close filter-open filter-close tag-second]}]]
  (binding [*tag-open*     (or tag-open *tag-open*)
            *tag-close*    (or tag-close *tag-close*)
            *filter-open*  (or filter-open *filter-open*)
            *filter-close* (or filter-close *filter-close*)
            *tag-second*   (or tag-second *tag-second*)]
    (parse* file)))

