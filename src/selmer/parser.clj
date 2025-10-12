(ns selmer.parser
  " Parsing and handling of compile-time vs.
  run-time. Avoiding unnecessary work by pre-processing
  the template structure and content and reacting to
  the runtime context map with a prepared data structure
  instead of a raw template. Anything other than a raw tag
  value injection is a runtime dispatch fn. Compile-time here
  means the first time we see a template *at runtime*, not the
  implementation's compile-time. "
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   [selmer.template-parser :refer [preprocess-template]]
   [selmer.filters :refer [filters]]
   [selmer.filter-parser :refer [compile-filter-body literal?
                                 split-value parse-literal]]
   [selmer.tags :refer :all]
   [selmer.util :refer :all]
   [selmer.validator :refer [validation-error]]
   selmer.node)
  (:import [selmer.node TextNode FunctionNode]))

;; Ahead decl because some fns call into each other.

(declare parse parse-input parse-str parse-file tag-content)

;; Memoization atom for templates. If you pass a filepath instead
;; of a string, we'll use the last-modified timestamp to cache the
;; template. Works fine for active local development and production.

(defonce templates (atom {}))

;; Can be overridden by closure/argument 'cache
(defonce cache? (atom true))

(defn clear-cache! []
  (reset! templates {}))

(defn cache-on! []
  (reset! cache? true))

(defn cache-off! []
  (clear-cache!)
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
    (if (or (looks-like-absolute-file-path? path)
            (.startsWith ^java.lang.String path "file:/"))
      (append-slash
       (try
         (str (java.net.URL. path))
         (catch java.net.MalformedURLException err
           (str "file:///" path))))
      (append-slash path))))

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

(def add-filter! selmer.filters/add-filter!)

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

(defn render-template
  " vector of ^selmer.node.INodes and a context map."
  [template context-map]
  (let [buf (StringBuilder.)]
    (doseq [^selmer.node.INode element template]
      (if-let [value (.render-node element context-map)]
        (.append buf value)
        (.append buf (*missing-value-formatter* (:tag (meta element)) context-map))))
    (.toString buf)))

(defn render
  " render takes the string, the context-map and possibly also opts. "
  [s context-map & [opts]]
  (render-template (parse parse-str s opts) context-map))

;; Primary fn you interact with as a user, you pass a path that
;; exists somewhere in your class-path, typically something like
;; resources/templates/template_name.html. You also pass a context
;; map and potentially opts. Smart (last-modified timestamp)
;; auto-memoization of compiler output.

(defn render-file
  " Parses files if there isn't a memoized post-parse vector ready to go,
  renders post-parse vector with passed context-map regardless. Double-checks
  last-modified on files. Uses classpath for filename-or-url path "
  [filename-or-url context-map & [{:keys [cache custom-resource-path url-stream-handler]
                                   :or   {cache                @cache?
                                          custom-resource-path *custom-resource-path*
                                          url-stream-handler   *url-stream-handler*}
                                   :as   opts}]]
  (binding [*custom-resource-path* (make-resource-path custom-resource-path)
            *url-stream-handler* url-stream-handler]
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
            (swap! templates assoc resource {:template      template
                                             :last-modified last-modified-time})
            (render-template template context-map))))
      (validation-error
       (str "resource-path for " filename-or-url " returned nil, typically means the file doesn't exist in your classpath.")
       nil nil nil))))

;; For a given tag, get the fn handler for the tag type,
;; pass it the arguments, tag-content, render-template fn,
;; and reader.

(defn expr-tag [{:keys [tag-name args]} rdr]
  (if-let [handler (tag-name @expr-tags)]
    (handler args tag-content render-template rdr)
    (throw (ex-info (str "unrecognized tag: " tag-name
                         " - did you forget to close a tag?")
                    {}))))

;; Same as a vanilla data tag with a value, but composes
;; the filter fns. Like, {{ data-var | upper | safe }}
;; (-> {:data-var "woohoo"} upper safe) => "WOOHOO"
;; Happens at compile-time.

(defn filter-tag
  " Compile-time parser of var tag filters. "
  [{:keys [tag-value]}]
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

(defn ensure-list
  "Turns the argument into a list if it isn't a list already."
  [list-maybe]
  (if (sequential? list-maybe)
    list-maybe
    (if (nil? list-maybe)
      []
      [list-maybe])))

(defn update-tags
  "Assocs in the passed tag to the tags map."
  [tag tags content args ^StringBuilder buf]
  (let [content {:args    args
                 :content (conj content (TextNode. (.toString buf)))}]
    (if-let [tag-already-there (tags tag)]
      ; if the tag is already in there, it's elif which can be duplicated.
      ; in this case, we want to make a list and put it in there, as we need all the elif tags later.
      (assoc tags tag (conj (ensure-list tag-already-there)
                            content))
      (assoc tags tag content))))

(defn skip-short-comment-tag [rdr]
  (loop [ch1 (read-char rdr)
         ch2 (read-char rdr)]
    (cond
      (nil? ch2)
      (throw (ex-info "short-form comment tag was not closed" {}))

      (and (= *short-comment-second* ch1) (= *tag-close* ch2))
      nil

      :else
      (recur ch2 (read-char rdr)))))

(defn tag-content
  "Parses the content of a tag.
   Returns a map of tag-name -> args & content, which can then be interpreted by the calling function."
  [rdr start-tag & end-tags]
  (let [buf (StringBuilder.)]
    (loop [ch       (read-char rdr)
           tags     {}
           content  []
           cur-tag  start-tag ; for example, if
           end-tags end-tags ; for example, [elif, else, endif]
           cur-args nil]
      (cond
        (and (nil? ch) (not-empty end-tags))
        (throw (ex-info (str "No closing tag found for " start-tag)
                        {:args start-tag}))

        ; We're done with this tag so return.
        (nil? ch)
        tags

        ; Skip any short form comments
        (open-short-comment? ch rdr)
        (do (skip-short-comment-tag rdr)
            (recur (read-char rdr) tags content cur-tag end-tags cur-args))

        ; A tag was found inside this tag
        (open-tag? ch rdr)
        (let [{:keys [tag-name args] :as tag} (read-tag-info rdr)]
          ; Determine if the tag belongs to the opening tag on this level
          (if-let [open-tag (and tag-name (some #{tag-name} end-tags))]
            ; This tag is part of the already open tag, like how else or endif belongs to the if tag.
            ; Since we have reached the end of this cluse we empty the contents of the buffer into the tags list.
            (let [tags     (update-tags cur-tag tags content cur-args buf)
                  ; special case to allow an arbitrary number of elif tags
                  end-tags (if (= open-tag :elif)
                             end-tags
                             ; but non-elif tags can only used once, so remove from the possible options
                             (next (drop-while #(not= tag-name %) end-tags)))]
              ; clear the buffer - it's been written inside update-tags
              (.setLength buf 0)
              (recur (when-not (empty? end-tags) (read-char rdr))
                     tags
                     []
                     open-tag
                     end-tags
                     args))

            ; The detected tag is not part of the open tag.
            ; Recursively reading the new tag and adding it to content.
            (let [content (append-node content tag buf rdr)]
              (.setLength buf 0)
              (recur (read-char rdr) tags content cur-tag end-tags cur-args))))

        ; Just a normal letter
        :else
        (do
          (.append buf ch)
          (recur (read-char rdr) tags content cur-tag end-tags cur-args))))))

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
    (let [buf (StringBuilder.)]
      (loop [template (transient [])
             ch       (read-char rdr)]
        (if ch
          (cond
            ;; We hit a tag so we append the buffer content to the template
            ;; and empty the buffer, then we proceed to parse the tag
            (and (open-tag? ch rdr) (contains? #{*tag-second* *filter-open*} (peek-rdr rdr)))
            (recur (add-node template buf rdr) (read-char rdr))

            ;; Short comment tags are dropped
            (open-short-comment? ch rdr)
            (do
              (skip-short-comment-tag rdr)
              (recur template (read-char rdr)))

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

;; File-aware parse wrapper for string.

(defn parse-str [input params]
  (-> (char-array input) preprocess-template (java.io.StringReader.) (parse-input params)))

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
            *tag-close-pattern*    (pattern "\\s*\\" tag-second "\\" tag-close)
            *tag-pattern*          (pattern "\\" tag-open "\\" tag-second "\\s*.*\\s*\\" tag-second "\\" tag-close)
            *include-pattern*      (pattern "\\" tag-open "\\" tag-second "\\s*include.*")
            *extends-pattern*      (pattern "\\" tag-open "\\" tag-second "\\s*extends.*")
            *block-pattern*        (pattern "\\" tag-open "\\" tag-second "\\s*block.*")
            *block-super-pattern*  (pattern "\\" tag-open "\\" filter-open "\\s*block.super\\s*\\" filter-close "\\" tag-close)
            *endblock-pattern*     (pattern "\\" tag-open "\\" tag-second "\\s*endblock.*")
            *tags*                 (atom [])]
    (with-meta
      (parse-fn input params)
      {:all-tags @*tags*})))

(defn- parse-variable-paths
  "
  takes in vals like: \"person.name|capitalize\"
  and produces [:person :name]
  "
  [arg]
  (some-> arg split-value first parse-accessor))

;; List of variables in a template file
(defn ^:private parse-variables [tags]
  (loop [vars        #{}
         nested-keys #{}
         tags        tags]
    (if-let [{:keys [tag-type tag-name tag-value args] :as tag} (first tags)]
      (cond
        (= :filter tag-type) (let [v               (parse-variable-paths tag-value)
                                   should-add-var? (when (vector? v)
                                                     (not (contains? nested-keys (first v))))
                                   updated-vars    (cond-> vars
                                                     should-add-var? (conj v))]
                               (recur
                                updated-vars
                                nested-keys
                                (rest tags)))
        (= :for tag-name)    (let [[ids [_ items]] (aggregate-args args)]
                               (recur
                                (conj vars (parse-variable-paths items))
                                (conj (set (map keyword ids)) :forloop)
                                (rest tags)))

        (= :with tag-name)   (let [[id value] (string/split (first args) #"=")]
                               (recur
                                (conj vars (parse-variable-paths value))
                                #{(keyword id)}
                                (rest tags)))

        (contains? #{:endfor :endwith} tag-name) (recur vars #{} (rest tags))

        :else
        (let [special-syms   #{nil :not :all :any :< :> := :<= :>=}
              should-remove? (fn [[var-head :as var]]
                               (or
                                (special-syms var-head)
                                (nested-keys  var-head)))]

          (recur (set/union
                  vars
                  (->> args
                       (filter (complement literal?))
                       (map parse-variable-paths)
                       (remove should-remove?)
                       set))
                 nested-keys
                 (rest tags))))
      vars)))

(defn known-variable-paths [input & [opts]]
  (->> (or opts {})
       (parse parse-input (java.io.StringReader. input))
       meta
       :all-tags
       parse-variables))

(defn known-variables [input & [opts]]
  (->> (or opts {})
       (parse parse-input (java.io.StringReader. input))
       meta
       :all-tags
       parse-variables
       (map first)
       (into #{})))

(defmacro ^:no-doc env-map
  "Puts &env into a map."
  []
  `(zipmap (mapv keyword (quote ~(keys &env))) (vector ~@(keys &env))))

(defn ^:no-doc resolve-var-from-kw [ns env kw]
  (if (namespace kw)
    (when-let [v (ns-resolve ns (symbol (str (namespace kw) "/" (name kw))))] {kw @v})
    (or
     ;; check local env first
     (when-let [[_ v] (find env kw)] {kw v})
     (when-let [v (ns-resolve ns (symbol (name kw)))] {kw @v}))))

(defmacro <<
  "Resolves the variables from your template string from the local-env, or the
  namespace and puts them into your template for you.

  e.g. (let [a 1] (<< \"{{a}} + {{a}} = 2\")) ;;=> \"1 + 1 = 2\" "
  [s]
  `(->> (known-variables ~s)
        (mapv #(resolve-var-from-kw ~*ns* (env-map) %))
        (apply merge)
        (render ~s)))

(defn resolve-arg
  "Resolves an arg as passed to an add-tag! handler using the provided
  context-map.

  A custom tag handler will receive a seq of args as its first argument.
  With this function, you can selectively resolve one or more of those args
  so that if they contain literals, the literal value is returned, and if they
  contain templates of any sort, which can itself have variables, filters or
  tags in it, they will be returned resolved, applied and rendered.

  Example:
    (resolve-arg {{header-name|upper}} {:header-name \"My Page\"})
    => \"MY PAGE\""
  [arg context-map]
  (if (literal? arg)
    (parse-literal arg)
    (render arg context-map)))
