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

(ns nl.surf.eduhub-rio-mapper.ooapi.program-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.specs.helper :as spec-helper]
            [nl.surf.eduhub-rio-mapper.specs.program :as prg]))

(def program (-> "fixtures/ooapi/program.json"
                 io/resource
                 slurp
                 (json/read-str :key-fn keyword)))

(def program-demo04 (-> "fixtures/ooapi/program-demo04.json"
                 io/resource
                 slurp
                 (json/read-str :key-fn keyword)))

(def consumers (-> "fixtures/ooapi/program-consumers.json"
                 io/resource
                 slurp
                 (json/read-str :key-fn keyword)))

(def other-consumer (first consumers))

(def rio-consumer (last consumers))

(deftest test-check-spec
  (testing "program"
    (let [spec ::prg/program]
      ;; Program is nil
      (is (= "Top level object is `null`. Expected an Program object."
             (spec-helper/check-spec nil spec "Program")))
      ;; not a JSON object
      (is (= "Top level object is not a JSON object. Expected an Program object."
             (spec-helper/check-spec [] spec "Program")))
      ;; missing required fields
      (is (= "Top level Program object is missing these required fields: programId, consumers, name, validFrom"
             (spec-helper/check-spec {} spec "Program")))
      ;; timeline overrides is nil
      (is (= "The `timelineOverrides` attribute should be an array, but it was null."
             (spec-helper/check-spec (assoc program :timelineOverrides nil) spec "Program")))
      ;; timeline overrides is not an array
      (is (= "The `timelineOverrides` attribute should be an array."
             (spec-helper/check-spec (assoc program :timelineOverrides {}) spec "Program")))
      ;; a timeline overrides element does not contain educationSpecification
      (is (= "Each item in the `timelineOverrides` attribute should contain an object with an `program` attribute."
             (spec-helper/check-spec (assoc program :timelineOverrides [{}]) spec "Program")))
      ;; a timeline overrides element does not contain required field `validFrom`
      (is (= "Each item in the `timelineOverrides` attribute should contain an object with an `program` attribute."
             (spec-helper/check-spec (assoc program :timelineOverrides [{:educationSpecification {}}]) spec "Program")))
      ;; program in a timeline overrides element is nil
      (is (= "The `program` attribute within a `timelineOverrides` item should be an object, but it was null."
             (spec-helper/check-spec (assoc program :timelineOverrides [{:validFrom "2019-08-24", :program nil}]) spec "Program")))
      ;; program in a timeline overrides element is not a map
      (is (= "The `program` attribute within a `timelineOverrides` item should be an object."
             (spec-helper/check-spec (assoc program :timelineOverrides [{:validFrom "2019-08-24", :program []}]) spec "Program")))
      ;; program in a timeline overrides element does not contain required element `name`
      (is (= "The `program` attribute within a `timelineOverrides` item should have an attribute `name`."
             (spec-helper/check-spec (assoc program :timelineOverrides [{:validFrom "2019-08-24", :program {}}]) spec "Program")))
      ;; topline element consumers, if present, is an array
      (is (= "The `consumers` attribute should be an array."
             (spec-helper/check-spec (assoc program :consumers {}) spec "Program")))
      ;; topline element consumers, if present, is an array with items
      (is (= "The `consumers` attribute should be an array with at least one item."
             (spec-helper/check-spec (assoc program :consumers []) spec "Program")))
      ;; topline element consumers, if present, is an array in which each items contains a consumerKey.
      (is (= "Each item in the `consumers` attribute should contain an object with an `consumerKey` attribute."
             (spec-helper/check-spec (assoc program :consumers [{}]) spec "Program")))
      ;; topline element consumers, if present, is an array in which there is an item with consumerKey "rio"
      (is (= "Top level `consumers` attribute, if present, must contain exactly one item with `consumerKey` \"rio\"."
             (spec-helper/check-spec (assoc program :consumers [{:consumerKey "fortaleza"}]) spec "Program")))
      ;; No errors for one consumer with consumerKey rio
      (is (nil?
            (spec-helper/check-spec (assoc program :consumers [{:consumerKey "rio"}]) spec "Program")))
      ;; No errors for two consumers with consumerKey rio and another
      (is (nil?
            (spec-helper/check-spec (assoc program :consumers [{:consumerKey "fortaleza"}, {:consumerKey "rio"}]) spec "Program")))
      ;; only one rio consumer allowed
      (is (= "Top level `consumers` attribute, if present, must contain exactly one item with `consumerKey` \"rio\"."
             (spec-helper/check-spec (assoc program :consumers [{:consumerKey "rio"}, {:consumerKey "rio"}]) spec "Program")))
      ;; If present, jointProgram must be a boolean
      (is (= "The `jointProgram` attribute in the rio consumer must be a boolean"
             (spec-helper/check-spec (assoc program :consumers [{:consumerKey "rio", :jointProgram "true"}]) spec "Program")))
      ;; If jointProgram is true, educationUnitCode is required
      (is (= "If the `jointProgram` attribute is true, `educationUnitCode` is required."
             (spec-helper/check-spec (assoc program :consumers [{:consumerKey "rio", :jointProgram true}]) spec "Program")))
      ;; If jointProgram is true, educationUnitCode is required and must be a string.
      (is (= "The `educationUnitCode` attribute must be a string."
             (spec-helper/check-spec (assoc program :consumers [{:consumerKey "rio", :jointProgram true, :educationUnitCode 1234}]) spec "Program")))
      ;; If jointProgram is true, educationUnitCode is required and must have the correct format.
      (is (= "The format of the value of the `educationUnitCode` attribute is invalid."
             (spec-helper/check-spec (assoc program :consumers [{:consumerKey "rio", :jointProgram true, :educationUnitCode "1234"}]) spec "Program"))))))

(deftest validate-rio-consumer
  (let [{::s/keys [problems]} (s/explain-data ::prg/rio-consumer rio-consumer)]
    (is (contains? #{nil []} problems))))

(deftest validate-rio-consumer-missing-consumer
  (let [problems (s/explain-str ::prg/consumers [])]
    (is (= "[] - failed: not-empty spec: :nl.surf.eduhub-rio-mapper.specs.program/consumers\n" problems))))

(deftest validate-rio-consumer-wrong-education-offerer-code
  (let [{::s/keys [problems]} (s/explain-data ::prg/rio-consumer (assoc rio-consumer :educationOffererCode "123B123"))]
    (is (= :educationOffererCode (-> problems first :path first)))))

(deftest dont-validate-admission-reqs
  (let [{::s/keys [problems]} (s/explain-data ::prg/program (assoc-in program [:admissionRequirements 0 :value]
                                                                      (apply str (take 1010 (repeatedly #(char (+ (rand-int 26) 97)))))))]
    (is (contains? #{nil []} problems))))

(deftest validate-consumers
  (let [{::s/keys [problems]} (s/explain-data ::prg/consumers [other-consumer rio-consumer])]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-explain
  (let [{::s/keys [problems]} (s/explain-data ::prg/program program)]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-duration-optional-explain
  (let [{::s/keys [problems]} (s/explain-data ::prg/program (dissoc program :duration))]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-explain-demo04
  (let [{::s/keys [problems]} (s/explain-data ::prg/program program-demo04)]
    (is (contains? #{nil []} problems))))
