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

(ns nl.surf.eduhub-rio-mapper.utils.logging-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.utils.logging :refer [->mdc-entry redact-placeholder]]))

(deftest mdc-entry-test

  (testing "simple string key and value"
    (is (= ["foo" "bar"]
           (->mdc-entry ["foo" "bar"]))))

  (testing "keyword key with string value"
    (is (= ["test-key" "test-value"]
           (->mdc-entry [:test-key "test-value"]))))

  (testing "qualified keyword key"
    (is (= ["my.ns/test-key" "test-value"]
           (->mdc-entry [:my.ns/test-key "test-value"]))))

  (testing "keyword value gets converted to string without colon"
    (is (= ["key" "keyword-value"]
           (->mdc-entry ["key" :keyword-value]))))

  (testing "sequential value gets formatted as comma-separated quoted values"
    (is (= ["items" "'one', 'two', 'three'"]
           (->mdc-entry ["items" ["one" "two" "three"]]))))

  (testing "map value gets formatted as key => value pairs"
    (is (= ["config" "{ name => test-app, version => 1.0 }"]
           (->mdc-entry ["config" {"name" "test-app" "version" "1.0"}]))))

  (testing "numeric value gets converted to string"
    (is (= ["count" "42"]
           (->mdc-entry ["count" 42]))))

  (testing "password key gets redacted"
    (is (= ["password" redact-placeholder]
           (->mdc-entry ["password" "secret123"]))))

  (testing "secret key gets redacted (case sensitive)"
    (is (= ["secret_token" redact-placeholder]
           (->mdc-entry ["secret_token" "very-secret-value"]))))

  (testing "secret key gets redacted (case insensitive)"
    (is (= ["SECRET_TOKEN" redact-placeholder]
           (->mdc-entry ["SECRET_TOKEN" "very-secret-value"]))))

  (testing "client-id key gets redacted"
    (is (= ["client-id" redact-placeholder]
           (->mdc-entry ["client-id" "client-12345"]))))

  (testing "deeply nested sensitive data gets redacted"
    (let [[k formatted] (->mdc-entry
                          [:session
                           {:level1 {:auth {:secret_token "secret-value"
                                            :proxy-options {:password "pw"}}
                                     :clients [{:client-id "client-6789"
                                                :notes ["safe-value" {:passcode "abc123"}]}]}}])]
      (is (= "session" k))
      (is (str/includes? formatted redact-placeholder))
      (is (not (str/includes? formatted "secret-value")))
      (is (not (str/includes? formatted "client-6789")))
      (is (not (str/includes? formatted "abc123")))
      (is (str/includes? formatted "safe-value")))))
