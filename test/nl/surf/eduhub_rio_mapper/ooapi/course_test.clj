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

(ns nl.surf.eduhub-rio-mapper.ooapi.course-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.specs.course :as crs]
            [nl.surf.eduhub-rio-mapper.specs.helper :as spec-helper]))

(def course (-> "fixtures/ooapi/course.json"
                io/resource
                slurp
                (json/read-str :key-fn keyword)))

(deftest tmp
  (let [check-spec (fn [entity] (spec-helper/check-spec entity ::crs/course "Course"))]
    (is (= "The `teachingLanguage` attribute of the course does not conform to the required format."
           (check-spec (assoc course :teachingLanguage "1"))))))

(deftest test-check-spec
  (let [check-spec (fn [entity] (spec-helper/check-spec entity ::crs/course "Course"))]
    ;; Course is nil
    (is (= "Top level object is `null`. Expected an Course object."
           (check-spec nil)))
    ;; not a JSON object
    (is (= "Top level object is not a JSON object. Expected an Course object."
           (check-spec [])))
    ;; missing required fields
    (is (= "Top level Course object is missing these required fields: consumers, courseId, duration, educationSpecification, name, validFrom"
           (check-spec {})))
    ;; incorrect format
    (is (= "The `teachingLanguage` attribute of the course does not conform to the required format."
           (check-spec (assoc course :teachingLanguage "1"))))
    ;; timeline overrides is nil
    (is (= "The `timelineOverrides` attribute should be an array, but it was null."
           (check-spec (assoc course :timelineOverrides nil))))
    ;; timeline overrides is not an array
    (is (= "The `timelineOverrides` attribute should be an array."
           (check-spec (assoc course :timelineOverrides {}))))
    ;; a timeline overrides element does not contain educationSpecification
    (is (= "Each item in the `timelineOverrides` attribute should contain an object with an `course` attribute."
           (check-spec (assoc course :timelineOverrides [{}]))))
    ;; a timeline overrides element does not contain required field `validFrom`
    (is (= "Each item in the `timelineOverrides` attribute should contain an object with an `course` attribute."
           (check-spec (assoc course :timelineOverrides [{:educationSpecification {}}]))))
    ;; course in a timeline overrides element is nil
    (is (= "The `course` attribute within a `timelineOverrides` item should be an object, but it was null."
           (check-spec (assoc course :timelineOverrides [{:validFrom "2019-08-24", :course nil}]))))
    ;; course in a timeline overrides element is not a map
    (is (= "The `course` attribute within a `timelineOverrides` item should be an object."
           (check-spec (assoc course :timelineOverrides [{:validFrom "2019-08-24", :course []}]))))
    ;; course in a timeline overrides element does not contain required element `name`
    (is (= "The `course` attribute within a `timelineOverrides` item should have an attribute `name`."
           (check-spec (assoc course :timelineOverrides [{:validFrom "2019-08-24", :course {}}]))))
    ;; topline element consumers, if present, is an array
    (is (= "The `consumers` attribute should be an array."
           (check-spec (assoc course :consumers {}))))
    ;; topline element consumers, if present, is an array with items
    (is (= "The `consumers` attribute should be an array with at least one item."
           (check-spec (assoc course :consumers []))))
    ;; consumer attributes must conform to format
    (is (= "The `educationLocationCode` attribute of the course's rio consumer does not conform to the required format."
           (check-spec (assoc course :consumers [{:consumerKey "rio", :educationLocationCode 123}]))))
    ;; topline element consumers, if present, is an array in which each items contains a consumerKey.
    (is (= "Each item in the `consumers` attribute should contain an object with an `consumerKey` attribute."
           (check-spec (assoc course :consumers [{}]))))
    ;; topline element consumers, if present, is an array in which there is an item with consumerKey "rio"
    (is (= "Top level `consumers` attribute, if present, must contain exactly one item with `consumerKey` \"rio\"."
           (check-spec (assoc course :consumers [{:consumerKey "fortaleza"}]))))
    ;; No errors for one consumer with consumerKey rio
    (is (nil?
          (check-spec (assoc course :consumers [{:consumerKey "rio"}]))))
    ;; No errors for two consumers with consumerKey rio and another
    (is (nil?
          (check-spec (assoc course :consumers [{:consumerKey "fortaleza"}, {:consumerKey "rio"}]))))
    ;; only one rio consumer allowed
    (is (= "Top level `consumers` attribute, if present, must contain exactly one item with `consumerKey` \"rio\"."
           (check-spec (assoc course :consumers [{:consumerKey "rio"}, {:consumerKey "rio"}]))))))

(deftest validate-fixtures-explain
  (let [problems (get (s/explain-data ::crs/course course)
                      :clojure.spec.alpha/problems)]
    (is (nil? problems))))

(deftest validate-rio-consumer-missing-consumer
  (let [problems (s/explain-str ::crs/consumers [])]
    (is (= "[] - failed: not-empty spec: :nl.surf.eduhub-rio-mapper.specs.course/consumers\n" problems))))
