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

(ns nl.surf.eduhub-rio-mapper.rio.helper-test
  (:require [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.rio.helper :as helper]))

(deftest ->xml-test
  (testing "->xml with aangebodenHOOpleidingsonderdeel"
    (is (= [:duo:aangebodenHOOpleidingsonderdeel
            [:duo:begindatum "2024-01-01"]
            [:duo:kenmerken
             [:duo:kenmerknaam "eigenAangebodenOpleidingSleutel"]
             [:duo:kenmerkwaardeTekst "test-course-123"]]]
           (helper/->xml {:eigenAangebodenOpleidingSleutel "test-course-123"
                          :naam "Test course"
                          :begindatum "2024-01-01"}
                         "aangebodenHOOpleidingsonderdeel"))))

  (testing "->xml with aangebodenHOOpleiding"
    (is (= [:duo:aangebodenHOOpleiding
            [:duo:begindatum "2024-01-01"]
            [:duo:kenmerken
             [:duo:kenmerknaam "eigenAangebodenOpleidingSleutel"]
             [:duo:kenmerkwaardeTekst "test-program-123"]]]
           (helper/->xml {:eigenAangebodenOpleidingSleutel "test-program-123"
                          :naam "Test program"
                          :begindatum "2024-01-01"}
                         "aangebodenHOOpleiding"))))

  (testing "->xml with aangebodenParticuliereOpleiding"
    (is (= [:duo:aangebodenParticuliereOpleiding
            [:duo:begindatum "2024-01-01"]
            [:duo:kenmerken
             [:duo:kenmerknaam "eigenAangebodenOpleidingSleutel"]
             [:duo:kenmerkwaardeTekst "test-private-123"]]]
           (helper/->xml {:eigenAangebodenOpleidingSleutel "test-private-123"
                          :naam "Test private program"
                          :begindatum "2024-01-01"}
                         "aangebodenParticuliereOpleiding"))))

  (testing "->xml with empty map"
    (is (= [:duo:aangebodenParticuliereOpleiding]
           (helper/->xml {} "aangebodenParticuliereOpleiding"))))

  (testing "->xml with function as rio-obj"
    ;; When rio-obj is allowed to be a function
    (let [test-fn (fn [key] (when (= key :begindatum)
                              "2024-01-01"))]
      (is (= [:duo:aangebodenHOOpleiding
              [:duo:begindatum "2024-01-01"]]
             (helper/->xml test-fn "aangebodenHOOpleiding")))))

  (testing "->xml with string type kenmerken"
    (is (= [:duo:aangebodenHOOpleiding
            [:duo:kenmerken
             [:duo:kenmerknaam "eigenAangebodenOpleidingSleutel"]
             [:duo:kenmerkwaardeTekst "1234-abcdef-98765432"]]]
           (helper/->xml {:eigenAangebodenOpleidingSleutel "1234-abcdef-98765432"}
                         "aangebodenHOOpleiding"))))

  (testing "->xml with number type kenmerken"
    (let [result (helper/->xml {:deelnemersplaatsen 100
                                :eigenAangebodenOpleidingSleutel "1234-abcdef-98765432"}
                               "aangebodenHOOpleiding")]
      (is [] result)))

  (testing "->xml with multiple different kenmerk types"
    (is (= [:duo:aangebodenHOOpleiding
            [:duo:kenmerken
             [:duo:kenmerknaam "eigenAangebodenOpleidingSleutel"]
             [:duo:kenmerkwaardeTekst "1234-abcdef-98765432"]]
            [:duo:kenmerken
             [:duo:kenmerknaam "vorm"]
             [:duo:kenmerkwaardeEnumeratiewaarde "deeltijd"]]
            [:duo:kenmerken
             [:duo:kenmerknaam "voertaal"]
             [:duo:kenmerkwaardeEnumeratiewaarde "fra"]]]
           (helper/->xml {:eigenAangebodenOpleidingSleutel "1234-abcdef-98765432"
                          :voertaal "fra"
                          :vorm "deeltijd"}
                         "aangebodenHOOpleiding"))))

  (testing "->xml with other enum kenmerken"
    (is (= [:duo:aangebodenHOOpleiding
            [:duo:kenmerken
             [:duo:kenmerknaam "vorm"]
             [:duo:kenmerkwaardeEnumeratiewaarde "full_time"]]]
           (helper/->xml {:vorm "full_time"}
                         "aangebodenHOOpleiding")))

    (is (= [:duo:hoOpleiding
            [:duo:kenmerken
             [:duo:kenmerknaam "soort"]
             [:duo:kenmerkwaardeEnumeratiewaarde "TOTALE_VERPLICHTE_KOSTEN"]]]
           (helper/->xml {:soort "TOTALE_VERPLICHTE_KOSTEN"}
                         "hoOpleiding")))))

(deftest kenmerken-test
  (testing "kenmerken function with different types"
    (testing "eigenAangebodenOpleidingSleutel uses kenmerkwaardeTekst"
      (let [result (helper/->xml {:eigenAangebodenOpleidingSleutel "test-string"} "aangebodenHOOpleiding")]
        (is (= [:duo:aangebodenHOOpleiding
                 [:duo:kenmerken
                   [:duo:kenmerknaam "eigenAangebodenOpleidingSleutel"]
                   [:duo:kenmerkwaardeTekst "test-string"]]] result))))

    (testing "voertaal uses array of kenmerkwaardeEnumeratiewaarde"
      (let [result (helper/->xml {:voertaal "en"} "aangebodenHOOpleiding")]
        (is (= [:duo:aangebodenHOOpleiding
                 [:duo:kenmerken [:duo:kenmerknaam "voertaal"] [:duo:kenmerkwaardeEnumeratiewaarde "en"]]]
               result))))

    (testing "voertaal uses array of kenmerkwaardeEnumeratiewaarde"
      (let [result (helper/->xml {:voertaal ["en" "nl"]} "aangebodenHOOpleiding")]
        (is (= [:duo:aangebodenHOOpleiding
                 [:duo:kenmerken [:duo:kenmerknaam "voertaal"] [:duo:kenmerkwaardeEnumeratiewaarde "en"]]
                 [:duo:kenmerken [:duo:kenmerknaam "voertaal"] [:duo:kenmerkwaardeEnumeratiewaarde "nl"]]] result)))))

    (testing "nil values are not included as kenmerken"
      (is (= [:duo:aangebodenHOOpleiding]
             (helper/->xml {:voertaal nil :eigenAangebodenOpleidingSleutel nil} "aangebodenHOOpleiding")))))

(deftest level-sector-mapping-test
  (testing "level-sector-mapping for undefined"
    (is (= "ONBEPAALD" (helper/level-sector-mapping "undefined" nil))))

  (testing "level-sector-mapping for NT2"
    (is (= "NT2-I" (helper/level-sector-mapping "nt2-1" nil)))
    (is (= "NT2-II" (helper/level-sector-mapping "nt2-2" nil))))

  (testing "level-sector-mapping for MBO"
    (is (= "MBO" (helper/level-sector-mapping "secondary vocational education" "secondary vocational education")))
    (is (= "MBO-1" (helper/level-sector-mapping "secondary vocational education 1" "secondary vocational education")))
    (is (= "MBO-4" (helper/level-sector-mapping "secondary vocational education 4" "secondary vocational education"))))

  (testing "level-sector-mapping for HBO"
    (is (= "HBO-AD" (helper/level-sector-mapping "associate degree" "higher professional education")))
    (is (= "HBO-BA" (helper/level-sector-mapping "bachelor" "higher professional education")))
    (is (= "HBO-MA" (helper/level-sector-mapping "master" "higher professional education"))))

  (testing "level-sector-mapping for WO"
    (is (= "WO-BA" (helper/level-sector-mapping "bachelor" "university education")))
    (is (= "WO-MA" (helper/level-sector-mapping "master" "university education")))
    (is (= "WO-PM" (helper/level-sector-mapping "doctoral" "university education"))))

  (testing "level-sector-mapping with invalid combinations"
    (is (nil? (helper/level-sector-mapping "invalid" "secondary vocational education")))
    (is (nil? (helper/level-sector-mapping "bachelor" "invalid sector")))))

(deftest narrow-isced-test
  (testing "narrow-isced with detailed fields"
    (is (= "012" (helper/narrow-isced "0123")))
    (is (= "021" (helper/narrow-isced "0214")))
    (is (= "999" (helper/narrow-isced "9999")))))
