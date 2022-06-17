(ns build
  "Basic build script for Selmer.

  Automatically compiles selmer.node when running tests:

  clojure -T:build test

  This will exclude the :benchmark tests by default.
  You can specify :select :benchmark to run just the
  benchmark tests, or :select :all to run all the tests."
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as bb]))

(defn prep
  "Compile selmer.node to target/classes."
  [_]
  (b/compile-clj {:basis (bb/default-basis)
                  :class-dir (bb/default-class-dir)
                  :ns-compile ['selmer.node]}))

(defn test
  "Run the tests, ensuring that selmer.node is compiled first.

  The :select option can be provided to determine which tests
  to run, and can be :default, :benchmark, or :all. If omitted,
  the :default selector is used, which means 'not :benchmark'.

  If :aliases is specified, those will be used in addition to
  the :dev alias (which is always used)."
  [opts]
  ;; find ALL test namespaces under test, not just *-test ones:
  ;; (because benchmarks are not in *-test namespace!)
  (let [selector (case (:select opts :default)
                   :default   {:main-opts ["-e" "benchmark" "-r" ".*"]}
                   :benchmark {:main-opts ["-i" "benchmark" "-r" ".*"]}
                   :all       {:main-opts ["-r" ".*"]})]
    (prep {})
    (bb/run-tests (-> opts
                      (dissoc :select) ; remove our custom option
                      (merge selector) ; add our main opts
                      ;; ensure :dev alias is used!
                      (update :aliases (fnil conj []) :dev)))))
