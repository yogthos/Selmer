(ns selmer.util
  (:require [clojure.java.io :as io])
  (:import java.io.File java.io.StringReader
           java.util.regex.Pattern))

(defmacro exception [& [param & more :as params]]
 (if (class? param)
   `(throw (new ~param (str ~@more)))
   `(throw (Exception. (str ~@params)))))

(def ^:dynamic *custom-resource-path* nil)

(defn set-custom-resource-path!
  [path]
  (alter-var-root #'*custom-resource-path* (constantly path))
  (when (thread-bound? #'*custom-resource-path*)
    (set! *custom-resource-path* path)))

(def ^:dynamic *escape-variables* true)

(defn turn-off-escaping! []
  (alter-var-root #'*escape-variables*
                  (constantly false)))

(defn turn-on-escaping! []
  (alter-var-root #'*escape-variables*
                  (constantly true)))

(defmacro with-escaping [& body]
  `(binding [*escape-variables* true]
     ~@body))

(defmacro without-escaping [& body]
  `(binding [*escape-variables* false]
     ~@body))

(defn pattern [& content]
  (re-pattern (clojure.string/join content)))

(defn read-char [^java.io.Reader rdr]
  (let [ch (.read rdr)]
    (if-not (== -1 ch) (char ch))))

(defn assoc-in*
  "Works best for small collections seemingly."
  [m ks v]
  (let [k (first ks)]
    (if (zero? (count ks))
      (assoc m k (assoc-in* (get m k) (next ks) v))
      (assoc m k v))))

;; default tag characters
(def ^:dynamic ^Character *tag-open* \{)
(def ^:dynamic ^Character *tag-close* \})
(def ^:dynamic ^Character *filter-open* \{)
(def ^:dynamic ^Character *filter-close* \})
(def ^:dynamic ^Character *tag-second* \%)
(def ^:dynamic ^Character *short-comment-second* \#)

;; tag regex patterns
(def ^:dynamic ^Pattern   *tag-second-pattern* nil)
(def ^:dynamic ^Pattern   *filter-open-pattern* nil)
(def ^:dynamic ^Pattern   *filter-close-pattern* nil)
(def ^:dynamic ^Pattern   *filter-pattern* nil)
(def ^:dynamic ^Pattern   *tag-open-pattern* nil)
(def ^:dynamic ^Pattern   *tag-close-pattern* nil)
(def ^:dynamic ^Pattern   *tag-pattern* nil)
(def ^:dynamic ^Pattern   *include-pattern* nil)
(def ^:dynamic ^Pattern   *extends-pattern* nil)
(def ^:dynamic ^Pattern   *block-pattern* nil)
(def ^:dynamic ^Pattern   *block-super-pattern* nil)
(def ^:dynamic ^Pattern   *endblock-pattern* nil)

(def match-unquoted " *(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")

(defn split-unquoted [^String s ^String x]
  (clojure.string/split s (re-pattern (str " *" x match-unquoted))))

(defn check-tag-args [args]
  (if (even? (count (filter #{\"} args)))
    args (exception "malformed tag arguments in " args)))

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
    (let [content (->> (.toString buf)
                       (check-tag-args)
                       (re-seq #"(?:[^\s\"]|\"[^\"]*\")+")
                       (remove empty?)
                       (map (fn [^String s] (.trim s))))]
      (merge {:tag-type tag-type}
             (if (= :filter tag-type)
               {:tag-value (first content)}
               {:tag-name (keyword (first content))
                :args (next content)})))))

(defn peek-rdr [^java.io.Reader rdr]
  (.mark rdr 1)
  (let [result (read-char rdr)]
    (.reset rdr)
    result))

(defmacro ->buf [[buf] & body]
  `(let [~buf (StringBuilder.)]
    (do ~@body)
    (.toString ~buf)))

(defn read-tag-content [rdr]
  (->buf [buf]
    (let [next-ch (peek-rdr rdr)
          filter? (not= *tag-second* next-ch)]
      (.append buf *tag-open*)
      (when next-ch
        (loop [ch (read-char rdr)]
          (.append buf ch)
          (when (and (not= *tag-close* ch) (not= *filter-close* ch))
            (recur (read-char rdr))))
        (when filter?
          (.append buf (read-char rdr)))))))

(defn open-tag? [ch rdr]
  (and (= *tag-open* ch)
       (let [next-ch (peek-rdr rdr)]
         (or (= *filter-open* next-ch)
             (= *tag-second* next-ch)))))

(defn open-short-comment? [ch rdr]
  (and (= *tag-open* ch)
       (let [next-ch (peek-rdr rdr)]
         (= *short-comment-second* next-ch))))

(defn split-by-args [s]
  (let [rdr (StringReader. s)
        buf (StringBuilder.)]
    (loop [items []
           ch (read-char rdr)
           open? false]
      (cond
        (nil? ch) items

        (and open? (= ch \"))
        (let [value (.trim (.toString buf))]
          (.setLength buf 0)
          (recur (conj items value) (read-char rdr) false))

        (= ch \")
        (recur items (read-char rdr) true)

        (and (not open?) (= ch \=))
        (let [id (.trim (.toString buf))]
          (.setLength buf 0)
          (recur (conj items id) (read-char rdr) open?))

        :else
        (do
          (.append buf ch)
          (recur items (read-char rdr) open?))))))

(defn get-resource [resource]
  (-> (Thread/currentThread)
      (.getContextClassLoader)
      (.getResource resource)))

(defn resource-path [template]
  (if-let [path *custom-resource-path*]
    (java.net.URL. (str path template))
    (get-resource template)))

(defn resource-last-modified [^java.net.URL resource]
  (let [path (.getPath resource)]
    (try
      (.lastModified (java.io.File. ^String path))
      (catch NullPointerException _ -1))))

(defn check-template-exists [^java.net.URL resource]
  (when-not resource
    (exception "template: \"" (.getPath ^java.net.URL resource) "\" not found")))

(def default-missing-value-formatter (constantly ""))

(def ^:dynamic *missing-value-formatter* default-missing-value-formatter)
(def ^:dynamic *filter-missing-values* true)

(defn set-missing-value-formatter!
  "Takes a function of two arguments which is called on a missing value.
   The function should return the value to be output in place of an empty string
   (which is the default from 'default-missing-value-formatter').

   Call with named argument :filter-missing-values true to force filtering of missing
   values (although for most use cases this will not make sense).

   Arguments to missing-value-fn:
   tag - map with data for the tag being evaluated.
         Contains the key :tag-type with the value :filter or :expr (for filter or expression tag types.
         For :filter:
            tag-value - the contents of the filter tag as a string.
         For :expr:
            tag-name - the name of the expression.
            args - the args provided to the expression.
   context-map - the context-map provided to the render function."
  [missing-value-fn & {:keys [filter-missing-values] :or {filter-missing-values false}}]
  (alter-var-root #'*missing-value-formatter* (constantly missing-value-fn))
  (alter-var-root #'*filter-missing-values* (constantly filter-missing-values)))
