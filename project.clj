(defproject selmer "1.12.60"
  :description "Django style templates for Clojure"
  :url "https://github.com/yogthos/Selmer"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [;; minimal clojure version
                 [org.clojure/clojure "1.9.0"]
                 ;; optional dependencies
                 [json-html "0.4.7" :scope "provided"]
                 [cheshire "5.11.0" :scope "provided"]]

  :aot [selmer.node]
  :javac-options ["-target" "1.6"]

  :test-selectors {:default (complement :benchmark)
                   :benchmark :benchmark
                   :all (constantly true)}
  :profiles {:dev {:global-vars {*warn-on-reflection* true}
                   :repl-options {:port 10123}
                   :source-paths ["src" "dev"]
                   :plugins [[lein-marginalia "0.9.0"]]
                   :dependencies [[environ "1.2.0"]
                                  [org.clojure/tools.namespace "1.3.0"]
                                  [criterium "0.4.6"]]}
             :test {:dependencies [[criterium "0.4.6"]]}})
