(defproject selmer "0.5.6"
  :description "Django templates for Clojure"
  :url "https://github.com/yogthos/Selmer"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [criterium "0.4.2" :scope "test"]
                 [joda-time "2.3"]
                 [commons-codec "1.8"]
                 [cheshire "5.2.0"]]
  :repl-options {:port 10123}
  :plugins [[lein-marginalia "0.7.1"]]
  :test-selectors {:default (complement :benchmark)
                   :benchmark :benchmark
                   :all (constantly true)}
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}})
