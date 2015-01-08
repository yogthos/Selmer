(defproject selmer "0.7.9"
  :description "Django templates for Clojure"
  :url "https://github.com/yogthos/Selmer"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [criterium "0.4.3" :scope "test"]
                 [joda-time "2.6"]
                 [commons-codec "1.10"]
                 [cheshire "5.4.0"]]
  :aot [selmer.node]
  :javac-options ["-target" "1.6"]
  :repl-options {:port 10123}
  :plugins [[lein-marginalia "0.7.1"]
            [lein-midje "3.0.0"]
            [midje-readme "1.0.4"]]

  :midje-readme {:require "[selmer.parser :refer :all] [selmer.filters :refer :all] [environ.core :refer [env]] [selmer.middleware :refer [wrap-error-page]]"}

  :test-selectors {:default (complement :benchmark)
                   :benchmark :benchmark
                   :all (constantly true)}
  :profiles {:dev {:global-vars {*warn-on-reflection* true}
                   :dependencies [[midje "1.6.3"]
                                  [environ "1.0.0"]
                                  [midje-readme "1.0.4"]]}})
