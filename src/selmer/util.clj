(ns selmer.util
  (:require [clojure.java.io :as io])
  (:import java.io.File))

(defn read-char [^java.io.Reader rdr]
  (let [ch (.read rdr)]
    (if-not (== -1 ch) (char ch))))

(defn assoc-in*
  "Works best for small collections seemingly."
  [m ks v]
  (let [k (first ks)]
    (if (= (count ks) 0)
      (assoc m k (assoc-in* (get m k) (next ks) v))
      (assoc m k v))))

(def ^:dynamic ^Character *tag-open* \{)
(def ^:dynamic ^Character *tag-close* \})
(def ^:dynamic ^Character *filter-open* \{)
(def ^:dynamic ^Character *filter-close* \})
(def ^:dynamic ^Character *tag-second* \%)

(defn read-tag-info [rdr]
  (let [buf (StringBuilder.)
        tag-type (if (= *filter-open* (read-char rdr)) :filter :expr)]
    (loop [ch1 (read-char rdr)
           ch2 (read-char rdr)]
      (when-not (or (nil? ch1)
                    (and (or (= *filter-close* ch1) (= *tag-second* ch1))
                         (= *tag-close* ch2)))
        (.append buf ch1)
        (recur ch2 (read-char rdr))))
    
    (let [content (->>  (.split (.toString buf) " ") (remove empty?) (map (fn [^String s] (.trim s))))]
      (merge {:tag-type tag-type}
             (if (= :filter tag-type)
               {:tag-value (first content)}
               {:tag-name (keyword (first content))
                :args (next content)})))))

(defmacro ->buf [[buf] & body]
  `(let [~buf (StringBuilder.)]
    (do ~@body)
    (.toString ~buf)))

(defn read-tag-content [rdr]
  (->buf [buf]
    (.append buf *tag-open*)
    (loop [ch (read-char rdr)]
      (.append buf ch)
      (when (not= *tag-close* ch)
        (recur (read-char rdr))))))

(defn peek-rdr [^java.io.Reader rdr]
  (.mark rdr 1)
  (let [result (read-char rdr)]
    (.reset rdr)
    result))

(defn open-tag? [ch rdr]
  (and (= *tag-open* ch) 
       (let [next-ch (peek-rdr rdr)]
         (or (= *filter-open* next-ch)
             (= *tag-second* next-ch)))))

#_(defn resource-path
  "returns the path to the public folder of the application"
  [& [path]]
  (if-let [path (io/resource (or path "."))]
    (.getPath path)))

(defn resource-path [template]
  (-> (Thread/currentThread)
      (.getContextClassLoader)
      (.getResource template)))

