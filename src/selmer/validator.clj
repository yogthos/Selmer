(ns selmer.validator
 (:use selmer.tags
       selmer.filters
       selmer.util
       [clojure.set :only [difference]]
       [clojure.java.io :only [reader]]))

(defn exception [& args]
  (throw ^java.lang.Exception (Exception. ^String (apply str args))))

(defn validate-filters [template line tag-value]
  (let [tag-filters (map
                  #(-> ^String % (.split ":") first keyword)
                  (-> tag-value name (.split "\\|") rest))]
    (if-not (empty? (difference (set tag-filters) (set (keys @filters))))
      (exception "Unrecognized filter for " tag-value " on line " line " for template " template))))

(defn close-tags []
  (apply concat (vals @closing-tags)))

(defn handle-closing-tags [tags tag-name]
  (second (split-with #{tag-name} tags)))

(handle-closing-tags [:else :foo :endif] :else)

(defn valide-tag [template line tags {:keys [tag-name tag-value tag-type] :as tag}]
 (if (= :expr tag-type)
   (let [end-tags (get @closing-tags (-> tags last :tag-name))]
     (cond
       (nil? tag-name)
       (exception "nN tag name supplied for the tag on line " line " for template " template)

       (not-any? #{tag-name} (concat (close-tags) (keys @expr-tags)))
       (exception "Unrecognized tag: " (name tag-name) " on line " line " for template " template)

       (some #{tag-name} (close-tags))
       (let [tags (butlast tags)]
         (if
           (some #{tag-name} end-tags)
           (if (= tag-name (last end-tags))
             tags (conj tags (assoc tag :line line)))
           (exception "Orphaned closing tag " tag-name " on line " line " for template " template)))

       (get @closing-tags tag-name)
       (conj tags (assoc tag :line line))

       (some #{tag-name} (close-tags))
       (exception "Orphan closing tag " tag-name " on line " line " for template " template)))
   (do (validate-filters template line tag-value) tags)))

(defn validate-tags [template]
 (with-open [rdr (reader template)]
   (loop [tags [], ch (read-char rdr), line 1]
     (if ch
       (if (open-tag? ch rdr)
         (recur (valide-tag template line tags (read-tag-info rdr)) (read-char rdr) line)
         (recur tags (read-char rdr) (if (= \newline ch) (inc line) line)))
       tags))))

(defn validate [template]
  (let [orphan-tags (validate-tags template)]
    (when-not (empty? orphan-tags)
      (->> (validate-tags template)
           (map (fn [{:keys [tag-name line]}] (str "\n" tag-name " on line " line)))
           doall
           (apply str "The template contains orphan tags: ")
           exception))))


(validate (.getAbsolutePath (java.io.File. "test/templates/validation-test.html")))