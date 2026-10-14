;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.otel-javaagent-test
  "Checks the OpenTelemetry javaagent shipped in the docker images.

  The agent only bridges the application's OTel API up to the API
  version it bundles itself; custom metrics recorded through a newer
  API (see `endpoints/metrics.clj`) are silently dropped.

  Needs the agent on the classpath, which `make test-common` does by
  adding the `:deps-to-scan` alias."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.util Properties)))

(def ^:private agent-coordinate 'io.opentelemetry.javaagent/opentelemetry-javaagent)

(defn- read-version-properties
  "Returns the version from a `version.properties` resource, or nil."
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (with-open [in (io/input-stream resource)]
      (let [props (doto (Properties.) (.load in))]
        (or (.getProperty props "sdk.version")
            (.getProperty props "version"))))))

(defn- parse-version
  "Parses \"1.54.1\" or \"1.54.1-alpha\" into [1 54 1]."
  [version]
  (when-let [[_ & parts] (re-find #"^(\d+)\.(\d+)(?:\.(\d+))?" (str version))]
    (mapv #(if % (parse-long %) 0) parts)))

(defn- version<=
  [a b]
  (<= (compare (parse-version a) (parse-version b)) 0))

(deftest version-helpers
  (is (= [1 54 1] (parse-version "1.54.1")))
  (is (= [1 54 1] (parse-version "1.54.1-alpha")))
  (is (= [2 32 0] (parse-version "2.32")))
  (is (nil? (parse-version "unknown")))
  (is (nil? (parse-version nil)))
  (is (version<= "1.54.1" "1.66.0"))
  (is (version<= "1.66.0" "1.66.0"))
  (is (version<= "1.9.0" "1.10.0") "compares numerically, not lexically")
  (is (not (version<= "1.54.1" "1.43.0")) "2.9.0 agent with clj-otel-api 0.2.10")
  (is (not (version<= "2.0.0" "1.99.0"))))

(deftest agent-bridges-application-otel-api
  (let [app-api   (read-version-properties "io/opentelemetry/api/version.properties")
        agent-api (read-version-properties
                   "io/opentelemetry/javaagent/shaded/io/opentelemetry/api/version.properties")]
    (is (some? app-api) "opentelemetry-api is on the application classpath")
    (is (some? agent-api)
        "opentelemetry-javaagent is on the classpath (run with the :deps-to-scan alias)")
    (when (and app-api agent-api)
      (is (version<= app-api agent-api)
          (str "Application uses OpenTelemetry API " app-api
               " but the javaagent only bridges up to " agent-api
               "; upgrade the javaagent in the :deps-to-scan alias in deps.edn")))))

(deftest agent-is-pinned-in-one-place
  (let [deps    (edn/read-string (slurp "deps.edn"))
        version (get-in deps [:aliases :deps-to-scan :extra-deps agent-coordinate])]
    (testing "deps.edn pins the javaagent version"
      (is (string? (:mvn/version version))))
    (testing "antq reports new javaagent releases"
      (is (not (:antq/exclude (meta version)))))
    (testing "docker images use the jar resolved from deps.edn"
      (doseq [dockerfile ["Dockerfile.v5" "Dockerfile.v6"]]
        (let [content (slurp dockerfile)]
          (is (str/includes? content "target/opentelemetry-javaagent.jar") dockerfile)
          (is (not (re-find #"opentelemetry-javaagent-[\d.]+\.jar" content))
              (str dockerfile " does not reference a versioned agent jar")))))))
