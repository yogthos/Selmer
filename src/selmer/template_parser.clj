(ns selmer.template-parser
  " Where we preprocess the inheritance and mixin components of the templates.
  These are presumed to be static and we only aggregate them on the first
  template render. The compile-time tag parsing routines happen on a flat string
  composed from the result of `extends` inheritance and `include` mixins. "
  (:require
    [clojure.java.io :refer [reader] :as io]
    [selmer.util :refer :all]
    [clojure.string :as s :refer [split trim]]
    [selmer.validator :as validator])
  (:import java.io.StringReader))

(declare consume-block preprocess-template)

(defn get-tag-params [tag-id block-str]
  (let [tag-id (re-pattern (str "^.+?" tag-id "\\s*"))]
    (-> block-str (s/replace tag-id "") (split *tag-second-pattern*) first trim)))

(defn parse-defaults [defaults]
  (when defaults
    (->> defaults
         (interpose " ")
         (apply str)
         split-by-args
         (partition 2)
         (map vec)
         (into {}))))

(defn split-include-tag [^String tag-str]
  (seq (.split ^String (get-tag-params "include" (.replace tag-str "\\" "/")) " ")))

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

(defn- process-includes [tag-str buf blocks]
  (let [params   (split-include-tag tag-str)
        source   (.replaceAll ^String (first params) "\"" "")
        defaults (parse-defaults (nnext params))]
    (preprocess-template source blocks defaults)))

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

            (cond
              includes?
              (.append buf (process-includes tag-str buf blocks))

              ;;check if we wish to write the closing tag for the block. If we're
              ;;injecting block.super, then we want to omit it
              (write-tag? buf super-tag? existing-block blocks-to-close omit-close-tag?)
              (.append buf tag-str))

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

(defn wrap-in-variable-tag [string]
  (str *tag-open* *filter-open* string *filter-close* *tag-close*))

(defn trim-regex [string & regexes]
  (reduce #(clojure.string/replace %1 %2 "") string regexes))

(defn trim-variable-tag [string]
  (trim-regex string *filter-open-pattern* *filter-close-pattern*))

(defn trim-expression-tag [string]
  (trim-regex string *tag-open-pattern* *tag-close-pattern*))

(defn to-expression-string [tag-name args]
  (let [tag-name' (name tag-name)
        args'     (clojure.string/join \space args)
        joined    (if (seq args) (str tag-name' \space args') tag-name')]
    (wrap-in-expression-tag joined)))

(defn add-default [identifier default]
  (str identifier "|default:" \" default \"))

(defn try-add-default [identifier defaults]
  (if-let [default (get defaults identifier)]
    (add-default identifier default)
    identifier))

(defn add-defaults-to-variable-tag [tag-str defaults]
  (let [tag-name (trim-variable-tag tag-str)]
    (wrap-in-variable-tag (try-add-default tag-name defaults))))

(defn add-defaults-to-expression-tag [tag-str defaults]
  (let [tag-str' (->> (trim-expression-tag tag-str)
                      ;; NOTE: we add a character here since read-tag-info
                      ;; consumes the first character before parsing.
                      (str *tag-second*))
        {:keys [tag-name args]} (read-tag-info (string->reader tag-str'))]
    (to-expression-string tag-name (map #(try-add-default % defaults) args))))

(defn get-template-path [template]
  (resource-path template))

(defn read-template [template blocks defaults]
  (let [path (resource-path template)]
    (when-not path
      (validator/validation-error
        (str "resource-path for " template " returned nil, typically means the file doesn't exist in your classpath.")
        nil nil nil))
    (validator/validate path)
    (check-template-exists (get-template-path template))
    (let [buf (StringBuilder.)
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
                    (and defaults
                         (re-matches *filter-pattern* tag-str))
                    (do (.append buf (add-defaults-to-variable-tag tag-str defaults))
                        (recur blocks (read-char rdr) parent))

                    (and defaults
                         (re-matches *tag-pattern* tag-str))
                    (do (.append buf (add-defaults-to-expression-tag tag-str defaults))
                        (recur blocks (read-char rdr) parent))

                    ;;if the template includes another, pre-process it and
                    ;;add the contents to the front of the buffer.
                    (re-matches *include-pattern* tag-str)
                    (do (.append buf (process-includes tag-str buf blocks))
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
        (recur parent blocks defaults)
        (.toString buf)))))

(defn preprocess-template [template & [blocks defaults]]
  (read-template template blocks defaults))
