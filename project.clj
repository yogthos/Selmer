(defproject selmer "0.1.2-SNAPSHOT"
  :description "Django templates in clojure"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [criterium "0.4.1"]
                 [joda-time "2.2"]]
  :test-selectors {:default (complement :benchmark)
                   :benchmark :benchmark
                   :all (constantly true)}
  ;; :aot :all
  :warn-on-reflection true)
