(defproject selmer "0.2.4"
  :description "Django templates for Clojure"
  :url "https://github.com/yogthos/Selmer"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [criterium "0.4.1" :scope "test"]
                 [joda-time "2.2"]
                 [commons-codec "1.6"]
                 [sonian/carica "1.0.3" :exclusions [[cheshire]]]
                 ;; http://en.wikipedia.org/wiki/Doublethink
                 [cheshire "5.2.0"]]
  :plugins [[lein-marginalia "0.7.1"]]
  :test-selectors {:default (complement :benchmark)
                   :benchmark :benchmark
                   :all (constantly true)}
  :global-vars {*warn-on-reflection* true})
