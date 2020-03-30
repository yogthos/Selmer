(ns selmer.validator
  (:require
    [selmer.tags :refer :all]
    [selmer.filters :refer :all]
    [selmer.util :refer :all]
    [clojure.set :refer [difference]]
    [clojure.java.io :as io]))

(def error-template
  (slurp (io/resource "selmer-error-template.html")))

(def validate? (atom true))

(defn validate-on! [] (reset! validate? true))

(defn validate-off! [] (reset! validate? false))

(defn format-tag [{:keys [tag-name tag-value tag-type args]}]
  (condp = tag-type
    :expr (str *tag-open* *tag-second* " " (name tag-name) " " (if args (str (clojure.string/join args) " ")) *tag-second* *tag-close*)
    :filter (str *tag-open* *filter-open* (name tag-value) *filter-close* *tag-close*)
    (str tag-name " " tag-value " " tag-type " " args)))

(defn validation-error
  ([error tag line template]
   (validation-error
     (str error
          (if tag (str " " (format-tag tag)))
          (if line (str " on line " line))
          (if template (str " for template " template)))
     error line [{:tag tag :line line}] template))
  ([long-error short-error line error-tags template]
   (throw
     (ex-info long-error
              {:type           :selmer/validation-error
               :error          short-error
               :error-template error-template
               :line           line
               :template       template
               :validation-errors
                               (for [error error-tags]
                                 (update-in error [:tag] format-tag))}))))

(defn validate-filters [template line {:keys [tag-value] :as tag}]
  (let [tag-filters (map
                      #(-> ^String % (.split ":") first keyword)
                      (-> tag-value name (.split "\\|") rest))]
    (if-not (empty? (difference (set tag-filters) (set (keys @filters))))
      (validation-error (str "Unrecognized filter " tag-value " found inside the tag") tag line template))))

(defn close-tags []
  (apply concat (vals @closing-tags)))

(defn validate-tag [template line tags {:keys [tag-name args tag-value tag-type] :as tag}]
  (condp = tag-type
    :expr
    (let [last-tag (last tags)
          end-tags (get @closing-tags (:tag-name last-tag))]
      (doseq [arg args] (validate-filters template line (assoc tag :tag-value arg)))
      (cond
        (nil? tag-name)
        (validation-error "No tag name supplied for the tag" tag line template)

        (not-any? #{tag-name} (concat (close-tags) (keys @expr-tags)))
        (validation-error "Unrecognized tag found" tag line template)

        ;; check if we have closing tag
        ;; handle the case where it's an intermediate tag
        ;; throw an exception if it doesn't belong to the last open tag
        (some #{tag-name} (close-tags))
        (let [tags (vec (butlast tags))]
          (if (some #{tag-name} end-tags)
            (if (not-empty (get @closing-tags tag-name))
              (conj tags (assoc tag :line line)) tags)
            (validation-error "No closing tag found for the tag" last-tag (:line last-tag) template)))

        (not-empty (get @closing-tags tag-name))
        (conj tags (assoc tag :line line))

        (some #{tag-name} (close-tags))
        (validation-error "Found an orphan closing tag" tag line template)

        :else tags))
    :filter
    (do (validate-filters template line tag) tags)))

(defn skip-verbatim-tags [tag-info rdr line template]
  (if (= :verbatim (:tag-name tag-info))
    (loop [ch (read-char rdr)]
      (if ch
        (if-not (and
                  (open-tag? ch rdr)
                  (= :endverbatim (:tag-name (read-tag-info rdr))))
          (recur (read-char rdr)))))
    tag-info))

(defn read-tag [rdr line template]
  (try
    (-> (read-tag-info rdr) (skip-verbatim-tags rdr line template))
    (catch Exception ex
      (validation-error (str "Error parsing the tag: " (.getMessage ex)) nil line template))))

(defn validate-tags [template]
  (with-open [rdr (io/reader template)]
    (loop [tags [], ch (read-char rdr), line 1]
      (if ch
        (if (open-tag? ch rdr)
          (if-let [tag-info (read-tag rdr line template)]
            (recur (validate-tag template line tags tag-info) (read-char rdr) line)
            (recur tags (read-char rdr) line))
          (recur tags (read-char rdr) (if (= \newline ch) (inc line) line)))
        tags))))

(defn validate [template]
  (when @validate?
    (check-template-exists template)
    (if-let [orphan-tags (not-empty (validate-tags template))]
      (validation-error
        (->> orphan-tags
             (map (fn [{:keys [tag-name line] :as tag}] (str (format-tag tag) " on line " line)))
             (interpose ", ")
             doall
             (clojure.string/join "The template contains orphan tags: "))
        "The template contains orphan tags."
        nil
        orphan-tags
        template))))
