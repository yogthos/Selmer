(ns selmer.template-parser
  (:require [clojure.java.io :refer [reader]]
            [selmer.util :refer :all]))

(declare preprocess-template)

(defn handle-include [template templates path]
  templates)

(defn handle-extends [template templates path]    
  (preprocess-template path (assoc-in templates [template :extends] path)))

(defn handle-block [template templates block-name]
  (update-in templates [template :blocks] conj block-name))

(defn get-template-path [[^String path]]  
  (when path 
    (.substring path 1 (dec (.length path)))))

(defn handle-tag [template templates rdr]
  (let [{:keys [tag-name args] :as tag-info} (read-tag-info rdr)]    
    (condp = tag-name 
      
      :include
      (handle-include template templates (get-template-path args))
      
      :extends
      (handle-extends template templates (get-template-path args))
      
      :block
      (handle-block template templates (first args))
      
      templates)))

(defn preprocess-template [filename & [templates]]
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

#_(preprocess-template "templates/inheritance/inherit-c.html")