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

(ns nl.surf.eduhub-rio-mapper.config-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.config :as config])
  (:import [clojure.lang ExceptionInfo]
           [java.io File]))

(def versions ["v5" "v6"])

(defn- default-env [version]
  {:surf-conext-introspection-endpoint "https://gateway.dev.surfnet.nl/",
   :keystore-alias "default",
   (keyword (str "gateway-password-" version)) "default",
   :keystore "test/keystore.jks",
   :clients-info-path "test/test-clients.json",
   :rio-recipient-oin "default",
   :rio-update-url "default",
   (keyword (str "gateway-user-" version)) "default",
   :surf-conext-client-id "default",
   :surf-conext-client-secret "default",
   (keyword (str "gateway-root-url-" version)) "https://gateway.test.surfeduhub.nl/",
   :rio-read-url "default"})

(def default-expected-value {:keystore-pass "repelsteeltje!",
                             :gateway-credentials
                             {:password "default", :username "default"},
                             :redis-conn {:spec {:uri "redis://localhost"}}})

(defn- test-env [version env]
  (config/load-config-from-env version (merge (default-env version) env)))

(deftest versioned-gateway-settings
  (testing "v5"
    (is (= #{:gateway-user-v5 :gateway-password-v5 :gateway-root-url-v5}
           (set (filter #(str/starts-with? (name %) "gateway-") (keys (config/make-opts-spec "v5"))))))
    (is (= [:gateway-password-v5 :keystore-password :surf-conext-client-secret :redis-uri]
           (config/env-keys-with-alternate-file-secret "v5")))
    (is (str/includes? (config/help "v5") "GATEWAY_PASSWORD_V5")))
  (testing "v6"
    (is (= #{:gateway-user-v6 :gateway-password-v6 :gateway-root-url-v6}
           (set (filter #(str/starts-with? (name %) "gateway-") (keys (config/make-opts-spec "v6"))))))
    (is (= [:gateway-password-v6 :keystore-password :surf-conext-client-secret :redis-uri]
           (config/env-keys-with-alternate-file-secret "v6")))
    (is (str/includes? (config/help "v6") "GATEWAY_USER_V6"))))

(deftest missing-secret
  (doseq [version versions]
    (is (= {:keystore-password "missing"}
           (last (test-env version {}))))))

(deftest only-value-secret
  (doseq [version versions]
    (let [env {:keystore-password "repelsteeltje!"}]
      (is (= default-expected-value
             (-> (test-env version env) first (select-keys [:keystore-pass :gateway-credentials :redis-conn])))))))

(deftest only-file-secret
  (doseq [version versions]
    (let [path (.getAbsolutePath (File/createTempFile "test-secret" ".txt"))
          env {:keystore-password-file path}]
      (spit path "repelsteeltje!")
      (is (= default-expected-value
             (-> (test-env version env) first (select-keys [:keystore-pass :gateway-credentials :redis-conn])))))))

(deftest file-secret-for-env-with-default
  (doseq [version versions]
    (let [path (.getAbsolutePath (File/createTempFile "test-secret" ".txt"))
          env {:redis-uri-file path :keystore-password "repelsteeltje!"}]
      (spit path "redis://localhost:6381")
      (is (= (assoc-in default-expected-value [:redis-conn :spec :uri] "redis://localhost:6381")
             (-> (test-env version env) first (select-keys [:keystore-pass :gateway-credentials :redis-conn])))))))

(deftest only-file-secret-file-missing
  (doseq [version versions]
    (let [env {:keystore-password-file "missing-file"}]
      (is (thrown? ExceptionInfo (test-env version env))))))

(deftest both-types-of-secret-specified
  (doseq [version versions]
    (let [path (.getAbsolutePath (File/createTempFile "test-secret" ".txt"))
          env {:keystore-password "repelsteeltje!"
               :keystore-password-file path}]
      (spit path "repelsteeltje!")
      (is (thrown? ExceptionInfo (test-env version env))))))
