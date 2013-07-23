(ns selmer.parser
  (:require [clojure.string :as s]
            [selmer.filters :refer [get-filter]]
            [selmer.tags :refer [tags]])
  (:import [java.io PushbackReader CharArrayReader StringReader]))

;;; TODO - implement filter/tag parsers

(defn ^CharArrayReader make-reader
  [^String s]
  (CharArrayReader. (.toCharArray s)))

(def ^Character tag-opener \{)
(def ^Character filter-second \{)
(def ^Character tag-second \%)

(declare buffer-string buffer-filter buffer-tag parser*)

(defn parser
  [s]
  (let [rdr (make-reader s)]
    (parser* rdr :read-string [] nil)))

(defn parser*
  "Returns a vector where strings are left as they were
but tags and filters are turned into maps to be compiled"
  [^CharArrayReader r state ast closing-to-find]
  (loop [state state ast ast to-find closing-to-find]
    (case state
      :read-string
      (let [[next-step buffered-str] (buffer-string r)]
        (case next-step
          ;; Found end of string
          ;; TODO - error if there's still a closing tag to find
          :end (if (seq buffered-str)
                 (conj ast buffered-str)
                 ast)
          :read-filter (recur :read-filter
                              (conj ast buffered-str)
                              to-find)
          :read-tag (recur :read-tag
                           (conj ast buffered-str)
                           to-find)))
      :read-filter
      (recur :read-string
             (conj ast {:type :filter
                        :body (buffer-filter r)})
             to-find)
      :read-tag
      (let [{:keys [status name closing-tag args]} (buffer-tag r)]
        (case status
          :found-closing-tag
          (cond
           (and to-find closing-tag
                (= to-find closing-tag))
           ast
           (and to-find closing-tag)
           (throw (IllegalStateException. (str "Expected closing tag "
                                               to-find
                                               ". Got " closing-tag " instead.")))
           ;; Found a closing tag when we weren't expecting one
           :else
           (do
             (println "Warning: Found a random closing tag:" closing-tag)
             (recur :read-string
                    ast
                    to-find)))
          :found-opening-tag
          ;; Keep parsing after the tag, looks kinda silly
          (do (recur :read-string
                     (conj ast
                           {:type :tag
                            :tag name
                            :args args
                            ;; Parse the body of the tag with a brand new tree
                            :body
                            (when-not (= :inline closing-tag)
                              (parser* r :read-string [] (when-not
                                                             (= :inline closing-tag)
                                                           closing-tag)))})
                     to-find ;; Assume we found the closing tag
                     )))))))

(defn buffer-string
  [^CharArrayReader r]
  (let [sb (StringBuilder.)]
    (loop [cn (.read r)]
      (if (== cn -1)
        ;; Finished reading
        [:end
         (str sb)]
        (let [c (char cn)]
          (if (= tag-opener c)
            (let [cn2 (.read r)]
              (if (== cn2 -1)
                ;; Reached end of reader
                (do (.append sb c)
                    [:end
                     (str sb)])
                (let [c2 (char cn2)]
                  (if (or (= filter-second c2)
                          (= tag-second c2))
                    [(if (= filter-second c2)
                       :read-filter
                       :read-tag)
                     (str sb)]
                    (do (.append sb c)
                        (.append sb c2)
                        (recur (.read r)))))))
            (do (.append sb c)
                (recur (.read r)))))))))

(defn buffer-filter
  [^CharArrayReader r]
  (let [sb (StringBuilder.)]
    (loop [cn (.read r)]
      (if (== -1 cn)
        (throw (IllegalStateException. "Filter with no closing '}}'"))
        (let [c (char cn)]
          (case c
            ;; Possibly found the end of the filter
            \}
            (let [cn2 (.read r)]
              (if (== -1 cn2)
                (throw (IllegalStateException. "Filter with no closing '}}'"))
                (let [c2 (char cn2)]
                  (if (= \} c2)
                    ;; Found the end of the filter
                    (str sb)
                    (do (.append sb c)
                        (.append sb c2)
                        (str sb))))))
            (do (.append sb c)
                (recur (.read r)))))))))

(declare parse-tag-args)

(defn buffer-tag
  [^CharArrayReader r]
  (let [sb (StringBuilder.)]
    (loop [cn (.read r)]
      (if (== -1 cn)
        (throw (IllegalStateException. "Tag with no closing %} 1"))
        (let [c (char cn)]
          (case c
            ;; Possibly found end of tag '%}'
            \%
            (let [cn2 (.read r)]
              (if (== -1 cn2)
                (throw (IllegalStateException. "Tag with no closing %} 2"))
                (let [c2 (char cn2)]
                  (case c2
                    \}
                    ;; Return the map result of parsing the tag args
                    (parse-tag-args (str sb))
                    (do (.append sb c)
                        (.append sb c2)
                        (recur (.read r)))))))
            (do (.append sb c)
                (recur (.read r)))))))))

;;; TODO - implement tags
(def valid-tags
  (atom {"for" "endfor"
         "include" :inline
         "block" "endblock"}))

(defn type-of-tag
  [tag]
  (let [vtags @valid-tags
        opening (set (keys vtags))
        closing (set (vals vtags))]
    (cond
     (or (= :inline (closing tag))
         (opening tag))
     :opening
     (closing tag)
     :closing
     :else
     :invalid)))

(defn parse-tag-args
  [s]
  (let [[tag & args]
        ;; TODO - find out actual spec
        (-> s
            (s/trim)
            (s/split #"\s+"))
        tag-type (type-of-tag tag)]
    (if (= :invalid tag-type)
      (throw (IllegalStateException. (str "Invalid tag:" tag)))
      {:name tag
       :args args
       :status (case tag-type
                 :opening :found-opening-tag
                 :closing :found-closing-tag)
       :closing-tag (case tag-type
                      :opening (@valid-tags tag)
                      :closing tag)})))

;;; More Utils
(defn escape-html*
  [s]
  "HTML-escapes the given string."
  [^String s]
  ;; This method is "Java in Clojure" for serious speedups.
  ;; Stolen from davidsantiago/quoin and modified.
  (let [sb (StringBuilder.)
        slength (long (count s))]
    (loop [idx (long 0)]
      (if (>= idx slength)
        (.toString sb)
        (let [c (char (.charAt s idx))]
          (case c
            \& (.append sb "&amp;")
            \< (.append sb "&lt;")
            \> (.append sb "&gt;")
            \" (.append sb "&quot;")
            \™ (.append sb "&trade;")
            \é (.append sb "&eacute;")
            (.append sb c))
          (recur (inc idx)))))))

(defn strip-doublequotes
  [^String s]
  (if (and (> (count s) 1)
           (= \" (first s) (.charAt s (dec (count s)))))
    (.substring s 1 (dec (count s)))
    s))

(defn escape-html
  [x]
  (if (and (vector? x)
           (= :safe (first x)))
    (second x)
    (let [s (str x)]
      (escape-html* s))))

;;; Compile filters
(defn fix-accessor
  [ks]
  (mapv (fn [^String s]
          (try (Long/valueOf s)
               (catch NumberFormatException _
                 (keyword s))))
        ks))

(defn split-filter-val
  [s]
  (let [ks (s/split s #"\.")]
    (fix-accessor ks)))

(defn fix-filter-args
  [args]
  ;; TODO - figure out what kind of extra args filters can take
  (map (fn [^String s]
         (try (Long/valueOf s)
              (catch NumberFormatException _
                (strip-doublequotes s))))
       args))

(defn filter-str->fn
  [s]
  (let [[filter-name & args]
        ;; Ignore colons inside doublequotes
        (re-seq #"(?:[^:\"]|\"[^\"]*\")+" s)
        args (fix-filter-args args)
        filter (get-filter filter-name)]
    (fn [x]
      (apply filter x args))))

(defn compile-filter-body
  [s]
  (let [[val & filters] (->> s
                             (s/trim)
                             ;; Ignore pipes and allow escaped doublequotes inside doublequotes
                             (re-seq #"(?:[^|\"]|\"[^\"]*\")+"))
        accessor (split-filter-val val)
        filters (->> filters
                      (map filter-str->fn)
                      (reverse))]
    (fn [context-map]
      (let [x (get-in context-map accessor)]
        ;; Escape by default unless the last filter is 'safe'
        (escape-html ((apply comp filters) x))))))
;;; End of filter compiling

(declare get-tag) ;; TODO - implement in selmer.tags

;;; Compile tags
(defn compile-tag
  [{:keys [tag args body]}]
  (fn [context-map]
    (if args
      (apply (get-tag tag) context-map args body)
      (apply (get-tag) context-map body))))

;;; End of tag compiling

(defn shallow-compile
  "Compile only the first level of the lexed-tree.
Compile means turning filters and tags into fns that expect
a context map. Strings are left alone"
  [lexed-tree]
  (map (fn [token]
         (cond (string? token)
               token
               (= :filter (:type token))
               (compile-filter-body (:body token))
               (= :tag (:type token))
               (compile-tag token)))
       lexed-tree))

(defn render
  [s context-map]
  (let [compiled (shallow-compile (parser s))]
    (apply str
           (for [x compiled]
             (if (string? x)
               x
               (x context-map))))))

;;; Testing stuff
(comment
  (def ts1
    "<body>
{{foo|upper}}

{% for x in lst %}
{{x}}
{% include foo.html %}
{%endfor%}

</body>")
 (def ts2 (apply str (repeat 2 ts1)))
 (def ts3
    "<body>
{{foo|upper}}

{% for x in lst %}
{{x}}
{% include foo.html %}
{% block bar%}
{{y}}
{%endblock%}

{%endfor%}

</body>")

 (def ts4 (apply str (repeat 2 ts3)))
 (def ts5 "
 <body>
{{x}} {{y}}
{{x|capitalize}} {{lst|length}}
 </body>
")

 (parser ts1)
 (parser ts2)
 (parser ts3)
 (parser ts4)
 (compile (parser ts1))
 (render ts5 {:x "foo"
              :y "bar"
              :lst [1 2 3]})
)
