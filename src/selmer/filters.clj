(ns selmer.filters
  "To create a filter use the function add-filter! which takes a name and a fn.
The first argument to the fn is always the value obtained from the context
map. The rest of the arguments are optional and are always strings."
  (:require [clojure.string :as s]
            [cheshire.core :as json :only [generate-string]])
  (:import [org.joda.time DateTime]
           [org.joda.time.format DateTimeFormat DateTimeFormatter]
           [org.apache.commons.codec.digest DigestUtils]))

;;; TODO - maybe dont let exceptions happen in filters

(def valid-date-formats
  {"shortDate"       (DateTimeFormat/shortDate)
   "shortTime"       (DateTimeFormat/shortTime)
   "shortDateTime"   (DateTimeFormat/shortDateTime)
   "mediumDate"      (DateTimeFormat/mediumDate)
   "mediumTime"      (DateTimeFormat/mediumTime)
   "mediumDateTime"  (DateTimeFormat/mediumDateTime)
   "longDate"        (DateTimeFormat/longDate)
   "longTime"        (DateTimeFormat/longTime)
   "longDateTime"    (DateTimeFormat/longDateTime)
   "fullDate"        (DateTimeFormat/fullDate)
   "fullTime"        (DateTimeFormat/fullTime)
   "fullDateTime"    (DateTimeFormat/fullDateTime)
   })
(defn ^DateTime fix-date
  [d]
  (cond (instance? DateTime d) d
        (instance? java.util.Date d) (DateTime. d)
        :else
        (try (DateTime. d)
             (catch Exception _
               (throw (IllegalArgumentException. (str d " is not a valid date format.")))))))

;;; Used in filters when we are expecting a collection but instead got nil or a number
;;; or something else just as useless.
;;; Some clojure functions silently do the wrong thing when given invalid arguments. This
;;; aims to prevent that.
(defn throw-when-expecting-seqable
  "Throws an exception with the given msg when (seq x) will fail (excluding nil)"
  [x & [msg]]
  (let [is-seqable (and (not (nil? x))
                        (or (seq? x)
                            (instance? clojure.lang.Seqable x)
                            (string? x)
                            (instance? Iterable x)
                            (-> ^Object x .getClass .isArray)
                            (instance? java.util.Map x)))
        ^String msg (if msg msg (str "Expected '" (if (nil? x) "nil" (str x)) "' to be a collection of some sort."))]
    (when-not is-seqable
      (throw (Exception. msg)))))

;;; Similar to the above only with numbers
(defn throw-when-expecting-number
  [x & [msg]]
  (let [^String msg (if msg msg (str "Expected '" (if (nil? x) "nil" (str x)) "' to be a number."))]
    (when-not (number? x)
      (throw (Exception. msg)))))

(defonce filters
  (atom
    {;;; Useful for doing crazy stuff like {{ foo|length-is:3|join:"/" }}
     ;;; Without blowing up I guess
     :str
     str

     ;;; Try to add the arguments as numbers
     ;;; If it fails concatenate them as strings
     :add
     (fn [x y & rest]
       (let [args (conj rest y x)]
         (try (apply +
                     (map #(Long/valueOf ^String %) args))
           (catch NumberFormatException _
             (apply str args)))))

     ;;; Add backslashes to quotes
     :addslashes
     (fn [s]
       (->> s
            (str)
            (mapcat (fn [c]
                      (if (or (= \" c) (= \' c))
                        [\\ c]
                        [c])))
            (apply str)))

     ;;; Center a string given a width
     :center
     (fn [s w]
       (let [s (str s)
             w (Long/valueOf (s/trim w))
             c (count s)
             l (Math/ceil (/ (- w c) 2))
             r (Math/floor (/ (- w c) 2))]
         (str
           (apply str (repeat l \space))
           s
           (apply str (repeat r \space)))))

     ;;; Format a date, expects an instance of DateTime (Joda Time) or Date.
     ;;; The format can be a key from valid-date-formats or a manually defined format
     ;;; Look in
     ;;; http://joda-time.sourceforge.net/apidocs/org/joda/time/format/DateTimeFormat.html
     ;;; for formatting help.
     ;;; You can also format time with this.
     :date
     (fn [d fmt]
       (let [fixed-date (fix-date d)
             ^DateTimeFormatter fmt (or (valid-date-formats fmt)
                                        (DateTimeFormat/forPattern fmt))]
         (.print fmt fixed-date)))

     ;;; Default if x is falsey
     :default
     (fn [x default]
       (if x
         x
         default))

     ;;; Default if coll is empty
     :default-if-empty
     (fn [coll default]
       (throw-when-expecting-seqable coll)
       (if (empty? coll)
         default
         coll))

     ;;; With no decimal places it rounds to 1 decimal place
     :double-format
     (fn [n & [decimal-places]]
       (throw-when-expecting-number n)
       (let [n (double n)]
         (format (str "%." (if decimal-places decimal-places "1") "f")
                 n)))

     :first
     (fn [coll]
       (throw-when-expecting-seqable coll)
       (first coll))

     ;;; Get the ith digit of a number
     ;;; 1 is the rightmost digit
     ;;; Returns the number if the index is out of bounds
     :get-digit
     (fn [n i]
       (let [nv (vec (str n))
             i (Long/valueOf ^String i)
             i (- (count nv) i)]
         (if (or (< i 0) (>= i (count nv)))
           n
           (let [d (nv i)]
             (if (= \. d)
               (nv (dec i))
               d)))))

     :hash
     (fn [s hash]
       (let [s (str s)]
         (case hash
           "md5" (DigestUtils/md5Hex s)
           "sha" (DigestUtils/shaHex s)
           "sha256" (DigestUtils/sha256Hex s)
           "sha384" (DigestUtils/sha384Hex s)
           "sha512" (DigestUtils/sha512Hex s)
           (throw (IllegalArgumentException. (str "'" hash "' is not a valid hash algorithm."))))))

     :join
     (fn [coll sep]
       (throw-when-expecting-seqable coll)
       (s/join sep coll))

     :json
     (fn [x] (json/generate-string x))

     :last
     (fn [coll]
       (throw-when-expecting-seqable coll)
       (if (vector? coll)
         (coll (dec (count coll)))
         (last coll)))

     ;;; Exception to the rule: nil counts to 0
     :length
     (fn [coll]
       (if (nil? coll)
         0
         (do
           (throw-when-expecting-seqable coll)
           (count coll))))

     ;;; Exception to the rule: nil counts to 0
     :count
     (fn [coll]
       (if (nil? coll)
         0
         (do
           (throw-when-expecting-seqable coll)
           (count coll))))

     ;;; Return true when the count of the coll matches the argument
     :length-is
     (fn [coll n]
       (when-not (nil? coll)
         (throw-when-expecting-seqable coll))
       (let [n (Long/valueOf ^String n)]
         (= n (count coll))))

     :count-is
     (fn [coll n]
       (when-not (nil? coll)
         (throw-when-expecting-seqable coll))
       (let [n (Long/valueOf ^String n)]
         (= n (count coll))))

     ;;; Single newlines become <br />, double newlines mean new paragraph
     :linebreaks
     (fn [s]
       (let [s (str s)
             br (s/replace s #"\n" "<br />")
             p (s/replace br #"<br /><br />" "</p><p>")
             c (s/replace p #"<p>$" "")]
         (if (re-seq #"</p>$" c)
           (str "<p>" c)
           (str "<p>" c "</p>"))))

     :linebreaks-br
     (fn [s]
       (let [s (str s)]
         (s/replace s #"\n" "<br />")))

     ;;; Display text with line numbers
     :linenumbers
     (fn [s]
       (let [s (str s)]
         (->> (s/split s #"\n")
              (map-indexed
               (fn [i line]
                 (str (inc i) ". " line)))
              (s/join "\n"))))

     :rand-nth
     (fn [coll]
       (throw-when-expecting-seqable coll)
       (rand-nth coll))

     ;;; Turns the to-remove string into a set of chars
     ;;; That are removed from the context string
     :remove
     (fn [s to-remove]
       (let [s (str s)
             to-remove (set to-remove)]
         (apply str (remove to-remove s))))

     ;;; Use like the following:
     ;;; You have {{ num-cherries }} cherr{{ num-cherries|pluralize:y:ies }}
     ;;; You have {{ num-walruses }} walrus{{ num-walruses|pluralize:es }}
     ;;; You have {{ num-messages }} message{{ num-messages|pluralize }}
     :pluralize
     (fn [n-or-coll & opts]
       (let [n (if (number? n-or-coll) n-or-coll
                   (do (throw-when-expecting-seqable n-or-coll)
                       (count n-or-coll)))
             plural (case (count opts)
                      0 "s"
                      1 (first opts)
                      2 (second opts))
             singular (case (count opts)
                        (list 0 1) ""
                        2 (first opts))]
         (if (== 1 n)
           singular
           plural)))

     ;;; Do not escape html
     :safe
     (fn [s] [:safe s])

     :lower
     (fn [s] (s/lower-case (str s)))

     :upper
     (fn [s] (s/upper-case (str s)))

     :capitalize
     (fn [s] (s/capitalize (str s)))

     ;; Capitalize every word
     :title
     (fn [s] (->> (s/split (str s) #" ")
                 (map s/capitalize)
                 (s/join " ")))

     :sort
     (fn [coll]
       (throw-when-expecting-seqable coll)
       (sort coll))

     ;;; Sort by a keyword
     :sort-by
     (fn [coll k]
       (throw-when-expecting-seqable coll)
       (sort-by (keyword k) coll))

     :sort-by-reversed
     (fn [coll k]
       (throw-when-expecting-seqable coll)
       (sort-by (keyword k) (comp - compare) coll))

     :sort-reversed
     (fn [coll]
       (throw-when-expecting-seqable coll)
       (sort (comp - compare) coll))

     ;;; Remove tags
     ;;; Use like {{ value|remove-tags:b:span }}
     :remove-tags
     (fn [s & tags]
       (if-not tags
         s
         (let [s (str s)
               tags (str "(" (s/join "|" tags) ")")
               opening (re-pattern (str "(?i)<" tags "(/?>|(\\s+[^>]*>))"))
               closing (re-pattern (str "(?i)</" tags ">"))]
           (-> s
               (s/replace opening "")
               (s/replace closing "")))))}))

(defn get-filter
  [name]
  (get @filters (keyword name)))

(defn call-filter
  [name & args]
  (apply (get-filter name) args))

(defn add-filter!
  [name f]
  (swap! filters assoc (keyword name) f))
