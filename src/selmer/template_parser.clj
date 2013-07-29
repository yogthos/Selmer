(ns selmer.template-parser
  " Where we preprocess the inheritance and mixin components of the templates.
  These are presumed to be static and we only aggregate them on the first
  template render. The compile-time tag parsing routines happen on a flat string
  composed from the result of `extends` inheritance and `include` mixins. "
  (:require [clojure.java.io :refer [reader]]
            [selmer.util :refer :all]
            [clojure.string :refer [split trim]])
  (:import java.io.StringReader))

(declare consume-block preprocess-template)

(defn get-tag-params [tag-id block-str]
  (-> block-str (split tag-id) second (split #"%") first trim))

(defn insert-includes
  "parse any included templates and splice them in replacing the include tags"
  [template] 
  (->buf [buf]
    (with-open [rdr (reader (StringReader. template))]
    (loop [ch (read-char rdr)]
      (when ch
        (if (= *tag-open* ch)
          (let [tag-str (read-tag-content rdr)]            
            (.append buf 
              (if (re-matches #"\{\%\s*include.*" tag-str)
                (preprocess-template (.replaceAll ^String (get-tag-params #"include" tag-str) "\"" ""))
              tag-str)))
          (.append buf ch))
        (recur (read-char rdr)))))))

(defn get-parent [tag-str]
  (let [template (get-tag-params #"extends" tag-str)]
    (.substring ^String template 1 (dec (.length ^String template)))))

(defn write-tag? [buf existing-block blocks-to-close omit-close-tag?]
  (and buf
       (not existing-block)
       (> blocks-to-close (if omit-close-tag? 1 0))))

(defn consume-block [rdr & [^StringBuilder buf blocks omit-close-tag?]]
  (loop [blocks-to-close 1
         has-super? false
         ch (read-char rdr)]    
    (if (and (pos? blocks-to-close) ch)
      (if (open-tag? ch rdr)
        (let [tag-str (read-tag-content rdr)
              block? (re-matches #"\{\%\s*block.*" tag-str)
              block-name (if block? (get-tag-params #"block" tag-str))
              super-tag? (re-matches #"\{\{\s*block.super\s*\}\}" tag-str) 
              existing-block (if block-name (get-in blocks [block-name :content]))]          
          (when (write-tag? buf existing-block blocks-to-close omit-close-tag?)
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

                (re-matches #"\{\%\s*endblock.*" tag-str)
                (dec blocks-to-close)
                
                :else blocks-to-close))
            (or has-super? super-tag?)
            (read-char rdr)))
        (do
          (when buf (.append buf ch))
          (recur blocks-to-close has-super? (read-char rdr))))
      (boolean has-super?))))

(defn rewrite-super [block parent-content]    
  (clojure.string/replace block #"\{\{\s*block.super\s*\}\}" parent-content))

(defn handle-super [rdr block-name existing-block blocks]
  (if (:super existing-block)
    (update-in blocks [block-name :content]
               rewrite-super
               (->buf [buf] (consume-block rdr buf blocks true)))
    (do (consume-block rdr) blocks)))

(defn read-block [rdr block-tag blocks]  
  (let [block-name (get-tag-params #"block" block-tag)
        existing-block (get blocks block-name)]    
    (if existing-block
      (handle-super rdr block-name existing-block blocks)
      (let [buf (doto (StringBuilder.) (.append block-tag))
            has-super? (consume-block rdr buf blocks)]        
        (assoc blocks block-name 
               {:super has-super?
                :content (.toString buf)})))))

(defn process-block [rdr buf block-tag blocks]    
  (let [block-name (get-tag-params #"block" block-tag)]    
    (if-let [existing-content (get-in blocks [block-name :content])]      
      (.append ^StringBuilder buf 
        (rewrite-super
          existing-content
          (->buf [buf] (consume-block rdr buf blocks true))))
      (do
        (.append ^StringBuilder buf block-tag)
        (consume-block rdr buf blocks)))))

(defn read-template [template blocks]
  (let [buf (StringBuilder.)
        [parent blocks]
        (with-open [rdr (reader (resource-path template))]
          (loop [blocks (or blocks {})
                 ch (read-char rdr)                 
                 parent nil]            
            (cond
              (nil? ch) [parent blocks]
              
              (open-tag? ch rdr)
              (let [tag-str (read-tag-content rdr)]                  
                (cond
                  ;;if the template extends another it's not the root
                  ;;this template is allowed to only contain blocks
                  (re-matches #"\{\%\s*extends.*" tag-str)
                  (recur blocks (read-char rdr) (get-parent tag-str))
                                    
                  ;;if we have a parent then we simply want to add the
                  ;;block to the block map if it hasn't been added already
                  (and parent (re-matches #"\{\%\s*block.*" tag-str))
                  (recur (read-block rdr tag-str blocks) (read-char rdr) parent)
                  
                  ;;if the template has blocks, but no parent it's the root
                  ;;we either replace the block with an existing one from a child
                  ;;template or read the block from this template
                  (re-matches #"\{\%\s*block.*" tag-str)
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
    (if parent (recur parent blocks) (.toString buf))))

(defn preprocess-template [template]
  (-> (read-template template {}) insert-includes))
