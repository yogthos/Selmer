(ns selmer.tags)

(def valid-tags (atom {}))
(def tags (atom {}))

;;; A tag can modify the context map for its body
;;; It has full control of its body which means that it has to
;;; take care of its compilation.
