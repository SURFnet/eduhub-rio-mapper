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

(ns nl.surf.eduhub-rio-mapper.specs.education-specification-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.specs.education-specification :as es]
            [nl.surf.eduhub-rio-mapper.specs.helper :as spec-helper]))

(def education-specification (-> "fixtures/ooapi/education-specification.json"
                                 io/resource
                                 slurp
                                 (json/read-str :key-fn keyword)))

(deftest test-check-spec
  (testing "education specification"
    (let [valid-eduspec education-specification
          check-spec (fn [entity] (spec-helper/check-spec entity ::es/EducationSpecificationTopLevel "EducationSpecification"))]
      ;; Education specification is nil
      (is (= "Top level object is `null`. Expected an EducationSpecification object."
             (check-spec nil)))
      ;; not a JSON object
      (is (= "Top level object is not a JSON object. Expected an EducationSpecification object."
             (check-spec [])))
      ;; missing required fields
      (is (= "Top level EducationSpecification object is missing these required fields: educationSpecificationId, educationSpecificationType, primaryCode, validFrom, name, consumers"
             (check-spec {})))
      ;; incorrect format
      (is (= "The `formalDocument` attribute of the education specification does not conform to the required format."
             (check-spec (assoc valid-eduspec :formalDocument "medal"))))
      ;; valid-type-and-subtype? (course/variant) fails
      (is (= "Invalid combination of educationSpecificationType and educationSpecificationSubType fields"
             (check-spec (assoc valid-eduspec :educationSpecificationType "EducationSpecification")
                                    )))
      ;; valid-type-and-subtype? (course/nil) succeeds
      (is (nil?
           (check-spec (assoc valid-eduspec :educationSpecificationType "course"
                                                        :consumers [{:consumerKey "rio"}])
                                  )))
      ;; not-equal-to-parent? fails
      (is (= "Fields educationSpecificationId and parent are not allowed to be equal"
             (check-spec (assoc valid-eduspec :parent (:educationSpecificationId valid-eduspec))
                                    )))
      ;; level-sector-map-to-rio?
      (is (= "Invalid combination of level and sector fields"
             (check-spec (assoc valid-eduspec :level "NONE"))))
      ;; timeline overrides is nil
      (is (= "The `timelineOverrides` attribute should be an array, but it was null."
             (check-spec (assoc valid-eduspec :timelineOverrides nil))))
      ;; timeline overrides is not an array
      (is (= "The `timelineOverrides` attribute should be an array."
             (check-spec (assoc valid-eduspec :timelineOverrides {}))))
      ;; a timeline overrides element does not contain educationSpecification
      (is (= "Each item in the `timelineOverrides` attribute should contain an object with an `educationSpecification` attribute."
             (check-spec (assoc valid-eduspec :timelineOverrides [{}]))))
      ;; a timeline overrides element does not contain required field `validFrom`
      (is (= "Each item in the `timelineOverrides` attribute should contain an object with a `validFrom` attribute."
             (check-spec (assoc valid-eduspec :timelineOverrides [{:educationSpecification {}}]))))
      ;; educationSpecification in a timeline overrides element is nil
      (is (= "The `educationSpecification` attribute within a `timelineOverrides` item should be an object, but it was null."
             (check-spec (assoc valid-eduspec :timelineOverrides [{:validFrom "2019-08-24", :educationSpecification nil}]))))
      ;; educationSpecification in a timeline overrides element is not a map
      (is (= "The `educationSpecification` attribute within a `timelineOverrides` item should be an object."
             (check-spec (assoc valid-eduspec :timelineOverrides [{:validFrom "2019-08-24", :educationSpecification []}]))))
      ;; educationSpecification in a timeline overrides element does not contain required element `name`
      (is (= "The `educationSpecification` attribute within a `timelineOverrides` item should have an attribute `name`."
             (check-spec (assoc valid-eduspec :timelineOverrides [{:validFrom "2019-08-24", :educationSpecification {}}]))))
      ;; topline element consumers, if present, is an array
      (is (= "The `consumers` attribute should be an array."
             (check-spec (assoc valid-eduspec :consumers {}))))
      ;; topline element consumers, if present, is an array with items
      (is (= "The `consumers` attribute should be an array with at least one item."
             (check-spec (assoc valid-eduspec :consumers []))))
      ;; topline element consumers, if present, is an array in which each items contains a consumerKey.
      (is (= "Each item in the `consumers` attribute should contain an object with an `consumerKey` attribute."
             (check-spec (assoc valid-eduspec :consumers [{}]))))
      ;; topline element consumers, if present, is an array in which there is an item with consumerKey "rio"
      (is (= "Top level `consumers` attribute, if present, must contain exactly one item with `consumerKey` \"rio\"."
             (check-spec (assoc valid-eduspec :consumers [{:consumerKey "fortaleza"}]))))
      ;; No errors for one consumer with consumerKey rio
      (is (nil?
            (check-spec (assoc valid-eduspec :consumers [{:consumerKey "rio"}]))))
      ;; No errors for two consumers with consumerKey rio and another
      (is (nil?
            (check-spec (assoc valid-eduspec :consumers [{:consumerKey "fortaleza"}, {:consumerKey "rio"}]))))
      ;; only one rio consumer allowed
      (is (= "Top level `consumers` attribute, if present, must contain exactly one item with `consumerKey` \"rio\"."
            (check-spec (assoc valid-eduspec :consumers [{:consumerKey "rio"}, {:consumerKey "rio"}]))))
      ;; consumer attributes must conform to format
      (is (= "The `category` attribute of the education specification's rio consumer does not conform to the required format."
             (check-spec (assoc valid-eduspec :consumers [{:consumerKey "rio", :category "blue"}])))))))

(deftest validate-no-problems-in-fixtures
  (let [problems (get (s/explain-data ::es/EducationSpecification education-specification)
                      ::s/problems)]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-name-required
  (let [value (dissoc education-specification :name)]
    (is (not (s/valid? ::es/EducationSpecification value)))))

(deftest validate-fixtures-level-sector-required
  (let [value (dissoc education-specification :level :sector)]
    (is (not (s/valid? ::es/EducationSpecificationTopLevel value)))))

(deftest validate-fixtures-level-sector-not-required-for-private
  (let [value (dissoc education-specification :level :sector)
        value (assoc value :educationSpecificationType "privateProgram")
        value (update-in value [:consumers 0] dissoc :educationSpecificationSubType)]
    (is (s/valid? ::es/EducationSpecification value))))

(deftest validate-fixtures-language-required-in-description
  (let [value (update-in education-specification [:description 0] dissoc :language)]
    (is (not (s/valid? ::es/EducationSpecification value)))))

(deftest validate-fixtures-dont-identify-with-parent
  (let [value (assoc education-specification :parent (:educationSpecificationId education-specification))]
    (is (not (s/valid? ::es/EducationSpecificationTopLevel value)))))

(deftest validate-fixtures-invalid-codetype
  (let [value (assoc-in education-specification [:primaryCode :codeType] "undefined")]
    (is (not (s/valid? ::es/EducationSpecificationTopLevel value)))))

(deftest validate-fixtures-custom-codetype
  (is (s/valid? ::es/EducationSpecification (assoc-in education-specification [:primaryCode :codeType] "x-undefined"))))

(deftest validate-fixtures-invalid-subtype
  (let [value (assoc-in education-specification [:consumers 0 :educationSpecificationSubType] "invalid")]
    (is (not (s/valid? ::es/EducationSpecificationTopLevel value)))))

(deftest validate-invalid-value-in-top-level-attribute
  (doseq [[key invalid-codes] [[:fieldsOfStudy ["12345" "123a"]]
                               [:formalDocument ["medal"]]
                               [:level ["grandmaster"]]
                               [:sector ["culturele"]]
                               [:validFrom ["2022-31-12" "29-02-2020"]]
                               [:validTo ["2022-31-12" "29-02-2020"]]
                               [:parent ["123e4567-e89b-12d3-a456" "g23e4567-e89b-12d3-a456-426614174111"]]]]
    (doseq [invalid-code invalid-codes]
      (is (not (s/valid? ::es/EducationSpecificationTopLevel
                         (assoc education-specification key invalid-code)))))))

(deftest validate-fixtures-invalid-otherCodes-codetype
  (let [value (assoc-in education-specification [:otherCodes 0 :codeType] "undefined")]
    (is (not (s/valid? ::es/EducationSpecification value)))))

(deftest validate-illegal-language-code-in-all-language-types-string-arrays
  (doseq [path [[:name 0 :language]
                [:description 0 :language]
                [:learningOutcomes 0 0 :language]]]
    (doseq [invalid-code [nil "" "-" "vrooom" "!" "e"]]
      (let [eduspec (assoc-in education-specification path invalid-code)]
        (is (not (s/valid? ::es/EducationSpecification eduspec)))))))

(deftest validate-maxlength-abbreviation
  (is (not (s/valid? ::es/EducationSpecification
                     (assoc education-specification :abbreviation (apply str (repeat 257 "a")))))))

(deftest validate-maxlength-link
  (is (not (s/valid? ::es/EducationSpecification
                     (assoc education-specification :link (apply str (repeat 2049 "a")))))))
