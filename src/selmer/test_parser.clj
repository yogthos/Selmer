(ns selmer.test-parser)

(declare parse expr-tag)

(defn render [template params]  
  (->> (for [element template] 
         (if (string? element) element (element params)))
       (apply str)))

(defn read-char [rdr]
  (let [ch (.read rdr)]
    (if-not (neg? ch) (char ch))))

(defn peek-stream [rdr size]
  (.mark rdr size)
  (let [result (loop [items []]
                 (let [ch (read-char rdr)]
                   (if (and ch (< (count items) size))
                     (recur (conj items ch))
                     items)))]
    (.reset rdr)
    result))

(defn value-tag [{:keys [tag-value]}]  
  (let [value (map keyword (.split tag-value "\\."))]
    (fn [params] (get-in params value))))

#_((value-tag {:tag-value "foo.bar.baz"}) {:foo {:bar {:baz "ok"}}})
#_((value-tag {:tag-value "foo"}) {:foo "ok"})

(defn read-tag-info [rdr]
  (let [buf (StringBuilder.)
        tag-type (if (= \{(read-char rdr)) :value :expr)]
    (loop [ch1 (read-char rdr)
           ch2 (read-char rdr)]            
      (when-not (and (or  (= \% ch1) (= \} ch1)) (= \} ch2))
        (.append buf ch1)        
        (recur ch2 (read-char rdr))))    
    (let [content (->>  (.split (.toString buf ) " ") (remove empty?) (map (memfn trim)))]
      (merge {:tag-type tag-type}
             (if (= :value tag-type)
               {:tag-value (first content)}
               {:tag-name (first content)
                :params (rest content)})))))

#_(read-tag-info (java.io.StringReader. "% for i in nums %}"))
#_(read-tag-info (java.io.StringReader. "{ nums }}"))

(defn tag-content [close-tag rdr]
  (let [content (transient [])
        buf (StringBuilder.)]
    (loop [ch (read-char rdr)]
      (if (= \{ ch)
        (let [{:keys [tag-name tag-type] :as tag} (read-tag-info rdr)]          
          (when (not= close-tag tag-name)
            (println tag)
            (conj! content (.toString buf))
            (.setLength buf 0)
            (conj! content (if (= :value tag-type)
                             (value-tag tag)
                             (expr-tag tag rdr)))
            (recur (read-char rdr))))
        (do
          (.append buf ch)
          (recur (read-char rdr)))))
    (conj! content (.toString buf))
    (persistent! content)))

#_(tag-content "endfor" (java.io.StringReader. "foo {{name}} bar {% endfor %}"))

#_(render (tag-content "endfor" (java.io.StringReader. "foo {{name.first}} bar {% endfor %}")) {:name {:first "Bob"}})

(def expr-tags
  {:for {:content true
         :close-tag "endfor"}})

(defn expr-tag [{:keys [tag-name params]} rdr]
  (let [{:keys [content close-tag]} (get expr-tags tag-name)]
    (if content
      (tag-content close-tag rdr))))

(defn handle-tag [rdr]
  (let [tag (read-tag-info rdr)]
    ((if (:value tag) value-tag expr-tag) tag)))

(defn parse [file & state]
  (with-open [rdr (clojure.java.io/reader file)]
      (let [template (transient [])
            sb (StringBuilder.)]
        (loop [state state
               ch (.read rdr)]                    
          (when (pos? ch)            
            (let [ch (char ch)]
              (if (= \{ ch)
                (do
                  (conj! template (.toString sb))
                  (.setLength sb 0)
                  (conj! template (handle-tag rdr))
                  (recur state (.read rdr)))
                (do
                  (.append sb ch)
                  (recur state (.read rdr)))))))
        (persistent! template))))


#_(render (parse "home.html") {:name "Bob"})
