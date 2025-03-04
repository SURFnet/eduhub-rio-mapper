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

(ns nl.surf.eduhub-rio-mapper.ooapi.loader-test
  (:require
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
    [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.test-helper :as helper]
    [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils])
  (:import [java.net URI]))

;; never mind trying to record
;; just create the vcr files manually
(deftest loader
  (let [vcr  (helper/make-vcr :playback)
        config       (helper/make-test-config)
        ooapi-loader (ooapi.loader/make-ooapi-http-loader (URI. "https://jomco.github.io/rio-mapper-test-data/")
                                                          (:gateway-credentials config)
                                                          config)
        client-info  (clients-info/client-info (:clients config) "rio-mapper-dev4.jomco.nl")
        request      {::ooapi/root-url (URI. "https://rio-mapper-dev4.jomco.nl/")
                      ::ooapi/type     "program-offerings"
                      ::ooapi/id       "6456b864-c121-bb61-fda2-109251a1c777"
                      :gateway-credentials (:gateway-credentials config)}]
    (binding [http-utils/*vcr* (vcr "test/fixtures/ooapi-loader" 1 "offering")]
      (let [items (:items (ooapi-loader (merge client-info request {:page-size 2})))]
        (is (= 3 (count items)))))))
