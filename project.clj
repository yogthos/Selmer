(defproject selmer "0.1.4-SNAPSHOT"
  :description "Django templates in clojure"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [criterium "0.4.1"]
                 [joda-time "2.2"]
                 [commons-codec "1.6"]
                 [cheshire "5.2.0"]]
  :test-selectors {:default (complement :benchmark)
                   :benchmark :benchmark
                   :all (constantly true)}
  :global-vars {*warn-on-reflection* true})
