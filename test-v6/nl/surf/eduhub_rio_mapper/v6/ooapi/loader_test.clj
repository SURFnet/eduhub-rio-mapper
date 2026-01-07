;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2024 SURFnet B.V.
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

(ns nl.surf.eduhub-rio-mapper.v6.ooapi.loader-test
  (:require
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.v6.ooapi.loader :as ooapi.loader]
    [nl.surf.eduhub-rio-mapper.v6.test-helper :as helper])
  (:import [clojure.lang ExceptionInfo]
           [java.net URI]))

(def vcr-mode (if (= "true" (System/getenv "VCR_RECORD"))
                :record
                :playback))

;; never mind trying to record
;; just create the vcr files manually
(deftest test-offerings
  (let [vcr  (helper/make-vcr vcr-mode)
        config       (helper/make-test-config)
        ooapi-loader (ooapi.loader/make-ooapi-http-loader (URI. "https://jomco.github.io/rio-mapper-test-data/")
                                                          (:gateway-credentials config)
                                                          config)
        client-info  (clients-info/client-info (:clients config) "rio-mapper-dev4.jomco.nl")
        request      {::ooapi/root-url (URI. "https://rio-mapper-dev4.jomco.nl/")
                      ::ooapi/type     "program-offerings"
                      ::ooapi/id       "6456b864-c121-bb61-fda2-109251a1c777"
                      :gateway-credentials (:gateway-credentials config)}]
    (binding [http-utils/*vcr* (vcr "test-v6/fixtures/vcr/ooapi-loader" 1 "offering")]
      (let [data (ooapi-loader (merge client-info request))
            items (:items data)]
        (is (= 3 (count items)))))))

;; We test only one error here, to make sure that the validating loader includes the OpenAPI error
(deftest test-invalid
  (let [vcr  (helper/make-vcr vcr-mode)
        config       (helper/make-test-config)
        ooapi-loader (ooapi.loader/make-ooapi-http-loader (URI. "https://jomco.github.io/rio-mapper-test-data/")
                                                          (:gateway-credentials config)
                                                          config)
        client-info  (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        request      {::ooapi/root-url (URI. "https://rio-mapper-dev.jomco.nl/")
                      ::ooapi/type     "programme"
                      ::ooapi/id       "6456b864-c121-bb61-fda2-109251a1c777"
                      :gateway-credentials (:gateway-credentials config)}]
    ;; This education specification lacks an educationSpecificationId
    (binding [http-utils/*vcr* (vcr "test-v6/fixtures/vcr/ooapi-loader" 2 "programme")]
      (let [ex (is (thrown? ExceptionInfo (-> (merge client-info request) ooapi-loader)))]
        (is (= {:issue "schema-validation-error"
                :canonical-schema-path ["components" "schemas" "ProgrammeId" "required"]}
               (select-keys (first (:issues (ex-data ex)))
                            [:issue :canonical-schema-path])))))))
