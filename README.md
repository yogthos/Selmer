Selmer
======

Django template implementation in Clojure

## The plan

* each tag will be parsed into a handler function that accepts a params map
* the templates will be parsed into a vector containing text and handler functions
* function with a body will be responsible for rendering their own body
* single pass parse step to read the templates
* use memoized template to render pages
