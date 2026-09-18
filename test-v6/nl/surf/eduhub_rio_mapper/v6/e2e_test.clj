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

(ns nl.surf.eduhub-rio-mapper.v6.e2e-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote-helper :refer [remote-entities-fixture]]
            [nl.surf.eduhub-rio-mapper.v6.e2e-helper :refer :all])
  (:import [java.util UUID]))

(use-fixtures :once with-running-mapper remote-entities-fixture)

(defn update-in-remote-entity [ooapi-type fixture-name f]
  {:pre [(not= "education-specifications" ooapi-type)
         (#{:programmes :courses} ooapi-type)]}
  (let [cfg (remote-helper/config)
        info (remote-helper/swift-auth-info cfg)
        path (str (name ooapi-type) "/" (ooapi-id ooapi-type fixture-name))
        container-name (:container-name cfg)
        entity (remote-helper/os-get-object info container-name {:path path})
        updated-entity (f entity)
        body (json/write-str updated-entity)]
    (remote-helper/os-put-object info container-name {:path path, :body body})))

(defn rio-object-exists?
  "Return whether a RIO object still exists under its literal RIO code."
  [rio-type code]
  (when code
    (= code
       (case rio-type
         :ao (get-in-xml (rio-aangebodenopleiding code) ["aangebodenOpleidingCode"])
         :oe (get-in-xml (rio-opleidingseenheid code) ["opleidingseenheidcode"])))))

(defn cleanup-entities!
  "Delete disposable entities in dependency order, even after partial failures.
  Restore their session key first if a test failed between unlink and relink."
  [entities]
  (doseq [[type fixture rio-type known-code] entities]
    (try
      (let [id (str (ooapi-id type fixture))
            resolved-code (rio-resolve rio-type id)
            code (or resolved-code known-code)]
        ;; A failed resolve can mean either that the object was unlinked or that
        ;; it was already deleted. Only use the remembered code in the former case.
        (when (or resolved-code (rio-object-exists? rio-type code))
          (is (job-done? (post-job :link code type (ooapi-id type fixture))))
          (is (job-done? (post-job :delete type fixture)))
          (is (nil? (rio-resolve rio-type id)))))
      (catch Exception ex
        (is false (str "Cleanup failed for " fixture ": " (.getMessage ex)))))))

(deftest ^:v6-e2e try-to-create-a-program-with-invalid-data
  (try
    (when (is (job-done? (post-job :upsert :programmes "specification-invalid-data-parent")))
      (doseq [[fixture-name schema-type]
              [["bad-edu-offerer" "OnderwijsaanbiederID-v01"]
               ["bad-edu-location" "OnderwijslocatieID-v01"]]]
        (testing (str "Reject " fixture-name " during XML schema validation.")
          (let [job (post-job :upsert :programmes fixture-name)
                message (or (job-result job :message) "")]
            (is (job-error? job))
            (is (= "upserting" (job-result job :phase)))
            (is (str/starts-with? message "XSD validation error in document:"))
            (is (str/includes? message schema-type))))))
    (finally
      (testing "Clean up the invalid-data test's parent specification."
        (is (job-done? (post-job :delete :programmes "specification-invalid-data-parent")))
        (is (nil? (rio-resolve :oe (str (ooapi-id :programmes "specification-invalid-data-parent")))))))))

(deftest ^:v6-e2e try-to-create-edspecs-with-invalid-data
  (testing "scenario [3a]: Test /job/upsert/<invalid type> to see how the rio mapper reacts on an invalid api call. You can expect a 404 response."
    (is (= http-status/not-found (:status (post-job :upsert "not-a-valid-type" (UUID/randomUUID))))))

  (testing "scenario [3b]: Test /job/upsert with an edspec parent with an invalid type attribute. You can expect 'error'."
    (let [job (post-job :upsert :programmes "specification-bad-type")]
      (and
       (is (job-error? job))
       (is (= "fetching-ooapi" (job-result job :phase)))))))

(deftest ^:v6-e2e test-program-without-prgspecs
  (testing "scenario [4b]: Test /job/upsert with the program. You can expect a new aangeboden opleiding. This aangeboden opleiding includes a periode and a cohort. (you can repeat this to test an update of the same data.)"
    (and
     (is (nil? (rio-resolve :oe (str (ooapi-id :programmes "specification-dorothy")))))
     (let [job (post-job :upsert :programmes "jack")]
       (and
        (is (job-error? job))
        (is (str/starts-with? (job-result job :message)
                              "No 'opleidingseenheid' found in RIO with eigensleutel:")))))))

(deftest ^:v6-e2e test-upsert-prgspec-dry-run
  (testing "scenario [1a]: Test /job/dry-run to see the difference between the edspec parent in OOAPI en de opleidingeenheid in RIO. You can expect RIO to be empty, when you start fresh."
    (let [job (post-job :dry-run/upsert :programmes "specification-orphan-prgspec")]
      (and
       (is (job-done? job))
       (is (job-dry-run-not-found? job))))))

(def ^:dynamic parent-code nil)
(def ^:dynamic last-job nil)
(def ^:dynamic child-code nil)
(def ^:dynamic variant-code nil)
(def ^:dynamic generated-sleutel nil)
(def ^:dynamic program-id nil)
(def ^:dynamic program-code nil)
(def ^:dynamic original-rio-sleutel nil)
(def ^:dynamic last-xml nil)
(def ^:dynamic original-relations nil)
(def ^:dynamic updated-relations nil)
(def ^:dynamic bonus-parent-code nil)
(def ^:dynamic bonus-child-code nil)

(deftest ^:v6-e2e test-delete-programme-after-linking-new-sleutel
  (binding [last-job (post-job :upsert :programmes "specification-delete-after-relink")
            parent-code nil
            generated-sleutel (UUID/randomUUID)
            original-rio-sleutel nil]
    (and
     (is (job-done? last-job))
     (set! parent-code (job-result-opleidingseenheidcode last-job))
     (is parent-code)
     (set! original-rio-sleutel (eigen-opleidingseenheid-sleutel parent-code))
     (is (string? original-rio-sleutel))

     (set! last-job (post-job :link parent-code :programmes generated-sleutel))
     (is (job-done? last-job))
     (is (some? (rio-resolve :oe (str generated-sleutel))))

     ;; In ooapi, there is no programme with key generated-sleutel, so we don't know its rio-type.
     ;; We try to resolve both oe and ao, and then try to delete the first match.
     (set! last-job (post-job :delete :programmes generated-sleutel))
     (is (job-done? last-job))
     (is (nil? (rio-resolve :oe (str generated-sleutel)))))))

(deftest ^:v6-e2e test-program-with-prgspecs
  ;; insert prgspec "parent-program"
  (binding [last-job nil
            parent-code nil
            child-code nil
            generated-sleutel (UUID/randomUUID)
            program-id nil
            program-code nil
            original-rio-sleutel nil
            last-xml nil
            original-relations nil
            updated-relations nil
            bonus-parent-code nil
            bonus-child-code nil]

    (try
    (set! last-job (post-job :upsert :programmes "specification-parent-program"))
    (set! parent-code (job-result-opleidingseenheidcode last-job))

    (and
     (is last-job)
     (is (job-done? last-job))
     (set! original-rio-sleutel (eigen-opleidingseenheid-sleutel parent-code))
     (testing "scenario [1b]: Test /job/upsert with the education specification. You can expect 'done' and a opleidingeenheid in RIO is inserted."
       (and
        (is parent-code)
        (set! last-xml (rio-opleidingseenheid parent-code))
        (is (= "1950-09-20"
               (get-in-xml last-xml ["particuliereOpleiding" "begindatum"])))
        (is (= "2060-08-28"
               (get-in-xml last-xml ["particuliereOpleiding" "einddatum"])))
        (is (= "HBO-BA"
               (get-in-xml last-xml ["particuliereOpleiding" "niveau"])))
        (is (= "1T"
               (get-in-xml last-xml ["particuliereOpleiding" "particuliereOpleidingPeriode" "naamKort"])))
        (is (= "parent-program education specification"
               (get-in-xml last-xml ["particuliereOpleiding" "particuliereOpleidingPeriode" "naamLang"])))
        (is (= "93"
               (get-in-xml last-xml ["particuliereOpleiding" "particuliereOpleidingPeriode" "studielast"])))
        (is (= "SBU"
               (get-in-xml last-xml ["particuliereOpleiding" "particuliereOpleidingPeriode" "studielasteenheid"]))))))

    (testing "scenario [1a]: Test /job/dry-run to see the difference between the edspec parent in OOAPI en de opleidingeenheid in RIO. You can expect them to be the same."
      (set! last-job (post-job :dry-run/upsert :programmes "specification-parent-program"))
      (and
       (is (job-done? last-job))
       (is (job-dry-run-found? last-job))
       (is (job-without-diffs? last-job))))

      ;; insert prgspec "child-program"
    (testing "scenario [1c]: Test /job/upsert with the edspec child. You can expect 'done' and an independent private specification is inserted."
      (set! last-job (post-job :upsert :programmes "specification-child-program"))
      (set! child-code (job-result-opleidingseenheidcode last-job))
      (and
       (is (job-done? last-job))
       (is (empty? (rio-relations child-code)))
       (is (= "child-program education specification"
              (get-in-xml (rio-opleidingseenheid child-code) ["particuliereOpleiding" "particuliereOpleidingPeriode" "naamLang"])))))

    (testing "Private entity updates preserve dates and converge to no differences"
      (update-in-remote-entity :programmes "specification-parent-program"
                               #(assoc % :validFrom "2017-01-01"))
      (is (job-done? (post-job :upsert :programmes "specification-parent-program")))
      (is (= "2017-01-01" (get-in-xml (rio-opleidingseenheid parent-code)
                                                 ["particuliereOpleiding" "begindatum"])))
      (is (empty? (rio-relations parent-code)))
      (is (job-without-diffs? (post-job :dry-run/upsert :programmes "specification-parent-program")))
      (is (job-done? (post-job :upsert :programmes "specification-bonusparent-program")))
      (let [code (rio-resolve :oe (str (ooapi-id :programmes "specification-bonusparent-program")))]
        (is (= "" (get-in-xml (rio-opleidingseenheid code) ["particuliereOpleiding" "einddatum"])))
        (update-in-remote-entity :programmes "specification-bonusparent-program" #(assoc % :validTo "2026-08-31"))
        (is (job-done? (post-job :upsert :programmes "specification-bonusparent-program")))
        (is (= "2026-08-31" (get-in-xml (rio-opleidingseenheid code) ["particuliereOpleiding" "einddatum"])))
        (let [job (post-job :dry-run/upsert :programmes "specification-bonusparent-program")]
          (is (job-done? job))
          (is (job-without-diffs? job)))))

      ;; link prgspec "parent-program" to new sleutel
    (testing "scenario [2a]: Test /job/link of the edspec parent and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."
      (set! original-rio-sleutel (eigen-opleidingseenheid-sleutel parent-code))
      (set! last-job (post-job :link parent-code :programmes generated-sleutel))
      (and
       (is (job-done? last-job))
       (is (= (str generated-sleutel)
              (get-in (job-result last-job) [:attributes :eigenOpleidingseenheidSleutel :new-id])))
       (is (job-has-diffs? last-job))
       (is (string? original-rio-sleutel))
       (is (not= (str generated-sleutel)
                 original-rio-sleutel))
       (is (= (str generated-sleutel)
              (eigen-opleidingseenheid-sleutel parent-code))
           (str "generated sleutel (" generated-sleutel ") original sleutel (" original-rio-sleutel ")"))))

    (testing "(you can repeat this to expect an error because the new 'eigen sleutel' already exists.)"
      (set! last-job (post-job :link child-code :programmes generated-sleutel))
      (is (job-error? last-job)))

      ;; unlink prgspec "parent-program"
    (testing "scenario [2d]: Test /job/unlink to reset the edspec parent to an empty 'eigen sleutel'."
      (set! last-job (post-job :unlink parent-code :programmes))
      (and
       (is (job-done? last-job))
       (is (nil? (eigen-opleidingseenheid-sleutel parent-code)))))

      ;; link prgspec "parent-program" to old sleutel
    (testing "scenario [2b]: Test /job/link to reset the edspec parent to the old 'eigen sleutel'."
      (set! last-job (post-job :link parent-code :programmes "specification-parent-program"))
      (and
       (is (job-done? last-job))
       (is (job-has-diffs? last-job))
       (is (= original-rio-sleutel
              (get-in (job-result-attributes last-job) [:eigenOpleidingseenheidSleutel :new-id])))
       (is (= original-rio-sleutel
              (eigen-opleidingseenheid-sleutel parent-code)))))

      ;; create a program (for the edSpec child)
    (testing "scenario [4a]: Test /job/dry-run to see the difference between the program in OOAPI en de aangeboden opleiding in RIO. You can expect RIO to be empty, when you start fresh."
      (set! last-job (post-job :dry-run/upsert :programmes "some"))
      (and
       (is (job-done? last-job))
       (is (job-dry-run-not-found? last-job))))

    (testing "scenario [4c]: Test /job/delete with the program. You can expect an error, because the program is not upserted yet."
      (set! last-job (post-job :delete :programmes "some"))
      (is (job-error? last-job)))

      ;; insert program "some", belonging to prgspec "parent-program"
    (testing "scenario [4b]: Test /job/upsert with the program. You can expect a new aangeboden opleiding. This aangeboden opleiding includes a periode and a cohort. (you can repeat this to test an update of the same data.)"
      (set! last-job (post-job :upsert :programmes "some"))
      (is (job-done? last-job))
      (set! program-code (job-result-aangebodenopleidingcode last-job))
      (and
       (is (job-done? last-job))
       (is program-code)
       (is (= (str (ooapi-id :programmes "some"))
              program-code)
           "aangebodenopleidingcode is the same as the OOAPI id")
       (set! last-xml (rio-aangebodenopleiding program-code))
       (is (= #{"FRA" "DEU"}
              (set (kenmerken-values-aangeboden-opleiding last-xml "voertaal" :kenmerkwaardeEnumeratiewaarde))))
       (is (= "2008-10-18"
              (get-in-xml last-xml ["aangebodenParticuliereOpleiding" "aangebodenParticuliereOpleidingPeriode" "begindatum"])))
       (is (= "2022-08-24"
           (first (kenmerken-values-aangeboden-opleiding last-xml "laatsteInstroomdatum" :kenmerkwaardeDatum))))
       (is (= ["1234asd12" "1234poi12" "1234qwe12"]
              (sort
               (get-all-in-xml last-xml ["aangebodenParticuliereOpleiding" "aangebodenParticuliereOpleidingCohort" "cohortcode"]))))))

    (testing "scenario [4a]: Test /job/dry-run to see the difference between the program in OOAPI en de opleidingeenheid in RIO. You can expect them to be the same."
      (set! last-job (post-job :dry-run/upsert :programmes "some"))
      (and
       (is (job-done? last-job))
       (is (job-dry-run-found? last-job))
       (is (job-without-diffs? last-job))))

    ;; link program "some" to new sleutel. For program and courses, usually aangeboden-opleiding-code == sleutel

    (set! program-id (str (ooapi-id :programmes "some")))
    (set! generated-sleutel (UUID/randomUUID))
    (set! original-rio-sleutel (eigen-aangeboden-opleiding-sleutel program-id))
    (testing "scenario [5a]: Test /job/link of the program and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."
      (set! last-job (post-job :link program-id :programmes generated-sleutel))
      (and
       (is (job-done? last-job))
       (is (job-has-diffs? last-job))
       (is (= (str generated-sleutel)
              (get-in (job-result-attributes last-job) [:eigenAangebodenOpleidingSleutel :new-id])))
       (is (string? original-rio-sleutel))
       (is (not= (str generated-sleutel)
                 original-rio-sleutel))
       (is (= (str generated-sleutel)
              (eigen-aangeboden-opleiding-sleutel program-id)))))

    (testing "(you can repeat this to expect an error because the new 'eigen sleutel' already exists.)"
      (set! last-job (post-job :link program-id :programmes generated-sleutel))
      (and
       (is (job-done? last-job))
       (is (job-without-diffs? last-job))))

    ;; unlink program "some"
    (testing "scenario [5d]: Test /job/unlink to reset the program to an empty 'eigen sleutel'."
      (set! last-job (post-job :unlink program-id :programmes generated-sleutel))
      (and
       (is (job-done? last-job))
       (is (nil? (eigen-aangeboden-opleiding-sleutel program-id)))))

    ;; link program "some" to old sleutel
    (testing "scenario [5b]: Test /job/link to reset the program to the old 'eigen sleutel'."
      (set! last-job (post-job :link program-id :programmes (ooapi-id :programmes "some")))
      (and
       (is (job-done? last-job))
       (is (job-has-diffs? last-job))
       (is (= program-id
              (get-in (job-result-attributes last-job) [:eigenAangebodenOpleidingSleutel :new-id])))
       (is (= program-id
              (eigen-aangeboden-opleiding-sleutel program-id)))))

    (finally
      (cleanup-entities! [[:programmes "some" :ao program-code]
                          [:programmes "specification-child-program" :oe child-code]
                          [:programmes "specification-parent-program" :oe parent-code]
                          [:programmes "specification-bonusparent-program" :oe nil]])
      (update-in-remote-entity :programmes "specification-parent-program" #(assoc % :validFrom "1950-09-20"))
      (update-in-remote-entity :programmes "specification-bonusparent-program" #(dissoc % :validTo))))))

(deftest ^:v6-e2e test-insert-variant-eduspecs
  (let [job (post-job :upsert :programmes "variant-specification-missing-parent")]
    (is (job-error? job))
    (is (= (str "No 'opleidingseenheid' found in RIO for the parent of this variant with eigensleutel: "
                (ooapi-id :programmes "specification-missing-parent"))
           (job-result job :message)))))

(def ^:dynamic course-id nil)

(deftest ^:v6-e2e test-course-with-prgspecs
  (binding [last-job nil
            course-id nil
            generated-sleutel nil
            parent-code nil
            last-xml nil
            original-rio-sleutel nil]

    (try
    (set! last-job (post-job :upsert :programmes "specification-parent-course"))
    ;; insert prgspec called "parent-course"
    (and
     (testing "scenario [7a]: Test /job/upsert with the edspec for a course. You can expect 'done'."
       (set! parent-code (job-result-opleidingseenheidcode last-job))
       (and
        (is (job-done? last-job))
        (is (some? parent-code))
          ;; make sure we see it after a read request as well
        (is (= parent-code
               (get-in-xml (rio-opleidingseenheid parent-code) ["hoOnderwijseenheid" "opleidingseenheidcode"])))))

     (testing "scenario [7c]: Test /job/dry-run to see the difference between the course in OOAPI en de aangeboden opleiding in RIO. You can expect RIO to be empty, when you start fresh."
       (set! last-job (post-job :dry-run/upsert :courses "some"))
       (and
        (is (job-done? last-job))
        (is (job-dry-run-not-found? last-job))))

     (testing "scenario [7e]: Test /job/delete with the course. You can expect an error, because the course is not upserted yet."
       (set! last-job (post-job :delete :courses "some"))
       (is (job-error? last-job)))

      ;; insert course "some"
     (testing "scenario [7d]: Test /job/upsert with the course. You can expect a new aangeboden opleiding. This aangeboden opleiding includes a periode and a cohort. (you can repeat this to test an update of the same data.)"
       (set! last-job (post-job :upsert :courses "some"))
       (set! course-id (job-result-aangebodenopleidingcode last-job))
       (and
        (is (job-done? last-job))
        (set! last-xml (rio-aangebodenopleiding course-id))
        (is (= parent-code
               (get-in-xml last-xml ["opleidingseenheidcode"])))
        (is (= "1994-09-05"
               (get-in-xml last-xml ["aangebodenHOOpleidingsonderdeel" "eersteInstroomDatum"])))
        (is (= "2050-11-10"
               (get-in-xml last-xml ["aangebodenHOOpleidingsonderdeel" "einddatum"])))))

     (testing "scenario [7c]: Test /job/dry-run to see the difference between the course in OOAPI en de aangeboden opleiding in RIO. You can expect them to be the same."
       (set! last-job (post-job :dry-run/upsert :courses "some"))
       (and
        (is (job-done? last-job))
        (is (job-dry-run-found? last-job))
        (is (job-without-diffs? last-job))))

     (set! course-id (str (ooapi-id :courses "some")))
     (set! generated-sleutel (UUID/randomUUID))
     (set! original-rio-sleutel (eigen-aangeboden-opleiding-sleutel course-id))
      ;; link course "some" to new sleutel. For program and courses, usually aangeboden-opleiding-code == sleutel
     (testing "scenario [8a]: Test /job/link of the course and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."
       (set! last-job (post-job :link course-id :courses generated-sleutel))
       (and
        (is (job-done? last-job))
        (is (job-has-diffs? last-job))
        (is (not= (str generated-sleutel)
                  original-rio-sleutel))
        (is (= (str generated-sleutel)
               (eigen-aangeboden-opleiding-sleutel course-id))
            (str "generated sleutel (" generated-sleutel ") original sleutel (" original-rio-sleutel ")"))))

      ;; unlink course "some"
     (testing "scenario [8d]: Test /job/unlink to reset the course to an empty 'eigen sleutel'."
        ;; course-id is also the course-code
       (set! last-job (post-job :unlink course-id :courses generated-sleutel))
       (and
        (is (job-done? last-job))
        (is (nil? (eigen-aangeboden-opleiding-sleutel course-id)))))

      ;; link course "some" to old sleutel
     (testing "scenario [8b]: Test /job/link to reset the course to the old 'eigen sleutel'."
       (set! last-job (post-job :link course-id :courses (ooapi-id :courses "some")))
       (and
        (is (job-done? last-job))
        (is (job-has-diffs? last-job))
        (is (= course-id (get-in (job-result-attributes last-job) [:eigenAangebodenOpleidingSleutel :new-id])))
        (is (= course-id (eigen-aangeboden-opleiding-sleutel course-id)))))

     (testing "scenario [7e]: Test /job/delete with the course."
       (set! last-job (post-job :delete :courses "some"))
       (and
        (is (job-done? last-job))
        (is (nil? (rio-resolve :ao course-id))))))
    (finally
      (cleanup-entities! [[:courses "some" :ao course-id]
                          [:programmes "specification-parent-course" :oe parent-code]])))))

(deftest ^:v6-e2e test-update-remote-entities
  ;; insert prgspec "remote-update"
  (binding [parent-code "2345O5432"
            last-xml nil
            original-rio-sleutel nil]
    (update-in-remote-entity :programmes "remote-update"
                             #(assoc-in % [:consumer :educationUnitCode] parent-code))
    (update-in-remote-entity :programmes "remote-update" #(update % :consumer dissoc :specificationId))

    (let [cfg (remote-helper/config)
          info (remote-helper/swift-auth-info cfg)
          path (str "programmes/" (ooapi-id :programmes "remote-update"))
          container-name (:container-name cfg)
          updated-program (remote-helper/os-get-object  info container-name {:path path})]
      (is (= parent-code (get-in updated-program [:consumer :educationUnitCode]))))))
