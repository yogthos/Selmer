(ns selmer.parser
  (:require [selmer.template-parser :refer [preprocess-template]]
            [selmer.filter-parser :refer [compile-filter-body]]
            [selmer.tags :refer :all]
            [selmer.util :refer :all]
            selmer.node)
  (:import [selmer.node INode TextNode FunctionNode]))

(set! *warn-on-reflection* true)

(defonce templates (atom {}))

(declare parse parse-file expr-tag tag-content)

(defn render [template context-map]
  (let [buf (StringBuilder.)]
    (doseq [^selmer.node.INode element template]
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

(def ^:dynamic *expr-tags*
  {:if if-handler
   :ifequal ifequal-handler
   :for for-handler
   :block block-handler})

(defn expr-tag [{:keys [tag-name args] :as tag} rdr]
  (if-let [handler (tag-name *expr-tags*)]
    (handler args tag-content render rdr)
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
          (if-let [open-tag (and tag-name 
                                 (some (fn [[open close]]
                                         (if (= tag-name close) open)) end-tags))]
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
