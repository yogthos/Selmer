(ns selmer.node
  (:gen-class))

(defprotocol INode
  (render-node [this context-map] "Renders the context"))

(deftype FunctionNode [handler]
  INode
  (render-node ^String [this context-map]
    (handler context-map)))

(deftype TextNode [text]
  INode
  (render-node ^String [this context-map]
    text))