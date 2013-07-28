(ns selmer.template-parser
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

(defn consume-block [rdr & [^StringBuilder buf blocks]]
  (loop [blocks-to-close 1
         ch (read-char rdr)]    
    (when (and (pos? blocks-to-close) ch)
      (if (= *tag-open* ch)
        (let [tag-str (read-tag-content rdr)
              block? (re-matches #"\{\%\s*block.*" tag-str)
              block-name (if block? (get-tag-params #"block" tag-str))
              existing-block (if block-name (get blocks block-name))]          
          (when (and buf (not existing-block)) (.append buf tag-str))
          (recur
            (long 
              (cond
                existing-block
                (do 
                  (consume-block rdr)
                  (consume-block (StringReader. existing-block) buf (dissoc blocks block-name))
                  blocks-to-close)
                
                block?
                (inc blocks-to-close)
   
                (re-matches #"\{\%\s*endblock.*" tag-str)
                (dec blocks-to-close)
                
                :else blocks-to-close))
            (read-char rdr)))
        (do
          (when buf (.append buf ch))
          (recur blocks-to-close (read-char rdr)))))))

(defn read-block [rdr block-tag blocks]
  (let [block-name (get-tag-params #"block" block-tag)]
    (if (get blocks block-name)
      (do (consume-block rdr) blocks)
      (assoc blocks block-name 
             (->buf [buf]
                    (.append buf block-tag)
                    (consume-block rdr buf blocks))))))

(defn process-block [rdr buf block-tag blocks]  
  (let [block-name (get-tag-params #"block" block-tag)]
    (if-let [block (get blocks block-name)]
      (do
        (consume-block rdr)
        (.append ^StringBuilder buf block))
      (do
        (.append ^StringBuilder buf block-tag)
        (consume-block rdr buf)))))

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
