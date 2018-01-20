(ns selmer.parser
  " Parsing and handling of compile-time vs.
  run-time. Avoiding unnecessary work by pre-processing
  the template structure and content and reacting to
  the runtime context map with a prepared data structure
  instead of a raw template. Anything other than a raw tag
  value injection is a runtime dispatch fn. Compile-time here
  means the first time we see a template *at runtime*, not the
  implementation's compile-time. "
  (:require [selmer.template-parser :refer [preprocess-template]]
            [selmer.filters :refer [filters]]
            [selmer.filter-parser :refer [compile-filter-body]]
            [selmer.tags :refer :all]
            [selmer.util :refer :all]
            [selmer.validator :refer [validation-error]]
            selmer.node)
  (:import [selmer.node INode TextNode FunctionNode]))

;; Ahead decl because some fns call into each other.

(declare parse parse-input parse-file tag-content)

;; Memoization atom for templates. If you pass a filepath instead
;; of a string, we'll use the last-modified timestamp to cache the
;; template. Works fine for active local development and production.

(defonce templates (atom {}))

;; Can be overridden by closure/argument 'cache
(defonce cache? (atom true))

(defn cache-on! []
  (reset! cache? true))

(defn cache-off! []
  (reset! cache? false))

(defn- append-slash
  "append '/' to the given string unless it already ends with a slash"
  [^String s]
  (if (or (nil? s)
          (.endsWith s "/"))
    s
    (str s "/")))

(defn- make-resource-path
  [path]
  (cond
    (nil? path)
      nil
    (instance? java.net.URL path)
      (append-slash (str path))
    :else
      (append-slash
       (try
         (str (java.net.URL. path))
         (catch java.net.MalformedURLException err
           (str "file:///" path))))))

(defn set-resource-path!
  "set custom location, where templates are being searched for. path
  may be a java.net.URL instance or a string. If it's a string, we
  first try to convert it to a java.net.URL instance and if it doesn't
  work it's interpreted as a path in the local filesystem."
  [path]
  (set-custom-resource-path! (make-resource-path path)))

(defn update-tag [tag-map tag tags]
  (assoc tag-map tag (concat (get tag-map tag) tags)))

(defn set-closing-tags! [& tags]
  (loop [[tag & tags] tags]
    (when tag
      (swap! selmer.tags/closing-tags update-tag tag tags)
      (recur tags))))

;; add-tag! is a hella nifty macro. Example use:
;; (add-tag! :joined (fn [args context-map] (clojure.string/join "," args)))
(defmacro add-tag!
  " tag name, fn handler, and maybe tags "
  [k handler & tags]
  `(do
     (set-closing-tags! ~k ~@tags)
     (swap! selmer.tags/expr-tags assoc ~k (tag-handler ~handler ~k ~@tags))))

(defn remove-tag!
  [k]
  (swap! expr-tags dissoc k)
  (swap! closing-tags dissoc k))

;; render-template renders at runtime, accepts
;; post-parsing vectors of INode elements.

(defn render-template [template context-map]
  " vector of ^selmer.node.INodes and a context map."
  (let [buf (StringBuilder.)]
    (doseq [^selmer.node.INode element template]
        (if-let [value (.render-node element context-map)]
          (.append buf value)
          (.append buf (*missing-value-formatter* (:tag (meta element)) context-map))))
    (.toString buf)))

(defn render [s context-map & [opts]]
  " render takes the string, the context-map and possibly also opts. "
  (render-template (parse parse-input (java.io.StringReader. s) opts) context-map))


;; Primary fn you interact with as a user, you pass a path that
;; exists somewhere in your class-path, typically something like
;; resources/templates/template_name.html. You also pass a context
;; map and potentially opts. Smart (last-modified timestamp)
;; auto-memoization of compiler output.

(defn render-file [filename-or-url context-map & [{:keys [cache custom-resource-path]
                                            :or  {cache @cache?
                                                  custom-resource-path *custom-resource-path*}
                                            :as opts}]]
  " Parses files if there isn't a memoized post-parse vector ready to go,
  renders post-parse vector with passed context-map regardless. Double-checks
  last-modified on files. Uses classpath for filename-or-url path "
  (binding [*custom-resource-path* (make-resource-path custom-resource-path)]
    (if-let [resource (resource-path filename-or-url)]
      (let [{:keys [template last-modified]} (get @templates resource)
            ;;for some resources, such as ones inside a jar, it's
            ;;not possible to check the last modified timestamp
            last-modified-time (if (or (nil? last-modified) (pos? last-modified))
                                 (resource-last-modified resource) -1)]
        (check-template-exists resource)
        (if (and cache last-modified (= last-modified last-modified-time))
          (render-template template context-map)
          (let [template (parse parse-file filename-or-url opts)]
            (swap! templates assoc resource {:template template
                                             :last-modified last-modified-time})
            (render-template template context-map))))
      (validation-error
       (str "resource-path for " filename-or-url " returned nil, typically means the file doesn't exist in your classpath.")
       nil nil nil))))

;; For a given tag, get the fn handler for the tag type,
;; pass it the arguments, tag-content, render-template fn,
;; and reader.

(defn expr-tag [{:keys [tag-name args] :as tag} rdr]
  (if-let [handler (tag-name @expr-tags)]
    (handler args tag-content render-template rdr)
    (exception "unrecognized tag: " tag-name " - did you forget to close a tag?")))

;; Same as a vanilla data tag with a value, but composes
;; the filter fns. Like, {{ data-var | upper | safe }}
;; (-> {:data-var "woohoo"} upper safe) => "WOOHOO"
;; Happens at compile-time.

(defn filter-tag [{:keys [tag-value]}]
  " Compile-time parser of var tag filters. "
  (compile-filter-body tag-value))

;; Generally either a filter tag, if tag, ifequal,
;; or for. filter-tags are conflated with vanilla tag

(defn parse-tag [{:keys [tag-type] :as tag} rdr]
  (with-meta
    (if (= :filter tag-type)
      (filter-tag tag)
      (expr-tag tag rdr))
    {:tag tag}))

;; Parses and detects tags which turn into
;; FunctionNode call-sites or TextNode content. open-tag? fn returns
;; true or false based on character lookahead to see if it's {{ or {%

(defn append-node [content tag ^StringBuilder buf rdr]
  (-> content
    (conj (TextNode. (.toString buf)))
    (conj (FunctionNode. (parse-tag tag rdr)))))

(defn update-tags [tag tags content args ^StringBuilder buf]
  (assoc tags tag
         {:args args
          :content (conj content (TextNode. (.toString buf)))}))

(defn tag-content [rdr start-tag & end-tags]
  (let [buf (StringBuilder.)]
    (loop [ch       (read-char rdr)
           tags     {}
           content  []
           cur-tag  start-tag
           end-tags end-tags]
      (cond
        (and (nil? ch) (not-empty end-tags))
        (exception "No closing tag found for " start-tag)
        (nil? ch)
        tags
        (open-tag? ch rdr)
        (let [{:keys [tag-name args] :as tag} (read-tag-info rdr)]
          (if-let [open-tag  (and tag-name (some #{tag-name} end-tags))]
              (let [tags     (update-tags cur-tag tags content args buf)
                    end-tags (next (drop-while #(not= tag-name %) end-tags))]
                (.setLength buf 0)
                (recur (when-not (empty? end-tags) (read-char rdr)) tags [] open-tag end-tags))
              (let [content (append-node content tag buf rdr)]
                (.setLength buf 0)
                (recur (read-char rdr) tags content cur-tag end-tags))))
        :else
        (do
          (.append buf ch)
          (recur (read-char rdr) tags content cur-tag end-tags))))))

(defn skip-short-comment-tag [template rdr]
  (loop [ch1 (read-char rdr)
         ch2 (read-char rdr)]
    (cond
      (nil? ch2)
      (exception "short-form comment tag was not closed")
      (and (= *short-comment-second* ch1) (= *tag-close* ch2))
      template
      :else (recur ch2 (read-char rdr)))))

;; Compile-time parsing of tags. Accumulates a transient vector
;; before returning the persistent vector of INodes (TextNode, FunctionNode)

(defn add-node [template buf rdr]
  (let [template (if-let [text (not-empty (.toString ^StringBuilder buf))]
                   (conj! template (TextNode. text))
                   template)]
    (.setLength ^StringBuilder buf 0)
    (conj! template (FunctionNode. (parse-tag (read-tag-info rdr) rdr)))))

(defn parse* [input]
  (with-open [rdr (clojure.java.io/reader input)]
      (let [buf      (StringBuilder.)]
        (loop [template (transient [])
               ch (read-char rdr)]
          (if ch
            (cond
              ;; We hit a tag so we append the buffer content to the template
              ;; and empty the buffer, then we proceed to parse the tag
              (and (open-tag? ch rdr) (contains? #{*tag-second* *filter-open*} (peek-rdr rdr)))
              (recur (add-node template buf rdr) (read-char rdr))

              ;; Short comment tags are dropped
              (open-short-comment? ch rdr)
              (recur (skip-short-comment-tag template rdr) (read-char rdr))

              ;; Default case, here we append the character and
              ;; read the next char
              :else
              (do
                (.append buf ch)
                (recur template (read-char rdr))))

            ;; Add the leftover content of the buffer and return the template
            (->> buf (.toString) (TextNode.) (conj! template) persistent!))))))

;; Primary compile-time parse routine. Work we don't want happening after
;; first template render. Vector output from parse* gets memoized by render-file.

(defn parse-input [input & [{:keys [custom-tags custom-filters]}]]
  (swap! expr-tags merge custom-tags)
  (swap! filters merge custom-filters)
  (parse* input))

;; File-aware parse wrapper.

(defn parse-file [file params]
  (-> file preprocess-template (java.io.StringReader.) (parse-input params)))

(defn parse [parse-fn input & [{:keys [tag-open tag-close filter-open filter-close tag-second short-comment-second]
                                :or   {tag-open             *tag-open*
                                       tag-close            *tag-close*
                                       filter-open          *filter-open*
                                       filter-close         *filter-close*
                                       tag-second           *tag-second*
                                       short-comment-second *short-comment-second*}
                                :as   params}]]
  (binding [*tag-open*             tag-open
            *tag-close*            tag-close
            *filter-open*          filter-open
            *filter-close*         filter-close
            *tag-second*           tag-second
            *short-comment-second* short-comment-second
            *tag-second-pattern*   (pattern tag-second)
            *filter-open-pattern*  (pattern "\\" tag-open "\\" filter-open "\\s*")
            *filter-close-pattern* (pattern "\\s*\\" filter-close "\\" tag-close)
            *filter-pattern*       (pattern "\\" tag-open "\\" filter-open "\\s*.*\\s*\\" filter-close "\\" tag-close)
            *tag-open-pattern*     (pattern "\\" tag-open "\\" tag-second "\\s*")
            *tag-close-pattern*    (pattern "\\s*\\" tag-second "\\"  tag-close)
            *tag-pattern*          (pattern "\\" tag-open "\\" tag-second "\\s*.*\\s*\\" tag-second "\\" tag-close)
            *include-pattern*      (pattern "\\" tag-open "\\" tag-second "\\s*include.*")
            *extends-pattern*      (pattern "\\" tag-open "\\" tag-second "\\s*extends.*")
            *block-pattern*        (pattern "\\" tag-open "\\" tag-second "\\s*block.*")
            *block-super-pattern*  (pattern "\\" tag-open "\\" filter-open "\\s*block.super\\s*\\" filter-close "\\" tag-close)
            *endblock-pattern*     (pattern "\\" tag-open "\\" tag-second "\\s*endblock.*")]
    (parse-fn input params)))
