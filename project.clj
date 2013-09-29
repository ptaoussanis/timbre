(defproject cc.artifice/timbre "2.6.3"
  :description "Clojure logging & profiling library"
  :url "https://github.com/ptaoussanis/timbre"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure     "1.4.0"]
                 [org.clojure/tools.macro "0.1.5"]
                 [clj-stacktrace          "0.2.6"]
                 [expectations            "1.4.55"]]
  :profiles {:1.4  {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :1.5  {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :1.6  {:dependencies [[org.clojure/clojure "1.6.0-master-SNAPSHOT"]]}
             :dev  {:dependencies []}
             :test {:dependencies []}}
  :aliases {"test-all"  ["with-profile" "test,1.4:test,1.5:test,1.6" "expectations"]
            "test-auto" ["with-profile" "test" "autoexpect"]
            "start-dev" ["with-profile" "dev,test,bench" "repl" ":headless"]
            "codox"     ["with-profile" "test" "doc"]}
  :plugins [[lein-expectations "0.0.8"]
            [lein-autoexpect   "1.0"]
            [lein-ancient      "0.4.4"]
            [codox             "0.6.6"]]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true}
  :repositories
  {"sonatype"
   {:url "http://oss.sonatype.org/content/repositories/releases"
    :snapshots false
    :releases {:checksum :fail}}
   "sonatype-snapshots"
   {:url "http://oss.sonatype.org/content/repositories/snapshots"
    :snapshots true
    :releases {:checksum :fail :update :always}}})
