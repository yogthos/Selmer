(ns selmer.test-parser)

(def ^Character tag-open \{)
(def ^Character tag-close \})
(def ^Character filter-open \{)
(def ^Character filter-close \})
(def ^Character tag-second \%)

(declare parse expr-tag tag-content)

(defn render [template args]
  (let [buf (StringBuilder.)]
    (doseq [element template]
      (.append buf (if (string? element) element (element args))))
    (.toString buf)))

(defn read-char [rdr]
  (let [ch (.read rdr)]
    (if-not (== -1 ch) (char ch))))

(defn for-hanlder [[id _ items] rdr]
  (let [content (tag-content :endfor rdr)
        id (map keyword (.split id "\\."))
        items (keyword items)]
    (fn [args]
      (let [buf (StringBuilder.)]
        (doseq [value (get args items)]          
          (.append buf (render content (assoc-in args id value))))
        (.toString buf)))))

(def expr-tags
  {:for {:handler for-hanlder}
   :block {:handler
           (fn [args rdr]
             (let [content (tag-content :endblock rdr)]
               (fn [args] (render content args))))}})

(defn expr-tag [{:keys [tag-name args] :as tag} rdr]
  (if-let [handler (get-in expr-tags [tag-name :handler])]
    (handler args rdr)
    (throw (Exception. (str "unrecognized tag: " tag-name)))))

(defn filter-tag [{:keys [tag-value]}]  
  (let [value (map keyword (.split tag-value "\\."))]
    (fn [args] (get-in args value))))

#_((filter-tag {:tag-value "foo.bar.baz"}) {:foo {:bar {:baz "ok"}}})
;ok
#_((filter-tag {:tag-value "foo"}) {:foo "ok"})
;ok

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

#_(read-tag-info (java.io.StringReader. "% for i in nums %}"))
;{:args ("i" "in" "nums"), :tag-name "for", :tag-type :expr}

#_(read-tag-info (java.io.StringReader. "{ nums }}"))
;{:tag-value "nums", :tag-type :value}

(defn tag-content [end-tag rdr]
  (let [content (transient [])
        buf (StringBuilder.)]
    (loop [ch (read-char rdr)]
      (if (= tag-open ch)
        (let [{:keys [tag-name tag-type] :as tag} (read-tag-info rdr)]
          (when (not= end-tag tag-name)
            (conj! content (.toString buf))
            (.setLength buf 0)
            (conj! content (if (= :filter tag-type)
                             (filter-tag tag)
                             (expr-tag tag rdr)))
            (recur (read-char rdr))))
        (do
          (.append buf ch)
          (recur (read-char rdr)))))
    (conj! content (.toString buf))
    (persistent! content)))

#_(tag-content "endfor" (java.io.StringReader. "foo {{name}} bar {% endfor %}"))

#_(render (tag-content "endfor" (java.io.StringReader. "foo {{name.first}} bar {% endfor %}")) {:name {:first "Bob"}})
;"foo Bob bar "

(defn handle-tag [rdr]
  (let [tag (read-tag-info rdr)]
    (if (= :filter (:tag-type tag))
      (filter-tag tag)
      (expr-tag tag rdr))))

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

#_(spit "out.html" (render (parse "home.html") {:name "Bob" :users [[{:name "test" }] [{:name "test1" }]]}))
#_(println (render (parse "home.html") {:name "Bob" :users [[{:name "test" }] [{:name "test1" }]]}))

