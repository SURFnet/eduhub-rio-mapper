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

(ns nl.surf.eduhub-rio-mapper.specs.offerings-test
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.specs.offerings :as offr]
    [nl.surf.eduhub-rio-mapper.test-helper :refer [load-json]]))

(def course-offerings (load-json "fixtures/ooapi/course-offerings.json"))

(def program-offerings (load-json "fixtures/ooapi/program-offerings.json"))

(def program-offering-demo04 (load-json "fixtures/ooapi/program-demo04-offerings.json"))

(deftest validate-fixtures
  (testing "course offerings"
    (let [problems (get (s/explain-data ::offr/OfferingsRequest course-offerings)
                        :clojure.spec.alpha/problems)]
      (is (nil? problems))))

  (testing "no registration status"
    (let [offerings (update-in course-offerings [:items 0 :consumers 0] dissoc :registrationStatus)
          problems (get (s/explain-data ::offr/OfferingsRequest offerings)
                        :clojure.spec.alpha/problems)]
      (is (some? problems))))

  (testing "wrong registration status"
    (let [offerings (update-in course-offerings [:items 0 :consumers 0] assoc :registrationStatus "ajar")
          problems (get (s/explain-data ::offr/OfferingsRequest offerings)
                        :clojure.spec.alpha/problems)]
      (is (some? problems))))

  (testing "explain-program-offerings"
    (let [problems (get (s/explain-data ::offr/OfferingsRequest program-offerings)
                        :clojure.spec.alpha/problems)]
      (is (nil? problems))))

  (testing "explain-program-demo-offerings"
    (let [problems (get (s/explain-data ::offr/OfferingsRequest program-offering-demo04)
                        :clojure.spec.alpha/problems)]
      (is (nil? problems)))))
