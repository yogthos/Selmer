(ns selmer.filter-parser
  "Accessors are separated by dots like {{ foo.bar.0 }}
which gets translated into (get-in context-map [:foo :bar 0]). So you
can nest vectors and maps in your context-map.

Filters can be applied by separating then from the accessor
with pipes: {{ foo|lower|capitalize }}. They are applied one after
the other from left to right. Arguments can be passed to a filter
separated by colons: {{ foo|pluralize:y:ies }}. If an argument includes
spaces you can enclose it with doublequotes or colons: {{ foo|join:\", \" }}.

You can escape doublequotes inside doublequotes. And you can put colons
inside doublequotes which will be ignored for the purpose of separating
arguments."
  (:require [selmer.filters :refer [get-filter]]
            [clojure.string :as s]))

;;; More Utils
(defn escape-html*
  [^String s]
  "HTML-escapes the given string. Escapes the same characters as django's escape."
  ;; This method is "Java in Clojure" for serious speedups.
  ;; Stolen from davidsantiago/quoin and modified.
  (let [sb (StringBuilder.)
        slength (count s)]
    (loop [idx 0]
      (if (>= idx slength)
        (.toString sb)
        (let [c (char (.charAt s idx))]
          (case c
            \& (.append sb "&amp;")
            \< (.append sb "&lt;")
            \> (.append sb "&gt;")
            \" (.append sb "&quot;")
            \' (.append sb "&#39;")
            (.append sb c))
          (recur (inc idx)))))))

(defn strip-doublequotes
  "Removes doublequotes from the start and end of a string if any."
  [^String s]
  (if (and (> (count s) 1)
           (= \" (first s) (.charAt s (dec (count s)))))
    (.substring s 1 (dec (count s)))
    s))

(defn escape-html
  "Must have the form [:safe safe-string] to prevent escaping."
  [x]
  (if (and (vector? x)
           (= :safe (first x)))
    (second x)
    (let [s (str x)]
      (escape-html* s))))

;;; Compile filters
(defn fix-accessor
  "Turns strings into keywords and strings like \"0\" into Longs
so it can access vectors as well as maps."
  [ks]
  (mapv (fn [^String s]
          (try (Long/valueOf s)
               (catch NumberFormatException _
                 (keyword s))))
        ks))

(defn split-filter-val
  "Split accessors like foo.bar.baz by the dot."
  [s]
  (let [ks (s/split s #"\.")]
    (fix-accessor ks)))

(defn fix-filter-args
  "Map any sort of needed fixes to the arguments before passing them
to the filters. Only strips enclosing doublequotes for now."
  [args]
  ;; TODO - figure out what kind of extra args filters can take
  (map (fn [^String s]
         (strip-doublequotes s))
       args))

(defn filter-str->fn
  "Turns a filter string like \"pluralize:y:ies\" into a function that
expects a value obtained from a context map or from a previously
applied filter."
  [s]
  (let [[filter-name & args]
        ;; Ignore colons inside doublequotes
        (re-seq #"(?:[^:\"]|\"[^\"]*\")+" s)
        args (fix-filter-args args)
        filter (get-filter filter-name)]
    
    (if filter 
      (fn [x]
        (apply filter x args))
      (throw (Exception. (str "No filter defined with the name '" filter-name "'"))))))

(defn compile-filter-body
  "Turns a string like foo|filter1:x|filter2:y into a fn that expects a
context-map and will apply the filters one after the other to the value
from the map. Finally it will escape the end result unless the last
filter is \"safe\"."
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
        #_(when-not x
          (println "Warning:" val "returns nil"))
        ;; Escape by default unless the last filter is 'safe'
        (escape-html ((apply comp filters) x))))))
