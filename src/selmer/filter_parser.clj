(ns selmer.filter-parser
  (:require [selmer.filters :refer [get-filter]]
            [clojure.string :as s]))

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
