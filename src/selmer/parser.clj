(ns selmer.parser
  " Parsing and handling of compile-time vs.
  run-time. Avoiding unnecessary work by pre-processing
  the template structure and content and reacting to
  the runtime context map with a prepared data structure
  instead of a raw template. Anything other than a raw tag
  value injection is a runtime dispatch fn. Compile-time here
  means the first time we see a template *at runtime*, not the
  implementation's compile-time. "
  (:require [carica.core :refer [config]]
            [selmer.template-parser :refer [preprocess-template]]
            [selmer.filters :refer [filters]]
            [selmer.filter-parser :refer [compile-filter-body]]
            [selmer.tags :refer :all]
            [selmer.util :refer :all]
            selmer.node)
  (:import [selmer.node INode TextNode FunctionNode]))

;; Ahead decl because some fns call into each other.

(declare parse parse-file expr-tag tag-content)

;; Memoization atom for templates. If you pass a filepath instead
;; of a string, we'll use the last-modified timestamp to cache the
;; template. Works fine for active local development and production.

(defonce templates (atom {}))

(defonce cache? (atom (not (config :dev))))
(defonce dev (config :dev))

(defn cache-on! []
  (reset! cache? true))

(defn cache-off! []
  (reset! cache? false))

;; expr-tags are {% if ... %}, {% ifequal ... %},
;; {% for ... %}, and {% block blockname %}

(defonce expr-tags
  (atom {:if if-handler
         :ifequal ifequal-handler
         :for for-handler
         :block block-handler
         :cycle cycle-handler
         :now now-handler
         :comment comment-handler
         :firstof first-of-handler
         :verbatim verbatim-handler
         :with with-handler
         :script script-handler
         :style style-handler}))

;; add-tag! is a hella nifty macro. Example use:
;; (add-tag! :joined (fn [args context-map] (clojure.string/join "," args)))

(defmacro add-tag! [k handler & tags]
  " tag name, fn handler, and maybe tags "
  `(swap! selmer.parser/expr-tags assoc ~k (tag-handler ~handler ~k ~@tags)))

;; render-template renders at runtime, accepts
;; post-parsing vectors of INode elements.

(defn render-template [template context-map]
  " vector of ^selmer.node.INodes and a context map."
  (let [buf (StringBuilder.)
        context-map (assoc context-map :cache? @cache?)]
    (doseq [^selmer.node.INode element template]
        (if-let [value (.render-node element context-map)]
          (.append buf value)))
    (.toString buf)))

(defn render [s context-map & [opts]]
  " render takes the string, the context-map and possibly also opts. "
  (render-template (parse (java.io.StringReader. s) opts) context-map))

;; Primary fn you interact with as a user, you pass a path that
;; exists somewhere in your class-path, typically something like
;; resources/templates/template_name.html. You also pass a context
;; map and potentially opts. Smart (last-modified timestamp)
;; auto-memoization of compiler output.

(defn render-file [^String filename context-map & [opts]]
  " Parses files if there isn't a memoized post-parse vector ready to go,
  renders post-parse vector with passed context-map regardless. Double-checks
  last-modified on files. Uses classpath for filename path "
  (let [file-path (.getPath ^java.net.URL (resource-path filename))
        {:keys [template last-modified]} (get @templates filename)
        last-modified-file (.lastModified (java.io.File. ^String file-path))]
      
    (when-not (.exists (java.io.File. file-path))
      (throw (Exception. (str "temaplate: \"" file-path "\" not found"))))
        
    (if (and @cache? last-modified (= last-modified last-modified-file))
      (render-template template context-map)
      (let [template (parse-file filename opts)]
        (swap! templates assoc filename {:template template
                                         :last-modified last-modified-file})
        (render-template template context-map)))))

;; For a given tag, get the fn handler for the tag type,
;; pass it the arguments, tag-content, render-template fn,
;; and reader.

(defn expr-tag [{:keys [tag-name args] :as tag} rdr]
  (if-let [handler (tag-name @expr-tags)]
    (handler args tag-content render-template rdr)
    (throw (Exception. (str "unrecognized tag: " tag-name)))))

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
  (if (= :filter tag-type)
    (filter-tag tag)
    (expr-tag tag rdr)))

;; Pretty fucking gnarly. Parses and detects tags which turn into
;; FunctionNode call-sites or TextNode content. open-tag? fn returns
;; true or false based on character lookahead to see if it's {{ or {%

(defn tag-content [rdr start-tag & end-tags]
  (let [buf (StringBuilder.)]
    (loop [ch       (read-char rdr)
           tags     {}
           content  []
           cur-tag  start-tag
           end-tags end-tags]
      (cond
        (nil? ch)
        tags
        (open-tag? ch rdr)
        (let [{:keys [tag-name args] :as tag} (read-tag-info rdr)]
          (if-let [open-tag (and tag-name (some #{tag-name} end-tags))]
              (let [tags     (assoc tags cur-tag
                                    {:args args
                                     :content (conj content (TextNode. (.toString buf)))})
                    end-tags (next (drop-while #(not= tag-name %) end-tags))]                
                (.setLength buf 0)
                (recur (if (empty? end-tags) nil (read-char rdr)) tags [] open-tag end-tags))
              (let [content (-> content
                              (conj (TextNode. (.toString buf)))
                              (conj (FunctionNode. (parse-tag tag rdr))))]
                (.setLength buf 0)
                (recur (read-char rdr) tags content cur-tag end-tags))))
        :else
        (do
          (.append buf ch)
          (recur (read-char rdr) tags content cur-tag end-tags))))))

;; Compile-time parsing of tags. Accumulates a transient vector
;; before returning the persistent vector of INodes (TextNode, FunctionNode)

(defn parse* [input]
  (with-open [rdr (clojure.java.io/reader input)]
      (let [template (transient [])
            buf      (StringBuilder.)]
        (loop [ch (read-char rdr)]
          (when ch
            (if (open-tag? ch rdr)
              (do                
                ;; We hit a tag so we append the buffer content to the template
                ;; and empty the buffer, then we proceed to parse the tag
                (conj! template (TextNode. (.toString buf)))
                (.setLength buf 0)
                (conj! template (FunctionNode. (parse-tag (read-tag-info rdr) rdr)))
                (recur (read-char rdr)))
              (do
                ;; Default case, here we append the character and
                ;; read the next char
                (.append buf ch)
                (recur (read-char rdr))))))
        ;; Add the leftover content of the buffer and return the template
        (conj! template (TextNode. (.toString buf)))
        (persistent! template))))

;; Primary compile-time parse routine. Work we don't want happening after
;; first template render. Vector output from parse* gets memoized by render-file.

(defn parse [file & [{:keys [tag-open tag-close filter-open
             filter-close tag-second custom-tags custom-filters]}]]
  (binding [*tag-open*     (or tag-open *tag-open*)
            *tag-close*    (or tag-close *tag-close*)
            *filter-open*  (or filter-open *filter-open*)
            *filter-close* (or filter-close *filter-close*)
            *tag-second*   (or tag-second *tag-second*)]
    (swap! expr-tags merge custom-tags)
    (swap! filters merge custom-filters)
    (parse* file)))

;; File-aware parse wrapper.

(defn parse-file [file & [params]]
  (-> file preprocess-template (java.io.StringReader.) (parse params)))
