(ns selmer.template-parser
  " Where we preprocess the inheritance and mixin components of the templates.
  These are presumed to be static and we only aggregate them on the first
  template render. The compile-time tag parsing routines happen on a flat string
  composed from the result of `extends` inheritance and `include` mixins. "
  (:require
   [clojure.java.io :refer [reader] :as io]
   [selmer.filter-parser :refer [split-value]]
   [selmer.util :refer :all]
   [clojure.string :as s :refer [split trim]]
   [selmer.validator :as validator])
  (:import java.io.StringReader))

(declare consume-block preprocess-template wrap-in-expression-tag)

(defn get-tag-params [tag-id block-str]
  (let [tag-id (re-pattern (str "^.+?" tag-id "\\s*"))]
    (-> block-str (s/replace tag-id "") (split *tag-second-pattern*) first trim)))

(defn string->reader [string]
  (reader (StringReader. string)))

(defn get-parent [tag-str]
  (let [template (get-tag-params "extends" tag-str)]
    (.substring ^String template 1 (dec (.length ^String template)))))

(defn write-tag? [buf super-tag? existing-block blocks-to-close omit-close-tag?]
  (and buf
       (or super-tag?
           (and
            (not existing-block)
            (> blocks-to-close (if omit-close-tag? 1 0))))))

(defn- parse-include-tag [tag-str]
  (let [params
        (tokenize-tag-args
         (get-tag-params "include"
                         (.replace ^String tag-str "\\" "/")))

        [source & include-args] params

        include-args
        (if (= "with" (first include-args))
          (rest include-args)
          include-args)]
    {:source   (.replaceAll ^String source "\"" "")
     :bindings (partition 2 include-args)}))

(defn- wrap-in-with-tag [template bindings]
  (let [include-binding-temp-name
        (fn [id]
          ;; Namespace the temp binding under the `selmer` keyword namespace
          ;; (mirroring `compile-args-namespaced`) so it can't collide with—or
          ;; throw on—a user-supplied `:selmer` context key. Dots are flattened
          ;; so the name resolves to a single namespaced keyword rather than a
          ;; nested accessor path.
          (str "selmer/include-" (clojure.string/replace id "." "_")))

        self-referential-include-binding?
        (fn [id value]
          (= id (-> value split-value first)))

        outer-with-binding-arg
        (fn [[id value]]
          (str (include-binding-temp-name id) 
               "=" value))

        ;; Precedence note: the inner `with` decides whether the binding or the
        ;; caller's context wins for the bound name.
        ;;   - self-referential (e.g. `x=x|upper`): the computed value wins,
        ;;     otherwise the filter would be a no-op whenever the caller has `x`.
        ;;   - everything else (e.g. `x=heading`): the binding is treated as a
        ;;     default, preserving Selmer's historic include-with semantics where
        ;;     a caller-provided value for the bound name wins.
        inner-with-binding-arg
        (fn [[id value]]
          (if (self-referential-include-binding? id value)
            (str id "=" 
                 (include-binding-temp-name id) 
                 "|default:@" 
                 id)
            (str id "=" id 
                 "|default:@" 
                 (include-binding-temp-name id))))

        outer-args
        (->> bindings
             (map outer-with-binding-arg)
             (clojure.string/join \space))
        
        inner-args
        (->> bindings
             (map inner-with-binding-arg)
             (clojure.string/join \space))]
    (str (wrap-in-expression-tag 
          (str "with " outer-args))
         (wrap-in-expression-tag 
          (str "with " inner-args))
         template
         (wrap-in-expression-tag "endwith")
         (wrap-in-expression-tag "endwith"))))

(defn- process-includes [tag-str blocks]
  (let [{:keys [source bindings]} 
        (parse-include-tag tag-str)

        template                  
        (preprocess-template source blocks)]
    (if (seq bindings)
      (wrap-in-with-tag template bindings)
      template)))

(defn consume-block [rdr & [^StringBuilder buf blocks omit-close-tag?]]
  (loop [blocks-to-close 1
         has-super?      false]
    (if (and (pos? blocks-to-close) (peek-rdr rdr))
      (let [ch (read-char rdr)]
        (if (open-tag? ch rdr)
          (let [tag-str        (read-tag-content rdr)
                includes?      (re-matches *include-pattern* tag-str)
                block?         (re-matches *block-pattern* tag-str)
                block-name     (when block? (get-tag-params "block" tag-str))
                super-tag?     (re-matches *block-super-pattern* tag-str)
                existing-block (when block-name (get-in blocks [block-name :content]))]

            (when buf
              (cond
                includes?
                (.append buf (process-includes tag-str blocks))

                ;;check if we wish to write the closing tag for the block. If we're
                ;;injecting block.super, then we want to omit it
                (write-tag? buf super-tag? existing-block blocks-to-close omit-close-tag?)
                (.append buf tag-str)))

            (recur
             (long
              (cond
                existing-block
                (do
                  (consume-block rdr)
                  (consume-block
                   (StringReader. existing-block) buf (dissoc blocks block-name))
                  blocks-to-close)

                block?
                (inc blocks-to-close)

                (re-matches *endblock-pattern* tag-str)
                (dec blocks-to-close)

                :else blocks-to-close))
             (or has-super? super-tag?)))
          (do
            (when buf (.append buf ch))
            (recur blocks-to-close has-super?))))
      (boolean has-super?))))

(defn rewrite-super [block parent-content]
  (clojure.string/replace block *block-super-pattern* parent-content))

(defn read-block [rdr block-tag blocks]
  (let [block-name     (get-tag-params "block" block-tag)
        existing-block (get blocks block-name)]
    (cond
      ;;we have a child block with a {{block.super}} tag, we'll need to
      ;;grab the contents of the parent and inject them in the child
      (:super existing-block)
      (let [child-content  (:content existing-block)
            parent-content (StringBuilder.)
            has-super?     (consume-block rdr parent-content blocks true)]
        (assoc blocks block-name
               {:super   has-super?
                :content (rewrite-super child-content (.toString parent-content))}))

      ;;we've got a child block without a super tag, the parent will be replaced
      existing-block
      (do (consume-block rdr) blocks)

      ;;this is the first occurance of the block and we simply add it to the
      ;;map of blocks we've already seen
      :else
      (let [buf        (doto (StringBuilder.) (.append block-tag))
            has-super? (consume-block rdr buf blocks)]
        (assoc blocks block-name
               {:super   has-super?
                :content (.toString buf)})))))

(defn process-block [rdr buf block-tag blocks]
  (let [block-name (get-tag-params "block" block-tag)]
    (if-let [child-content (get-in blocks [block-name :content])]
      (.append ^StringBuilder buf
               (rewrite-super
                child-content
                (->buf [buf] (consume-block rdr buf blocks true))))
      (do
        (.append ^StringBuilder buf block-tag)
        (consume-block rdr buf blocks)))))

(defn wrap-in-expression-tag [string]
  (str *tag-open* *tag-second* string *tag-second* *tag-close*))

(defn get-template-path [template]
  (resource-path template))

(defn read-template [template blocks]
  (let [path (if (instance? (Class/forName "[C") template)
               template
               (let [path (resource-path template)]
                 (when-not path
                   (validator/validation-error
                    (str "resource-path for " template " returned nil, typically means the file doesn't exist in your classpath.")
                    nil nil nil))
                 (validator/validate path)
                 (check-template-exists (get-template-path template))
                 path))
        buf (StringBuilder.)
        [parent blocks]
        (with-open [rdr (reader path)]
          (loop [blocks (or blocks {})
                 ch     (read-char rdr)
                 parent nil]
            (cond
              (nil? ch) [parent blocks]

              (open-tag? ch rdr)
              (let [tag-str (read-tag-content rdr)]
                (cond
                  (re-matches *include-pattern* tag-str)
                  (do (.append buf (process-includes tag-str blocks))
                      (recur blocks (read-char rdr) parent))

                  ;;if the template extends another it's not the root
                  ;;this template is allowed to only contain blocks
                  (re-matches *extends-pattern* tag-str)
                  (recur blocks (read-char rdr) (get-parent tag-str))

                  ;;if we have a parent then we simply want to add the
                  ;;block to the block map if it hasn't been added already
                  (and parent (re-matches *block-pattern* tag-str))
                  (recur (read-block rdr tag-str blocks) (read-char rdr) parent)

                  ;;if the template has blocks, but no parent it's the root
                  ;;we either replace the block with an existing one from a child
                  ;;template or read the block from this template
                  (re-matches *block-pattern* tag-str)
                  (do
                    (process-block rdr buf tag-str blocks)
                    (recur blocks (read-char rdr) parent))

                  ;;if we are in the root template we'll accumulate the content
                  ;;into a buffer, this will be the resulting template string
                  (nil? parent)
                  (do
                    (.append buf tag-str)
                    (recur blocks (read-char rdr) parent))))

              :else
              (do
                (when (nil? parent) (.append buf ch))
                (recur blocks (read-char rdr) parent)))))]
    (if parent
      (recur parent blocks)
      (.toString buf))))

(defn preprocess-template [template & [blocks]]
  (read-template template blocks))
