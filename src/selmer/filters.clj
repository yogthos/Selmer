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

;;; Try to add the arguments as numbers
;;; If it fails concatenate them as strings
(add-filter!
 :add
 (fn [x y & rest]
   (let [args (conj rest y x)]
     (try (apply +
                 (map #(Long/valueOf ^String %) args))
          (catch NumberFormatException _
            (apply str args))))))

;;; Add backslashes to quotes
(add-filter!
 :addslashes
 (fn [s]
   (->> s
        (mapcat (fn [c]
                  (if (= \' c)
                    [\\ c]
                    [c])))
        (apply str))))

(add-filter!
 :capitalize
 s/capitalize)

;;; Center a string given a width
(add-filter!
 :center
 (fn [s w]
   (let [w (Long/valueOf (s/trim w))
         c (count s)
         l (Math/ceil (/ (- w c) 2))
         r (Math/floor (/ (- w c) 2))]
     (str
      (apply str (repeat l \space))
      s
      (apply str (repeat r \space))))))

(add-filter!
 :count
 count)

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

;;; Format a date, expects an instance of DateTime (Joda Time) or Date.
;;; The format can be a key from valid-date-formats or a manually defined format
;;; Look in
;;; http://joda-time.sourceforge.net/apidocs/org/joda/time/format/DateTimeFormat.html
;;; for formatting help.
;;; You can also format time with this.
(defn ^DateTime fix-date
  [d]
  (cond (instance? DateTime d) d
        (instance? java.util.Date d) (DateTime. d)
        :else
        (try (DateTime. d)
             (catch Exception _
               (throw (IllegalArgumentException. (str d " is not a valid date format.")))))))

(add-filter!
 :date
 (fn [d fmt]
   (let [fixed-date (fix-date d)
         ^DateTimeFormatter fmt (or (valid-date-formats fmt)
                                    (DateTimeFormat/forPattern fmt))]
     (.print fmt fixed-date))))

;;; Default if x is falsey
(add-filter!
 :default
 (fn [x default]
   (if x
     x
     default)))

;;; Default if coll is empty
(add-filter!
 :default-if-empty
 (fn [coll default]
   (if (empty? coll)
     default
     coll)))

;;; With no decimal places it rounds to 1 decimal place
(add-filter!
 :double-format
 (fn [n & [decimal-places]]
   (let [n (double n)]
     (format (str "%." (if decimal-places decimal-places "1") "f")
             n))))

(add-filter!
 :first
 first)

;;; Get the ith digit of a number
;;; 1 is the rightmost digit
(add-filter!
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
           d))))))

(add-filter!
 :hash
 (fn [^String s hash]
   (case hash
     "md5" (DigestUtils/md5Hex s)
     "sha" (DigestUtils/shaHex s)
     "sha256" (DigestUtils/sha256Hex s)
     "sha384" (DigestUtils/sha384Hex s)
     "sha512" (DigestUtils/sha512Hex s)
     (throw (IllegalArgumentException. (str hash " is not a valid hash algorithm."))))))

(add-filter!
 :join
 (fn [coll sep]
   (s/join sep coll)))

(add-filter!
 :json
 (fn [s]
   (json/generate-string s)))

(add-filter!
 :last
 (fn [coll]
   (if (vector? coll)
     (coll (dec (count coll)))
     (last coll))))

(add-filter!
 :length
 count)

;;; Return true when the count of the coll matches the argument
(add-filter!
 :length-is
 (fn [coll n]
   (let [n (Long/valueOf ^String n)]
     (= n (count coll)))))

;;; Single newlines become <br />, double newlines mean new paragraph
(add-filter!
 :linebreaks
 (fn [s]
   (let [br (s/replace s #"\n" "<br />")
         p (s/replace br #"<br /><br />" "</p><p>")
         c (s/replace p #"<p>$" "")]
     (if (re-seq #"</p>$" c)
       (str "<p>" c)
       (str "<p>" c "</p>")))))

(add-filter!
 :linebreaks-br
 (fn [s]
   (s/replace s #"\n" "<br />")))

;;; Display text with line numbers
(add-filter!
 :linenumbers
 (fn [s]
   (->> (s/split s #"\n")
        (map-indexed
           (fn [i line]
             (str (inc i) ". " line)))
        (s/join "\n"))))

(add-filter!
 :lower
 (fn [s]
   (when s
     (s/lower-case s))))

;;; Use like the following:
;;; You have {{ num-cherries }} cherr{{ num-cherries|pluralize:y:ies }}
;;; You have {{ num-walruses }} walrus{{ num-walruses|pluralize:es }}
;;; You have {{ num-messages }} message{{ num-messages|pluralize }}
(add-filter!
 :pluralize
 (fn [n-or-coll & opts]
   (let [n (if (number? n-or-coll) n-or-coll (count n-or-coll))
         plural (case (count opts)
                  0 "s"
                  1 (first opts)
                  2 (second opts))
         singular (case (count opts)
                    (list 0 1) ""
                    2 (first opts))]
     (if (== 1 n)
       singular
       plural))))

(add-filter!
 :rand-nth
 rand-nth)

;;; Turns the to-remove string into a set of chars
;;; That are removed from the context string
(add-filter!
 :remove
 (fn [s to-remove]
   (let [to-remove (set to-remove)]
     (apply str (remove to-remove s)))))

;;; Remove tags
;;; Use like {{ value|remove-tags:b:span }}
(add-filter!
 :remove-tags
 (fn [s & tags]
   (if-not tags
     s
     (let [re (->> tags
                   (map (fn [t]
                          (str "<" t ">|</" t ">"))))
           re (re-pattern (str "(?:" (s/join "|" re) ")"))]
       (s/replace s re "")))))

;;; Do not escape html
(add-filter!
 :safe
 (fn [s]
   [:safe s]))

(add-filter!
 :sort
 sort)

;;; Sort by a keyword
(add-filter!
 :sort-by
 (fn [coll k]
   (sort-by (keyword k) coll)))

(add-filter!
 :sort-reversed
 (fn [coll]
   (sort (comp - compare) coll)))

(add-filter!
 :sort-by-reversed
 (fn [coll k]
   (sort-by (keyword k) (comp - compare) coll)))

(add-filter!
 :upper
 (fn [s]
   (when s
     (s/upper-case s))))
