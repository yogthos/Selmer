(ns selmer.template-parser
  (:require [clojure.java.io :refer [reader]]
            [selmer.util :refer :all]
            [clojure.set :refer [difference]]
            [clojure.string :refer [split trim]])
  (:import java.io.StringReader))

(declare read-tag-str replace-block find-template-dependencies insert-includes preprocess-template)

(defn get-tag-name [tag-id block-str]
  (-> block-str (split tag-id) second (split #"%") first trim))

(defn handle-extends [template templates path]    
  (find-template-dependencies path (assoc-in templates [template :extends] path)))

(defn handle-block [template templates block-name]
  (update-in templates [template :blocks] conj block-name))

(defn get-template-path [[^String path]]  
  (when path 
    (.substring path 1 (dec (.length path)))))

(defn handle-tag [template templates rdr]
  (let [{:keys [tag-name args] :as tag-info} (read-tag-info rdr)]    
    (condp = tag-name 
      :extends
      (handle-extends template templates (get-template-path args))
      
      :block
      (handle-block template templates (first args))
      
      templates)))

(defn find-template-dependencies [filename & [templates]]
  (with-open [rdr (reader (str (resource-path) filename))]
    (loop [templates (assoc (or templates {}) filename {})
           ch        (read-char rdr)]
      (if ch                  
        (recur
          (if (= *tag-open* ch)
            (handle-tag filename templates rdr)
            templates)
          (read-char rdr))
        templates))))

(defn consume-block [rdr & [^StringBuilder buf]]
  (loop [ch (read-char rdr)
         blocks-to-close 1]
    (when (and (pos? blocks-to-close) ch)
      (if (= *tag-open* ch)
        (let [content (read-tag-content rdr)]
          (when buf (.append buf content))
          (recur (read-char rdr) 
                 (long 
                   (cond 
                     (re-matches #"\{\%\s*block.*" content)
                     (inc blocks-to-close)
                     (re-matches #"\{\%\s*endblock.*" content)
                     (dec blocks-to-close)
                     :else blocks-to-close))))
        (do
          (when buf (.append buf ch))
          (recur (read-char rdr) blocks-to-close))))))

(defn read-block [block-template block-name blocks]
  (->buf [buf]
    (with-open [rdr (reader (str (resource-path) block-template))]
      (loop [ch (read-char rdr)]
        (when ch 
          (if (= *tag-open* ch) 
            (let [tag-str (read-tag-content rdr)]
              (cond
                (re-matches #"\{%\s*extends.*" tag-str)
                (.append buf "")
                
                (and (re-matches #"\{%\s*block.*" tag-str)
                     (= block-name (get-tag-name #"block" tag-str)))
                (do
                  (.append buf tag-str)
                  (.append buf (->buf [block-buf] (consume-block rdr block-buf))))
                
                  :else
                  (.append buf tag-str)))
            (.append buf ch))
          (recur (read-char rdr)))))))

(defn replace-block [rdr block-name blocks]  
  (consume-block rdr)
  (let [block-template (get blocks block-name)
        replacement-block (read-block block-template block-name blocks)]
    (with-open [rdr (StringReader. replacement-block)]
      (->buf [buf]
        (loop [ch (read-char rdr)]
          (when ch
            (.append buf
              (if (= *tag-open* ch)
                (read-tag-str rdr block-template blocks)
                ch))
            (recur (read-char rdr))))))))

(defn read-tag-str [rdr template blocks]
  (let [tag-str (read-tag-content rdr)]    
    (cond 
      (re-matches #"\{%\s*block.*" tag-str)
      (let [block-name     (get-tag-name #"block" tag-str)
            block-template (get blocks block-name)]
        (if (not= block-template template)
          (replace-block rdr block-name blocks)
          tag-str))
      
      (re-matches #"\{%\s*extends.*" tag-str) ""
      
      :else tag-str)))

(defn fill-blocks [filename blocks]
  (->buf [buf]
     (with-open [rdr (reader (str (resource-path) filename))]
       (loop [ch (read-char rdr)]
         (when ch                            
           (->> 
             (if (= *tag-open* ch) (read-tag-str rdr filename blocks) ch)
             (.append buf))           
           (recur (read-char rdr)))))))

(defn find-blocks [template templates block-map]  
  (let [blocks (difference (set (get-in templates [template :blocks]))
                           (set (keys block-map)))
        block-map (into block-map (map vector blocks (repeat template)))
        parent (get-in templates [template :extends])]
    (if parent (recur parent templates block-map) block-map)))

(defn find-root [template templates]
  (if-let [parent (get-in templates [template :extends])]
    (recur parent templates)
    template))

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
                (preprocess-template (.replaceAll ^String (get-tag-name #"include" tag-str) "\"" ""))
              tag-str)))
          (.append buf ch))
        (recur (read-char rdr)))))))

(defn preprocess-template [filename]
  (let [templates (find-template-dependencies filename)
        blocks (find-blocks filename templates {})]
    (-> filename
        (find-root templates)
        (fill-blocks blocks)
        insert-includes)))



#_(println "\n-----------\n" (preprocess-template "templates/inheritance/inherit-c.html"))

#_(println "\n-----------\n" (preprocess-template "templates/inheritance/inherit-b.html"))
