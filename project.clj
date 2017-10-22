(defproject async-chain "0.1.0-SNAPSHOT"
  :description "A Clojure(Script) library built on top of core.async providing macros to chain sync/async expressions."
  :url "https://github.com/pepzer/async-chain"
  :license {:name "Mozilla Public License Version 2.0"
            :url "http://mozilla.org/MPL/2.0/"}

  :min-lein-version "2.7.1"

  :dependencies [[org.clojure/clojure "1.9.0-beta1"]
                 [org.clojure/clojurescript "1.9.946"]
                 [org.clojure/core.async "0.3.443"]
                 #_[andare "0.7.0"]]

  :plugins [[lein-figwheel "0.5.13"]
            [lein-cljsbuild "1.1.7" :exclusions [[org.clojure/clojure]]]]

  :clean-targets ^{:protect false} ["target"]

  :source-paths ["src/cljc" "test/cljc"]

  :cljsbuild {
              :builds [{:id "dev"
                        :source-paths ["src/cljc" "test/cljc"]
                        :figwheel true
                        :compiler {:main async-chain.dev
                                   :output-to "target/out/async-chain.js"
                                   :output-dir "target/out"
                                   ;;:target :nodejs
                                   :optimizations :none
                                   :source-map true }}
                       {:id "prod"
                        :source-paths ["src/cljc"]
                        :compiler {:output-to "target/out-rel/async-chain.js"
                                   :output-dir "target/out-rel"
                                   ;;:target :nodejs
                                   :optimizations :advanced
                                   :source-map false }}]}

  :profiles {:dev {:source-paths ["dev"]}}
  :figwheel {})

