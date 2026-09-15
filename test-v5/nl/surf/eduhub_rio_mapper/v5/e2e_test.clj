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

(ns nl.surf.eduhub-rio-mapper.v5.e2e-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote-helper :refer [remote-entities-fixture]]
            [nl.surf.eduhub-rio-mapper.v5.e2e-helper :refer :all])
  (:import [java.util UUID]))

(use-fixtures :once with-running-mapper remote-entities-fixture)

(defn update-in-remote-entity [ooapi-type fixture-name f]
  (let [cfg (remote-helper/config)
        info (remote-helper/swift-auth-info cfg)
        path (str (name ooapi-type) "/" (ooapi-id ooapi-type fixture-name))
        container-name (:container-name cfg)
        entity (remote-helper/os-get-object info container-name {:path path})
        updated-entity (f entity)
        body (json/write-str updated-entity)]
    (remote-helper/os-put-object info container-name {:path path, :body body})))

(defn cleanup-entities!
  "Delete disposable entities in dependency order, even after partial failures.
  Restore their session key first if a test failed between unlink and relink."
  [entities]
  (doseq [[type fixture rio-type known-code] entities]
    (try
      (let [id (str (ooapi-id type fixture))
            code (or (rio-resolve rio-type id) known-code)]
        (when code
          (is (job-done? (post-job :link code type (ooapi-id type fixture))))
          (is (job-done? (post-job :delete type fixture)))
          (is (nil? (rio-resolve rio-type id)))))
      (catch Exception ex
        (is false (str "Cleanup failed for " fixture ": " (.getMessage ex)))))))

(def ^:dynamic rio-code nil)
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

(deftest ^:v5-e2e try-to-create-a-program-with-invalid-data
  (try
    (when (is (job-done? (post-job :upsert :education-specifications "invalid-data-parent")))
      (testing "scenario [6a]: Reject an invalid onderwijsaanbieder during OOAPI validation."
        (let [job (post-job :upsert :programs "bad-edu-offerer")]
          (is (job-error? job))
          (is (= "fetching-ooapi" (job-result job :phase)))
          (is (str/includes? (or (job-result job :message) "")
                             "The `educationOffererCode` attribute of the program's rio consumer does not conform to the required format."))))

      (testing "scenario [6b]: Reject an invalid onderwijslocatie during XML schema validation."
        (let [job (post-job :upsert :programs "bad-edu-location")
              message (or (job-result job :message) "")]
          (is (job-error? job))
          (is (= "upserting" (job-result job :phase)))
          (is (str/starts-with? message "XSD validation error in document:"))
          (is (str/includes? message "OnderwijslocatieID-v01")))))
    (finally
      (testing "Clean up the invalid-data test's parent education specification."
        (is (job-done? (post-job :delete :education-specifications "invalid-data-parent")))
        (is (nil? (rio-resolve :oe (str (ooapi-id :education-specifications "invalid-data-parent")))))))))

(deftest ^:v5-e2e try-to-create-edspecs-with-invalid-data
  (testing "scenario [3a]: Test /job/upsert/<invalid type> to see how the rio mapper reacts on an invalid api call. You can expect a 404 response."
    (is (= http-status/not-found (:status (post-job :upsert "not-a-valid-type" (UUID/randomUUID))))))

  (testing "scenario [3b]: Test /job/upsert with an edspec parent with an invalid educationSpecificationType attribute. You can expect 'error'."
    (let [job (post-job :upsert :education-specifications "bad-type")]
      (and
       (is (job-error? job))
       (is (= "fetching-ooapi" (job-result job :phase)))))))

(deftest ^:v5-e2e test-program-without-eduspecs
  (binding [rio-code (str (ooapi-id :education-specifications "dorothy"))]
    (testing "scenario [4b]: Test /job/upsert with the program. You can expect a new aangeboden opleiding. This aangeboden opleiding includes a periode and a cohort. (you can repeat this to test an update of the same data.)"
      (and
       (is (some? rio-code))
       (is (nil? (rio-resolve :oe rio-code)))
       (let [job (post-job :upsert :programs "jack")]
         (and
          (is (job-error? job))
          (is (str/starts-with? (job-result job :message)
                                "No 'opleidingseenheid' found in RIO with eigensleutel:"))))))))

(deftest ^:v5-e2e test-upsert-eduspec-dry-run
  (testing "scenario [1a]: Test /job/dry-run to see the difference between the edspec parent in OOAPI en de opleidingeenheid in RIO. You can expect RIO to be empty, when you start fresh."
    (let [job (post-job :dry-run/upsert :education-specifications "orphan-eduspec")]
      (and
       (is (job-done? job))
       (is (job-dry-run-not-found? job))))))

(deftest ^:v5-e2e test-program-with-eduspecs
  ;; insert eduspec "parent-program"
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
    (set! last-job (post-job :upsert :education-specifications "parent-program"))
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
      (set! last-job (post-job :dry-run/upsert :education-specifications "parent-program"))
      (and
       (is (job-done? last-job))
       (is (job-dry-run-found? last-job))
       (is (job-without-diffs? last-job))))

      ;; insert eduspec "child-program"
    (testing "scenario [1c]: Test /job/upsert with the edspec child. You can expect 'done' and an independent private specification is inserted."
      (set! last-job (post-job :upsert :education-specifications "child-program"))
      (set! child-code (job-result-opleidingseenheidcode last-job))
      (and
       (is (job-done? last-job))
       (is (empty? (rio-relations child-code)))
       (is (= "child-program education specification"
              (get-in-xml (rio-opleidingseenheid child-code) ["particuliereOpleiding" "particuliereOpleidingPeriode" "naamLang"])))))

    (testing "Private entity updates preserve dates and converge to no differences"
      (update-in-remote-entity :education-specifications "parent-program"
                               #(assoc % :validFrom "2007-01-01"))
      (is (job-done? (post-job :upsert :education-specifications "parent-program")))
      (is (= "2007-01-01" (get-in-xml (rio-opleidingseenheid parent-code)
                                                 ["particuliereOpleiding" "begindatum"])))
      (is (empty? (rio-relations parent-code)))
      (is (job-without-diffs? (post-job :dry-run/upsert :education-specifications "parent-program")))
      (is (job-done? (post-job :upsert :education-specifications "bonusparent-program")))
      (let [code (rio-resolve :oe (str (ooapi-id :education-specifications "bonusparent-program")))]
        (is (= "" (get-in-xml (rio-opleidingseenheid code) ["particuliereOpleiding" "einddatum"])))
        (update-in-remote-entity :education-specifications "bonusparent-program" #(assoc % :validTo "2026-08-31"))
        (is (job-done? (post-job :upsert :education-specifications "bonusparent-program")))
        (is (= "2026-08-31" (get-in-xml (rio-opleidingseenheid code) ["particuliereOpleiding" "einddatum"])))
        (let [job (post-job :dry-run/upsert :education-specifications "bonusparent-program")]
          (is (job-done? job))
          (is (job-without-diffs? job)))))

      ;; link eduspec "parent-program" to new sleutel
    (testing "scenario [2a]: Test /job/link of the edspec parent and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."
      (set! original-rio-sleutel (eigen-opleidingseenheid-sleutel parent-code))
      (set! last-job (post-job :link parent-code :education-specifications generated-sleutel))
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
      (set! last-job (post-job :link child-code :education-specifications generated-sleutel))
      (is (job-error? last-job)))

      ;; unlink eduspec "parent-program"
    (testing "scenario [2d]: Test /job/unlink to reset the edspec parent to an empty 'eigen sleutel'."
      (set! last-job (post-job :unlink parent-code :education-specifications))
      (and
       (is (job-done? last-job))
       (is (nil? (eigen-opleidingseenheid-sleutel parent-code)))))

      ;; link eduspec "parent-program" to old sleutel
    (testing "scenario [2b]: Test /job/link to reset the edspec parent to the old 'eigen sleutel'."
      (set! last-job (post-job :link parent-code :education-specifications "parent-program"))
      (and
       (is (job-done? last-job))
       (is (job-has-diffs? last-job))
       (is (= original-rio-sleutel
              (get-in (job-result-attributes last-job) [:eigenOpleidingseenheidSleutel :new-id])))
       (is (= original-rio-sleutel
              (eigen-opleidingseenheid-sleutel parent-code)))))

      ;; create a program (for the edSpec child)
    (testing "scenario [4a]: Test /job/dry-run to see the difference between the program in OOAPI en de aangeboden opleiding in RIO. You can expect RIO to be empty, when you start fresh."
      (set! last-job (post-job :dry-run/upsert :programs "some"))
      (and
       (is (job-done? last-job))
       (is (job-dry-run-not-found? last-job))))

    (testing "scenario [4c]: Test /job/delete with the program. You can expect an error, because the program is not upserted yet."
      (set! last-job (post-job :delete :programs "some"))
      (is (job-error? last-job)))

      ;; insert program "some", belonging to eduspec "parent-program"
    (testing "scenario [4b]: Test /job/upsert with the program. You can expect a new aangeboden opleiding. This aangeboden opleiding includes a periode and a cohort. (you can repeat this to test an update of the same data.)"
      (set! last-job (post-job :upsert :programs "some"))
      (is (job-done? last-job))
      (set! program-code (job-result-aangebodenopleidingcode last-job))
      (and
       (is (job-done? last-job))
       (is program-code)
       (is (= (str (ooapi-id :programs "some"))
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
      (set! last-job (post-job :dry-run/upsert :programs "some"))
      (and
       (is (job-done? last-job))
       (is (job-dry-run-found? last-job))
       (is (job-without-diffs? last-job))))

    ;; link program "some" to new sleutel. For program and courses, usually aangeboden-opleiding-code == sleutel

    (set! program-id (str (ooapi-id :programs "some")))
    (set! generated-sleutel (UUID/randomUUID))
    (set! original-rio-sleutel (eigen-aangeboden-opleiding-sleutel program-id))
    (testing "scenario [5a]: Test /job/link of the program and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."
      (set! last-job (post-job :link program-id :programs generated-sleutel))
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
      (set! last-job (post-job :link program-id :programs generated-sleutel))
      (and
       (is (job-done? last-job))
       (is (job-without-diffs? last-job))))

    ;; unlink program "some"
    (testing "scenario [5d]: Test /job/unlink to reset the program to an empty 'eigen sleutel'."
      (set! last-job (post-job :unlink program-id :programs generated-sleutel))
      (and
       (is (job-done? last-job))
       (is (nil? (eigen-aangeboden-opleiding-sleutel program-id)))))

    ;; link program "some" to old sleutel
    (testing "scenario [5b]: Test /job/link to reset the program to the old 'eigen sleutel'."
      (set! last-job (post-job :link program-id :programs (ooapi-id :programs "some")))
      (and
       (is (job-done? last-job))
       (is (job-has-diffs? last-job))
       (is (= program-id
              (get-in (job-result-attributes last-job) [:eigenAangebodenOpleidingSleutel :new-id])))
       (is (= program-id
              (eigen-aangeboden-opleiding-sleutel program-id)))))

    (finally
      (cleanup-entities! [[:programs "some" :ao program-code]
                          [:education-specifications "child-program" :oe child-code]
                          [:education-specifications "parent-program" :oe parent-code]
                          [:education-specifications "bonusparent-program" :oe nil]])
      (update-in-remote-entity :education-specifications "parent-program" #(assoc % :validFrom "1950-09-20"))
      (update-in-remote-entity :education-specifications "bonusparent-program" #(dissoc % :validTo))))))

(deftest ^:v5-e2e test-insert-variant-eduspecs
  (let [job (post-job :upsert :education-specifications "missing-parent-variant")]
    (is (job-error? job))
    (is (= (str "No 'opleidingseenheid' found in RIO for the parent of this variant with eigensleutel: "
                (ooapi-id :education-specifications "missing-parent"))
           (job-result job :message)))))

(def ^:dynamic course-id nil)

(deftest ^:v5-e2e test-course-with-eduspecs
  (binding [last-job nil
            course-id nil
            generated-sleutel nil
            parent-code nil
            last-xml nil
            original-rio-sleutel nil]

    (try
    (set! last-job (post-job :upsert :education-specifications "parent-course"))
    ;; insert eduspec called "parent-course"
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
                          [:education-specifications "parent-course" :oe parent-code]])))))

(defn- set-education-unit-code-in-consumer [consumer unit-code]
  (if (not= "rio" (:consumerKey consumer))
    consumer
    (assoc consumer :educationUnitCode unit-code)))

(defn- set-education-unit-code-in-rio-consumer [consumers unit-code]
  (mapv
   #(set-education-unit-code-in-consumer % unit-code)
   consumers))

(deftest ^:v5-e2e test-update-remote-entities
  ;; insert eduspec "remote-update"
  (binding [parent-code "2345O5432"
            last-xml nil
            original-rio-sleutel nil]
    (update-in-remote-entity :programs "remote-update"
                             #(update % :consumers set-education-unit-code-in-rio-consumer parent-code))
    (update-in-remote-entity :programs "remote-update" #(dissoc % :educationSpecification))

    (let [cfg (remote-helper/config)
          info (remote-helper/swift-auth-info cfg)
          path (str "programs/" (ooapi-id :programs "remote-update"))
          container-name (:container-name cfg)
          updated-program (remote-helper/os-get-object  info container-name {:path path})]
      (is (= parent-code (get-in updated-program [:consumers 1 :educationUnitCode]))))))
