(ns selmer.parser
  (:require [selmer.template-parser :refer [preprocess-template]]
            [selmer.filter-parser :refer [compile-filter-body]]
            [selmer.util :refer :all]))

(set! *warn-on-reflection* true)

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

(defonce templates (atom {}))

(declare parse parse-file expr-tag tag-content)

(defn render [template context-map]
  (let [buf (StringBuilder.)]
    (doseq [^selmer.parser.INode element template]
        (if-let [value (.render-node element context-map)]
          (.append buf value)))
    (.toString buf)))

(defn render-string [s context-map & [opts]]
  (render (parse (java.io.StringReader. s) opts) context-map))

(defn render-file [^String filename context-map & [opts]]
  (let [file-path (.getPath ^java.net.URL (resource-path filename))
        {:keys [template last-modified]} (get @templates filename)
        last-modified-file (.lastModified (java.io.File. ^String file-path))]
      
    (when-not (.exists (java.io.File. file-path))
      (throw (Exception. (str "temaplate: \"" file-path "\" not found"))))
        
    (if (and last-modified (= last-modified last-modified-file))
      (render template context-map)
      (let [template (parse-file filename opts)]
        (swap! templates assoc filename {:template template
                                         :last-modified last-modified-file})
        (render template context-map)))))

(defn for-handler [[^String id _ items] rdr]
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

(defn parse-if-arg [^String arg]
  (map keyword (.split arg "\\.")))

(defn if-result [value]  
  (condp = value
    nil     false
    "false" false
    false   false
    true))

(defn if-handler [[condition1 condition2] rdr]
  (let [tags (tag-content rdr :if :else :endif)
        not? (and condition1 condition2 (= condition1 "not"))
        condition (parse-if-arg (or condition2 condition1))]
    (fn [context-map]
      (let [condition (if-result (get-in context-map condition))]
        (render-if context-map (if not? (not condition) condition) (:if tags) (:else tags))))))

(defn ifequal-handler [args rdr]
  (let [tags (tag-content rdr :ifequal :else :endifequal)
        args (for [^String arg args]
               (if (= \" (first arg)) 
                 (.substring arg 1 (dec (.length arg))) 
                 (parse-if-arg arg)))]
    (fn [context-map]
      (let [condition (apply = (map #(if (coll? %) (get-in context-map %) %) args))]
        (render-if context-map condition (:ifequal  tags) (:else tags))))))

(defn block-handler [args rdr]
  (let [content (get-in (tag-content rdr :endblock) [:block :content])]
    (fn [context-map] (render content context-map))))

(defn render-tags [context-map tags]
  (into {}
    (for [[tag content] tags]
      [tag 
       (update-in content [:content]
         (fn [^selmer.parser.INode node]
           (apply str (map #(.render-node ^selmer.parser.INode % context-map) node))))])))

(defn tag-handler [handler & tags]
  (fn [args rdr]    
     (let [content (if (> (count tags) 1) (apply (partial tag-content rdr) tags))]
       (-> (fn [context-map]
             (render
               [(->> content (render-tags context-map) (handler args context-map) (TextNode.))]
               context-map))))))

(def ^:dynamic *expr-tags*
  {:if if-handler
   :ifequal ifequal-handler
   :for for-handler
   :block block-handler})

(defn expr-tag [{:keys [tag-name args] :as tag} rdr]
  (if-let [handler (tag-name *expr-tags*)]
    (handler args rdr)
    (throw (Exception. (str "unrecognized tag: " tag-name)))))

(defn filter-tag [{:keys [tag-value]}]
  (compile-filter-body tag-value))

(defn parse-tag [{:keys [tag-type] :as tag} rdr]  
  (if (= :filter tag-type)
    (filter-tag tag)
    (expr-tag tag rdr)))

(defn tag-content [rdr & end-tags]  
  (let [buf (StringBuilder.)]
    (loop [ch       (read-char rdr)
           tags     {}
           content  []
           end-tags (partition 2 1 end-tags)]      
      (cond
        (nil? ch)
        tags
        
        (open-tag? ch rdr)
        (let [{:keys [tag-name args] :as tag} (read-tag-info rdr)]
          (if-let [open-tag (and tag-name (some (fn [[open close]] (if (= tag-name close) open)) end-tags))]
              (let [tags     (assoc tags open-tag
                                    {:args args
                                     :content (conj content (TextNode. (.toString buf)))})
                    end-tags (next (drop-while #(not= tag-name (second %)) end-tags))]
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

(defn parse* [input]
  (with-open [rdr (clojure.java.io/reader input)]
      (let [template (transient [])
            buf      (StringBuilder.)]
        (loop [ch (read-char rdr)]
          (when ch
            (if (open-tag? ch rdr)
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

(defn parse [file & [{:keys [tag-open tag-close filter-open filter-close tag-second custom-tags]}]]
  (binding [*tag-open*     (or tag-open *tag-open*)
            *tag-close*    (or tag-close *tag-close*)
            *filter-open*  (or filter-open *filter-open*)
            *filter-close* (or filter-close *filter-close*)
            *tag-second*   (or tag-second *tag-second*)
            *expr-tags*    (merge *expr-tags* custom-tags)]
    (parse* file)))

(defn parse-file [file & [params]]
  (-> file preprocess-template (java.io.StringReader.) (parse params)))

