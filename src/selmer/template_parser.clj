(ns selmer.template-parser
  (:require [clojure.java.io :refer [reader]]
            [selmer.util :refer :all]
            [clojure.set :refer [difference]]))

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

(defn find-block [template parent-template block-name]
  )

(defn fill-blocks [template templates block-map]
  (println template block-map)
  (let [blocks (difference (set (get-in templates [template :blocks]))
                           (set (keys block-map)))
        block-map (into block-map (map vector blocks (repeat template)))
        parent (get-in templates [template :extends])]
    (if parent (recur parent templates block-map) block-map)))

(defn find-root [template templates]
  (if-let [parent (get-in templates [template :extends])]
    (recur parent templates)
    template))

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

#_(let [template "templates/inheritance/inherit-c.html"
        templates (preprocess-template template)]
    (find-root template templates)
    (fill-blocks template templates {}))