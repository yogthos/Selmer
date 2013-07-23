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

(defn add-filter!
  [name f]
  (swap! filters assoc (keyword name) f))

(add-filter!
 :length
 count)

(add-filter!
 :upper
 s/upper-case)

(add-filter!
 :lower
 s/lower-case)

(add-filter!
 :capitalize
 s/capitalize)

;;; Do not escape html
(add-filter!
 :safe
 (fn
   [s]
   [:safe s]))

(add-filter!
 :join
 (fn
   [coll sep]
   (s/join sep coll)))
