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
  (:require
    [selmer.filters :refer [get-filter]]
    [selmer.util :refer [exception *escape-variables* fix-accessor parse-accessor]]
    [clojure.string :as s]))

;;; More Utils
(defn escape-html*
  "HTML-escapes the given string. Escapes the same characters as django's escape."
  [^String s]
  ;; This method is "Java in Clojure" for serious speedups.
  ;; Stolen from davidsantiago/quoin and modified.
  (if *escape-variables*
    (let [slength (count s)
          sb      (StringBuilder. slength)]
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
            (recur (inc idx))))))
    s))

(defn strip-doublequotes
  "Removes doublequotes from the start and end of a string if any."
  [^String s]
  (if (and (> (count s) 1)
           (= \" (first s) (.charAt s (dec (count s)))))
    (.substring s 1 (dec (count s)))
    s))

(defn escape-html
  "Must have the form [:safe safe-string] to prevent escaping. Alternatively,
  you can call selmer.util/turn-off-escaping! to turn it off completely.

  If it is marked as :safe, the value will be returned as-is, otherwise it
  will be converted to a string even if it is not escaped."
  [x]
  (if (and (vector? x)
           (= :safe (first x)))
    (second x)
    (let [s (str x)]
      (escape-html* s))))

;;; Compile filters

(defn fix-filter-args
  "Map any sort of needed fixes to the arguments before passing them
to the filters. Only strips enclosing doublequotes for now."
  [args]
  ;; TODO - figure out what kind of extra args filters can take
  (map (fn [^String s]
         (strip-doublequotes s))
       args))

(defn lookup-args
  "Given a context map, return a function that accepts a filter
  argument and if it begins with @, return the value from the
  context map instead of treating it as a literal."
  [context-map]
  (fn [^String arg]
    (if (and (> (count arg) 1) (.startsWith arg "@"))
      (let [accessor (parse-accessor (subs arg 1))]
        (get-in context-map accessor arg))
      arg)))

(defn filter-str->fn
  "Turns a filter string like \"pluralize:y:ies\" into a function that
expects a value obtained from a context map or from a previously
applied filter."
  [s]
  (let [[filter-name & args]
        ;; Ignore colons inside doublequotes
        (re-seq #"(?:[^:\"]|\"[^\"]*\")+" s)
        args   (fix-filter-args args)
        filter (get-filter filter-name)]
    (if filter
      (fn [x context-map]
        (apply filter x (map (lookup-args context-map) args)))
      (exception "No filter defined with the name '" filter-name "'"))))

(def safe-filter ::selmer-safe-filter)

(defn literal? [^String val]
  (or
    (and (.startsWith val "\"") (.endsWith val "\""))
    (re-matches #"[0-9]+" val)))

(defn- parse-literal [^String val]
  (if (.startsWith val "\"")
    (subs val 1 (dec (count val)))
    val))

(defn- apply-filters [val s filter-strs filters context-map]
  (reduce
    (fn [acc [filter-str filter]]
      (try (filter acc context-map)
           (catch Exception e
             (exception
               "On filter body '" s "' and filter '" filter-str "' this error occurred:" (.getMessage e)))))
    val
    (map vector filter-strs filters)))

(defn get-accessor
  "Returns the value of `k` from map `m`, either as a keyword or string lookup."
  [m k]
  (let [v (get m k)]
    (if (nil? v)
      (when (keyword? k)
        (if-let [n (namespace k)]
          (get m (str n "/" (name k)))
          (get m (name k))))
      v)))

(defn split-value [s]
  (->> s
       (s/trim)
       ;; Ignore pipes and allow escaped doublequotes inside doublequotes
       (re-seq #"(?:[^|\"]|\"[^\"]*\")+")))

(defn compile-filter-body
  "Turns a string like foo|filter1:x|filter2:y into a fn that expects a
 context-map and will apply the filters one after the other to the value
 from the map. It will escape the end result unless the last
 filter is \"safe\" or when it's called with escape? equal to true,
 which is the default behavior."
  ([s] (compile-filter-body s true))
  ([s escape?]
   (let [[val & filter-strs] (split-value s)
         accessor (parse-accessor val)
         filters  (map filter-str->fn filter-strs)]
     (if (literal? val)
       (fn [context-map]
         (apply-filters
           (parse-literal val)
           s
           filter-strs
           filters
           context-map))
       (fn runtime-test [context-map]
         (let [val (reduce get-accessor context-map accessor)]
           (when (or (not (nil? val)) (and selmer.util/*filter-missing-values* (seq filters)))
             (let [x (apply-filters
                       val
                       s
                       filter-strs
                       filters
                       context-map)]
               ;; Escape by default unless the last filter is 'safe' or safe-filter is set in the context-map
               (cond
                 (safe-filter context-map) x
                 escape? (escape-html x)
                 :else x)))))))))
