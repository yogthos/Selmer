(ns selmer.validator
 (:use selmer.tags
       selmer.filters
       selmer.util
       [clojure.set :only [difference]]
       [clojure.java.io :only [reader]]))

(def error-template
"
<html>
   <head>
       <font face='arial'>
       <style type='text/css'>
           body {
               margin: 0px;
               background: #ececec;
           }
           #header {
               padding-top:  5px;
               padding-left: 25px;
               color: white;
               background: #a32306;
               text-shadow: 1px 1px #33333;
               border-bottom: 1px solid #710000;
           }
           #error-wrap {
               border-top: 5px solid #d46e6b;
           }
           #error-message {
               color: #800000;
               background: #f5a29f;
               padding: 10px;
               text-shadow: 1px 1px #FFBBBB;
               border-top: 1px solid #f4b1ae;
           }
           #file-wrap {
               border-top: 5px solid #2a2a2a;
           }
           #file {
               color: white;
               background: #333333;
               padding: 10px;
               padding-left: 20px;
               text-shadow: 1px 1px #555555;
               border-top: 1px solid #444444;
           }
           #line-number {
               width=20px;
               color: #8b8b8b;
               background: #d6d6d6;
               float: left;
               padding: 5px;
               text-shadow: 1px 1px #EEEEEE;
               border-right: 1px solid #b6b6b6;
           }
           #line-content {
               float: left;
               padding: 5px;
           }
           #error-content {
               float: left;
               width: 100%;
               border-top: 5px solid #cdcdcd;
               border-bottom: 5px solid #cdcdcd;
           }
       </style>
   </head>
   <body>
       <div id='header'>
           <h1>Template Compilation Error</h1>
       </div>
       <div id='error-wrap'>
           <div id='error-message'>{{error}}.</div>
       </div>
       {% if template %}
         <div id='file-wrap'>
             <div id='file'>In {{template}}{% if line %} on line {{line}}{% endif %}.</div>
         </div>
         {% for error in validation-errors %}
         <div id='error-content'>
           <div id='line-number'>{{error.line}}</div>
           <div id='line-content'>{{error.tag}}</div>
         </div>
         {% endfor %}
       {% endif %}
   </body>
</html>
")

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
              {:type           :selmer-validation-error
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

(defn valide-tag [template line tags {:keys [tag-name args tag-value tag-type] :as tag}]
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

(defn validate-tags [template]
  (with-open [rdr (reader template)]
    (loop [tags [], ch (read-char rdr), line 1]
      (if ch
        (if (open-tag? ch rdr)
          (let [tag-info
                (try (read-tag-info rdr)
                  (catch Exception ex
                    (validation-error (str "Error parsing the tag: " (.getMessage ex)) nil line template)))]
            (recur (valide-tag template line tags tag-info) (read-char rdr) line))
          (recur tags (read-char rdr) (if (= \newline ch) (inc line) line)))
        tags))))

(defn validate [template]
  (when @validate?
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
