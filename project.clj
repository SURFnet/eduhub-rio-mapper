(defproject nl.surf/eduhub-rio-mapper "0.1.0-SNAPSHOT"
  :description "Mapping between Surf's OOAPI and DUO's RIO."
  :url "https://github.com/jomco/eduhub-rio-mapper"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/core.async "1.6.681"]
                 [org.clojure/core.memoize "1.1.266"]
                 [com.velisco/strgen "0.2.5" :exclusions [org.clojure/clojurescript]]

                 ;; data
                 [org.clojure/data.json "2.5.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.apache.santuario/xmlsec "4.0.2"]

                 ;; interaction
                 [clj-http "3.13.0"]
                 [clj-time "0.15.2"]
                 [nl.jomco/clj-http-status-codes "0.1"]

                 ;; state
                 [com.taoensso/carmine "3.4.1" :exclusions [org.clojure/tools.reader]]

                 ;; CLI
                 [nl.jomco/envopts "0.0.5"]

                 ;; API
                 [compojure "1.7.1"]
                 [ring/ring-jetty-adapter "1.12.2"]
                 [ring/ring-core "1.12.2"]
                 [ring/ring-defaults "0.5.0"]
                 [ring/ring-json "0.5.1"]
                 [nl.jomco/ring-trace-context "0.0.8"]

                 ;; logging
                 [org.clojure/tools.logging "1.3.0"]
                 [ch.qos.logback.contrib/logback-jackson "0.1.5"]
                 [ch.qos.logback.contrib/logback-json-classic "0.1.5"]
                 [ch.qos.logback/logback-classic "1.5.8"]
                 [com.fasterxml.jackson.core/jackson-core "2.18.0"]
                 [com.fasterxml.jackson.core/jackson-databind "2.18.0"]

                 ;; force newest version
                 [cheshire "5.13.0"]
                 [commons-io "2.17.0"]
                 [commons-codec "1.17.1"]]

  :java-source-paths ["src"]

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[clj-commons/clj-yaml "RELEASE"]
                                  [clj-kondo "RELEASE"]
                                  [expound "RELEASE"]
                                  [nl.jomco/proof-specs "RELEASE"]
                                  [ring/ring-mock "RELEASE"]]
                   :plugins      [[lein-ancient "RELEASE"]]
                   :aliases      {"lint"           ["run" "-m" "clj-kondo.main" "--lint" "src" "test"]
                                  "check-deps"     ["ancient" "check" ":no-profiles" ":exclude" "keep-this-version"]
                                  "upgrade-deps"   ["ancient" "upgrade" ":no-profiles" ":exclude" "keep-this-version"]
                                  ;; Enums are generated from yaml files in the open-education-api/specification github project.
                                  ;; To regenerate, call `lein generate-enums $path-to-open-education-api-specification`
                                  ;; This will regenerate `src/nl/surf/eduhub_rio_mapper/enums.clj`
                                  "generate-enums" ["run" "-m" "generate-enums.main"]
                                  ;; Simple prepopulated call to the raadplegen section of the rio test api. Spits out response body to STDOUT.
                                  "beheren-edn"    ["run" "-m" "xsd-to-edn.main" "schema"]
                                  "types-edn"      ["run" "-m" "xsd-to-edn.main" "types"]
                                  "mapper"         ["run" "-m" "nl.surf.eduhub-rio-mapper.main"]
                                  "proof-specs"    ["run" "-m" "nl.jomco.proof-specs"
                                                    "--verbose"
                                                    "--num-vals" "10"
                                                    "--limit-ms" "5000"
                                                    "--include" "nl.surf.*"
                                                    "--require" "nl.surf.eduhub-rio-mapper.specs.ooapi"]}}

             ;; Make tests fail on conflicting deps. This isn't in the
             ;; root of the project.clj, because that will abort any
             ;; mismatch on additional tooling loaded in development -
             ;; i.e. cider-nrepl and refactor-nrepl generally have
             ;; conflicting transitive dependencies.
             :test {:pedantic?    :abort
                    :dependencies [[pjstadig/humane-test-output "RELEASE"]]
                    :injections   [(require 'pjstadig.humane-test-output)
                                   (pjstadig.humane-test-output/activate!)]}


             :uberjar {:aot :all}}

  :uberjar-name "eduhub-rio-mapper.jar"
  :main nl.surf.eduhub-rio-mapper.main

  :test-selectors {:default #(not-any? % [:e2e :redis])
                   :redis   :redis
                   :e2e     :e2e
                   :all     (constantly true)})
