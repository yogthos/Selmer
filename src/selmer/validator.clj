(ns selmer.validator
 (:use selmer.tags
       selmer.filters
       selmer.util
       [clojure.set :only [difference]]
       [clojure.java.io :only [reader]]))

(def validate? (atom true))

(defn validate-on! [] (reset! validate? true))

(defn validate-off! [] (reset! validate? false))

(defn format-tag [{:keys [tag-name tag-value tag-type args]}]
  (condp = tag-type
    :expr (str *tag-open* *tag-second* " " (name tag-name) " " (if args (str (apply str args) " ")) *tag-second* *tag-close*)
    :filter (str *filter-open* *tag-second* (name tag-value) *tag-second* *filter-close*)))

(defn validate-filters [template line {:keys [tag-value] :as tag}]
  (let [tag-filters (map
                  #(-> ^String % (.split ":") first keyword)
                  (-> tag-value name (.split "\\|") rest))]
    (if-not (empty? (difference (set tag-filters) (set (keys @filters))))
      (exception "Unrecognized filter " tag-value " in tag " (format-tag tag) " on line " line " for template " template))))

(defn close-tags []
  (apply concat (vals @closing-tags)))

(defn valide-tag [template line tags {:keys [tag-name args tag-value tag-type] :as tag}]
 (condp = tag-type
   :expr
   (let [last-tag (last tags)
         end-tags (get @closing-tags (:tag-name last-tag))]
     (doseq [arg args] (validate-filters template line (assoc tag :tag-value arg)))
     (cond
       (nil? tag-name)
       (exception "No tag name supplied for the tag on line " line " for template " template)

       (not-any? #{tag-name} (concat (close-tags) (keys @expr-tags)))
       (exception "Unrecognized tag: " (format-tag tag) " on line " line " for template " template)

       ;; check if we have closing tag
       ;; handle the case where it's an intermediate tag
       ;; throw an exception if it doesn't belong to the last open tag
       (some #{tag-name} (close-tags))
       (let [tags (vec (butlast tags))]
         (if (some #{tag-name} end-tags)
           (if (get @closing-tags tag-name)
             (conj tags (assoc tag :line line)) tags)
           (exception
             "Tag " (format-tag last-tag)" was not closed on line " (:line last-tag) " for template " template)))

       (get @closing-tags tag-name)
       (conj tags (assoc tag :line line))

       (some #{tag-name} (close-tags))
       (exception "Orphan closing tag " (format-tag tag) " on line " line " for template " template)

       :else tags))
   :filter
   (do (validate-filters template line tag) tags)))

(defn validate-tags [template]
 (with-open [rdr (reader template)]
   (loop [tags [], ch (read-char rdr), line 1]
     (if ch
       (if (open-tag? ch rdr)
         (recur (valide-tag template line tags (read-tag-info rdr)) (read-char rdr) line)
         (recur tags (read-char rdr) (if (= \newline ch) (inc line) line)))
       tags))))

(defn validate [template]
  (when @validate?
    (let [orphan-tags (validate-tags template)]
      (when-not (empty? orphan-tags)
        (->> (validate-tags template)
             (map (fn [{:keys [tag-name line] :as tag}] (str (format-tag tag) " on line " line)))
             (interpose ", ")
             doall
             (apply str "The template contains orphan tags: ")
             exception)))))
