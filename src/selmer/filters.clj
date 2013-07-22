(ns selmer.filters
  (:require [clojure.string :as s]))

(def filters
  (atom {}))

(defn get-filter
  [name]
  (get @filters (keyword name)))

(defn call-filter
  [name & args]
  (apply (get-filter name) args))

(defn add-filter-fn!
  [name f]
  (swap! filters assoc (keyword name) f))

(defmacro add-filter!
  "Convenience macro to create a fn and then add it to the filters map.
Example usage:

 (add-filter! :inc
   [x]
   (when (number? x)
     (inc x)))"
  [name args & body]
  `(add-filter-fn!
    ~name
    (fn ~args
      ~@body)))

(add-filter-fn!
 :length
 count)

(add-filter-fn!
 :upper
 s/upper-case)

(add-filter-fn!
 :lower
 s/lower-case)

(add-filter-fn!
 :capitalize
 s/capitalize)

;;; Do not escape html
(add-filter!
 :safe
 [s]
 [:safe s])
