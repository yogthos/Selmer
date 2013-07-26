(ns selmer.template-parser
  (:require [clojure.java.io :refer [reader]]
            [selmer.util :refer :all]
            [clojure.set :refer [difference]]
            [clojure.string :refer [split trim]])
  (:import java.io.StringReader))

(declare find-template-dependencies insert-includes preprocess-template)

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
           ch       (read-char rdr)]
      (if ch                  
        (recur
          (if (= *tag-open* ch)
            (handle-tag filename templates rdr)
            templates)
          (read-char rdr))
        templates))))

(defn consume-block [rdr]  
  (loop [ch (read-char rdr)
         blocks-to-close 1]
    (when (and (pos? blocks-to-close) ch)
      (if (= *tag-open* ch)
        (let [content (read-tag-content rdr)]            
          (recur (read-char rdr) 
                 (cond 
                   (re-matches #"\{\%\s*block.*" content)
                   (inc blocks-to-close)
                   (re-matches #"\{\%\s*endblock.*" content)
                   (dec blocks-to-close)
                   :else blocks-to-close)))
        (recur (read-char rdr) blocks-to-close)))))

(defn replace-block [rdr block-name block-template]
  (println "injecting block" block-name "from" block-template)
  (consume-block rdr))

(defn read-tag-str [rdr template blocks]
  (let [tag-str (read-tag-content rdr)]
    (println tag-str)
    (cond 
      (re-matches #"\{%\s*block.*" tag-str)
      (let [block-name     (get-tag-name #"block" tag-str)
            block-template (get blocks block-name)]
        (if (not= block-template template)
          (replace-block rdr block-name block-template)
          tag-str))
      
      (re-matches #"\{%\s*extends.*" tag-str) ""
      
      :else tag-str)))


(defn fill-blocks [filename blocks]
  (let [buf (StringBuilder.)]
    (with-open [rdr (reader (str (resource-path) filename))]
      (loop [ch (read-char rdr)]
        (when ch                            
          (->> 
            (if (= *tag-open* ch) (read-tag-str rdr filename blocks) ch)
            (.append buf ))            
          (recur (read-char rdr))))
      (.toString buf))))

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
  (let [buf (StringBuilder.)]
    (with-open [rdr (reader (StringReader. template))]
    (loop [ch (read-char rdr)]
      (when ch
        (if (= *tag-open* ch)
          (let [tag-str (read-tag-content rdr)]            
            (.append buf 
              (if (re-matches #"\{\%\s*include.*" tag-str)
                (preprocess-template (.replaceAll (get-tag-name #"include" tag-str) "\"" ""))
              tag-str)))
          (.append buf ch))
        (recur (read-char rdr))))
    (.toString buf))))

(defn preprocess-template [filename]
  (let [templates (find-template-dependencies filename)
        blocks (find-blocks filename templates {})]
    (-> filename
        (find-root templates)
        (fill-blocks blocks)
        insert-includes)))



#_(preprocess-template "templates/inheritance/inherit-c.html")

