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

(deftest test-check-spec
  (testing "course"
    (let [spec ::crs/course]
      ;; Course is nil
      (is (= "Top level object is `null`. Expected an Course object."
             (spec-helper/check-spec nil spec "Course")))
      ;; not a JSON object
      (is (= "Top level object is not a JSON object. Expected an Course object."
             (spec-helper/check-spec [] spec "Course")))
      ;; missing required fields
      (is (= "Top level EducationSpecification object is missing these required fields: consumers, courseId, duration, educationSpecification, name, validFrom"
             (spec-helper/check-spec {} spec "Course")))
      ;; timeline overrides is nil
      (is (= "The `timelineOverrides` attribute should be an array, but it was null."
             (spec-helper/check-spec (assoc course :timelineOverrides nil) spec "Course")))
      ;; timeline overrides is not an array
      (is (= "The `timelineOverrides` attribute should be an array."
             (spec-helper/check-spec (assoc course :timelineOverrides {}) spec "Course")))
      ;; a timeline overrides element does not contain educationSpecification
      (is (= "Each item in the `timelineOverrides` attribute should contain an object with an `course` attribute."
             (spec-helper/check-spec (assoc course :timelineOverrides [{}]) spec "Course")))
      ;; a timeline overrides element does not contain required field `validFrom`
      (is (= "Each item in the `timelineOverrides` attribute should contain an object with an `course` attribute."
             (spec-helper/check-spec (assoc course :timelineOverrides [{:educationSpecification {}}]) spec "Course")))
      ;; course in a timeline overrides element is nil
      (is (= "The `course` attribute within a `timelineOverrides` item should be an object, but it was null."
             (spec-helper/check-spec (assoc course :timelineOverrides [{:validFrom "2019-08-24", :course nil}]) spec "Course")))
      ;; course in a timeline overrides element is not a map
      (is (= "The `course` attribute within a `timelineOverrides` item should be an object."
             (spec-helper/check-spec (assoc course :timelineOverrides [{:validFrom "2019-08-24", :course []}]) spec "Course")))
      ;; course in a timeline overrides element does not contain required element `name`
      (is (= "The `course` attribute within a `timelineOverrides` item should have an attribute `name`."
             (spec-helper/check-spec (assoc course :timelineOverrides [{:validFrom "2019-08-24", :course {}}]) spec "Course")))
      ;; topline element consumers, if present, is an array
      (is (= "The `consumers` attribute should be an array."
             (spec-helper/check-spec (assoc course :consumers {}) spec "Course")))
      ;; topline element consumers, if present, is an array with items
      (is (= "The `consumers` attribute should be an array with at least one item."
             (spec-helper/check-spec (assoc course :consumers []) spec "Course")))
      ;; topline element consumers, if present, is an array in which each items contains a consumerKey.
      (is (= "Each item in the `consumers` attribute should contain an object with an `consumerKey` attribute."
             (spec-helper/check-spec (assoc course :consumers [{}]) spec "Course")))
      ;; topline element consumers, if present, is an array in which there is an item with consumerKey "rio"
      (is (= "Top level `consumers` attribute, if present, must contain exactly one item with `consumerKey` \"rio\"."
             (spec-helper/check-spec (assoc course :consumers [{:consumerKey "fortaleza"}]) spec "Course")))
      ;; No errors for one consumer with consumerKey rio
      (is (nil?
            (spec-helper/check-spec (assoc course :consumers [{:consumerKey "rio"}]) spec "Course")))
      ;; No errors for two consumers with consumerKey rio and another
      (is (nil?
            (spec-helper/check-spec (assoc course :consumers [{:consumerKey "fortaleza"}, {:consumerKey "rio"}]) spec "Course")))
      ;; only one rio consumer allowed
      (is (= "Top level `consumers` attribute, if present, must contain exactly one item with `consumerKey` \"rio\"."
             (spec-helper/check-spec (assoc course :consumers [{:consumerKey "rio"}, {:consumerKey "rio"}]) spec "Course"))))))

(deftest validate-fixtures-explain
  (let [problems (get (s/explain-data ::crs/course course)
                      :clojure.spec.alpha/problems)]
    (is (nil? problems))))

(deftest validate-rio-consumer-missing-consumer
  (let [problems (s/explain-str ::crs/consumers [])]
    (is (= "[] - failed: not-empty spec: :nl.surf.eduhub-rio-mapper.specs.course/consumers\n" problems))))
