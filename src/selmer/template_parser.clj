(ns selmer.template-parser
  " Where we preprocess the inheritance and mixin components of the templates.
  These are presumed to be static and we only aggregate them on the first
  template render. The compile-time tag parsing routines happen on a flat string
  composed from the result of `extends` inheritance and `include` mixins. "
  (:require [clojure.java.io :refer [reader]]
            [selmer.util :refer :all]
            [clojure.string :refer [split trim]]
            [selmer.validator :as validator])
  (:import java.io.StringReader))

(declare consume-block preprocess-template)

(defn get-tag-params [tag-id block-str]
  (-> block-str (split tag-id) second (split *tag-second-pattern*) first trim))

(defn parse-defaults [defaults]
  (when defaults
    (->> defaults
         (apply str)
         split-by-args
         (partition 2)
         (map vec)
         (into {}))))

(defn insert-includes
  "parse any included templates and splice them in replacing the include tags"
  [template]
  ;; We really need to split out the "gather all parent templates recursively"
  ;; and separate that from the buffer appending so we can gather the template
  ;; hierarchy for smarter cache invalidation - will eliminate almost all
  ;; existing reasons for cache-off!
  (->buf [buf]
    (with-open [rdr (reader (StringReader. template))]
    (loop [ch (read-char rdr)]
      (when ch
        (if (= *tag-open* ch)
          (let [tag-str (read-tag-content rdr)]
            (.append buf 
              (if (re-matches *include-pattern* tag-str)
                (let [params   (seq (.split ^String (get-tag-params #"include" tag-str) " "))
                      source   (.replaceAll ^String (first params) "\"" "")
                      defaults (parse-defaults (nnext params))]
                  (preprocess-template source {} defaults))
              tag-str)))
          (.append buf ch))
        (recur (read-char rdr)))))))

(defn get-parent [tag-str]
  (let [template (get-tag-params #"extends" tag-str)]
    (.substring ^String template 1 (dec (.length ^String template)))))

(defn write-tag? [buf super-tag? existing-block blocks-to-close omit-close-tag?]
  (and buf
       (or super-tag?
           (and 
             (not existing-block)
             (> blocks-to-close (if omit-close-tag? 1 0))))))

(defn consume-block [rdr & [^StringBuilder buf blocks omit-close-tag?]]
  (loop [blocks-to-close 1
         has-super? false]
    (if (and (pos? blocks-to-close) (peek-rdr rdr))
      (let [ch (read-char rdr)]
        (if (open-tag? ch rdr)
          (let [tag-str        (read-tag-content rdr)
                block?         (re-matches *block-pattern* tag-str)
                block-name     (if block? (get-tag-params #"block" tag-str))
                super-tag?     (re-matches *block-super-pattern* tag-str) 
                existing-block (if block-name (get-in blocks [block-name :content]))]
            ;;check if we wish to write the closing tag for the block. If we're
            ;;injecting block.super, then we want to omit it
            (when (write-tag? buf super-tag? existing-block blocks-to-close omit-close-tag?)
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
  (let [block-name     (get-tag-params #"block" block-tag)
        existing-block (get blocks block-name)]
    (cond
      ;;we have a child block with a {{block.super}} tag, we'll need to
      ;;grab the contents of the parent and inject them in the child
      (:super existing-block)
      (let [child-content  (:content existing-block)
            parent-content (StringBuilder.)
            has-super?     (consume-block rdr parent-content blocks true)]
        (assoc blocks block-name
               {:super has-super?
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
               {:super has-super?
                :content (.toString buf)})))))

(defn process-block [rdr buf block-tag blocks]
  (let [block-name (get-tag-params #"block" block-tag)]
    (if-let [child-content (get-in blocks [block-name :content])]
      (.append ^StringBuilder buf
        (rewrite-super
          child-content
          (->buf [buf] (consume-block rdr buf blocks true))))
      (do
        (.append ^StringBuilder buf block-tag)
        (consume-block rdr buf blocks)))))

(defn set-default-value [tag-str defaults]
  (let [tag-name (-> tag-str
                   (clojure.string/replace *filter-open-pattern* "")
                   (clojure.string/replace *filter-close-pattern* ""))]
    (if-let [value (get defaults tag-name)]
      (str *tag-open* *filter-open* tag-name "|default:\"" value "\"" *filter-close* *tag-close*)
      tag-str)))

(defn get-template-path [template]
  (.getPath ^java.net.URL (resource-path template)))

(defn read-template [template blocks defaults]
  (validator/validate (get-template-path template))
  (check-template-exists (get-template-path template))
  (let [buf (StringBuilder.)
        [parent blocks]
        (with-open [rdr (reader (resource-path template))]
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
                  (do (.append buf (set-default-value tag-str defaults))
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
                  (do (process-block rdr buf tag-str blocks)
                    (recur blocks (read-char rdr) parent))

                  ;;if we are in the root template we'll accumulate the content
                  ;;into a buffer, this will be the resulting template string
                  (nil? parent)
                  (do
                    (.append buf tag-str)
                    (recur blocks (read-char rdr) parent))))

              :else
              (do
                (if (nil? parent) (.append buf ch))
                (recur blocks (read-char rdr) parent)))))]
    (if parent (recur parent blocks defaults) (.toString buf))))

(defn preprocess-template [template & [blocks defaults]]
  (-> (read-template template blocks defaults) insert-includes))
