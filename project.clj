(defproject selmer "0.6.6"
  :description "Django templates for Clojure"
  :url "https://github.com/yogthos/Selmer"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [criterium "0.4.3" :scope "test"]
                 [joda-time "2.3"]
                 [commons-codec "1.9"]
                 [cheshire "5.3.1"]]
  :repl-options {:port 10123}
  :plugins [[lein-marginalia "0.7.1"]]
  :test-selectors {:default (complement :benchmark)
                   :benchmark :benchmark
                   :all (constantly true)}
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}})
