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

(ns nl.surf.eduhub-rio-mapper.specs.program-test
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
    (let [check-spec (fn [entity] (spec-helper/check-spec entity ::prg/program "Program"))]
      ;; Program is nil
      (is (= "Top level object is `null`. Expected an Program object."
             (check-spec nil)))
      ;; not a JSON object
      (is (= "Top level object is not a JSON object. Expected an Program object."
             (check-spec [])))
      ;; missing required fields
      (is (= "Top level Program object is missing these required fields: programId, consumers, name, validFrom"
             (check-spec {})))
      ;; incorrect format
      (is (= "The `teachingLanguage` attribute of the program does not conform to the required format."
             (check-spec (assoc program :teachingLanguage "1"))))
      ;; timeline overrides is nil
      (is (= "The `timelineOverrides` attribute should be an array, but it was null."
             (check-spec (assoc program :timelineOverrides nil))))
      ;; timeline overrides is not an array
      (is (= "The `timelineOverrides` attribute should be an array."
             (check-spec (assoc program :timelineOverrides {}))))
      ;; a timeline overrides element does not contain educationSpecification
      (is (= "Each item in the `timelineOverrides` attribute should contain an object with an `program` attribute."
             (check-spec (assoc program :timelineOverrides [{}]))))
      ;; a timeline overrides element does not contain required field `validFrom`
      (is (= "Each item in the `timelineOverrides` attribute should contain an object with an `program` attribute."
             (check-spec (assoc program :timelineOverrides [{:educationSpecification {}}]))))
      ;; program in a timeline overrides element is nil
      (is (= "The `program` attribute within a `timelineOverrides` item should be an object, but it was null."
             (check-spec (assoc program :timelineOverrides [{:validFrom "2019-08-24", :program nil}]))))
      ;; program in a timeline overrides element is not a map
      (is (= "The `program` attribute within a `timelineOverrides` item should be an object."
             (check-spec (assoc program :timelineOverrides [{:validFrom "2019-08-24", :program []}]))))
      ;; program in a timeline overrides element does not contain required element `name`
      (is (= "The `program` attribute within a `timelineOverrides` item should have an attribute `name`."
             (check-spec (assoc program :timelineOverrides [{:validFrom "2019-08-24", :program {}}]))))
      ;; topline element consumers, if present, is an array
      (is (= "The `consumers` attribute should be an array."
             (check-spec (assoc program :consumers {}))))
      ;; topline element consumers, if present, is an array with items
      (is (= "The `consumers` attribute should be an array with at least one item."
             (check-spec (assoc program :consumers []))))
      ;; topline element consumers, if present, is an array in which each items contains a consumerKey.
      (is (= "Each item in the `consumers` attribute should contain an object with an `consumerKey` attribute."
             (check-spec (assoc program :consumers [{}]))))
      ;; topline element consumers, if present, is an array in which there is an item with consumerKey "rio"
      (is (= "Top level `consumers` attribute, if present, must contain exactly one item with `consumerKey` \"rio\"."
             (check-spec (assoc program :consumers [{:consumerKey "fortaleza"}]))))
      ;; No errors for one consumer with consumerKey rio
      (is (nil?
            (check-spec (assoc program :consumers [{:consumerKey "rio"}]))))
      ;; consumer attributes must conform to format
      (is (= "The `acceleratedRoute` attribute of the program's rio consumer does not conform to the required format."
            (check-spec (assoc program :consumers [{:consumerKey "rio", :acceleratedRoute "so_fast"}]))))
      ;; No errors for two consumers with consumerKey rio and another
      (is (nil?
            (check-spec (assoc program :consumers [{:consumerKey "fortaleza"}, {:consumerKey "rio"}]))))
      ;; only one rio consumer allowed
      (is (= "Top level `consumers` attribute, if present, must contain exactly one item with `consumerKey` \"rio\"."
             (check-spec (assoc program :consumers [{:consumerKey "rio"}, {:consumerKey "rio"}]))))
      ;; If present, jointProgram must be a boolean
      (is (= "The `jointProgram` attribute in the rio consumer must be a boolean"
             (check-spec (assoc program :consumers [{:consumerKey "rio", :jointProgram "true"}]))))
      ;; If jointProgram is true, educationUnitCode is required
      (is (= "If the `jointProgram` attribute is true, `educationUnitCode` is required."
             (check-spec (assoc program :consumers [{:consumerKey "rio", :jointProgram true}]))))
      ;; If jointProgram is true, educationUnitCode is required and must be a string.
      (is (= "The `educationUnitCode` attribute must be a string."
             (check-spec (assoc program :consumers [{:consumerKey "rio", :jointProgram true, :educationUnitCode 1234}]))))
      ;; If jointProgram is true, educationUnitCode is required and must have the correct format.
      (is (= "The format of the value of the `educationUnitCode` attribute is invalid."
             (check-spec (assoc program :consumers [{:consumerKey "rio", :jointProgram true, :educationUnitCode "1234"}])))))))

(deftest validate-rio-consumer
  (let [{::s/keys [problems]} (s/explain-data ::prg/rio-consumer rio-consumer)]
    (is (contains? #{nil []} problems))))

(deftest validate-rio-consumer-missing-consumer
  (let [problems (s/explain-data ::prg/consumers [])]
    (is (= "not-empty" (name (get-in problems [::s/problems 0 :pred]))))))

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
