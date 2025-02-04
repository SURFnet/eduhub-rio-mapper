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

(ns nl.surf.eduhub-rio-mapper.e2e-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.e2e-helper :refer :all]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :refer [remote-entities-fixture]])
  (:import [java.util UUID]))

(use-fixtures :once with-running-mapper remote-entities-fixture)

(deftest ^:e2e try-to-create-a-program-with-invalid-data
  (testing "scenario [6a]: Test /job/upsert with a program with an invalid onderwijsaanbieder attribute. You can expect 'error'."
    (is (job-error? (post-job :upsert :programs "bad-edu-offerer"))))

  (testing "scenario [6b]: Test /job/upsert with a program with an invalid onderwijslocatie attribute. You can expect 'error'."
    (is (job-error? (post-job :upsert :programs "bad-edu-location")))))

(deftest ^:e2e try-to-create-edspecs-with-invalid-data
  (testing "scenario [3a]: Test /job/upsert/<invalid type> to see how the rio mapper reacts on an invalid api call. You can expect a 404 response."
    (is (= http-status/not-found (:status (post-job :upsert "not-a-valid-type" (UUID/randomUUID))))))

  (testing "scenario [3b]: Test /job/upsert with an edspec parent with an invalid type attribute. You can expect 'error'."
    (let [job (post-job :upsert :education-specifications "bad-type")]
      (and
        (is (job-error? job))
        (is (= "fetching-ooapi" (job-result job :phase)))))))

(deftest ^:e2e test-program-without-eduspecs
  (testing "scenario [4b]: Test /job/upsert with the program. You can expect a new aangeboden opleiding. This aangeboden opleiding includes a periode and a cohort. (you can repeat this to test an update of the same data.)"
    (let [job (post-job :upsert :programs "some")]
      (and
        (is (job-error? job))
        (is (str/starts-with? (job-result job :message)
                              "No 'opleidingseenheid' found in RIO with eigensleutel:"))))))

(deftest ^:e2e test-upsert-eduspec-dry-run
  (testing "scenario [1a]: Test /job/dry-run to see the difference between the edspec parent in OOAPI en de opleidingeenheid in RIO. You can expect RIO to be empty, when you start fresh."
    (let [job (post-job :dry-run/upsert :education-specifications "parent-program")]
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

(deftest ^:e2e test-program-with-eduspecs
  ;; insert eduspec "parent-program"
  (binding [last-job (post-job :upsert :education-specifications "parent-program")
            parent-code nil
            child-code nil
            generated-sleutel nil
            program-id nil
            program-code nil
            original-rio-sleutel nil
            last-xml nil]

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
                 (get-in-xml last-xml ["hoOpleiding" "begindatum"])))
          (is (= "2060-08-28"
                 (get-in-xml last-xml ["hoOpleiding" "einddatum"])))
          (is (= "HBO-BA"
                 (get-in-xml last-xml ["hoOpleiding" "niveau"])))
          (is (= "1T"
                 (get-in-xml last-xml ["hoOpleiding" "hoOpleidingPeriode" "naamKort"])))
          (is (= "parent-program education specification"
                 (get-in-xml last-xml ["hoOpleiding" "hoOpleidingPeriode" "naamLang"])))
          (is (= "93"
                 (get-in-xml last-xml ["hoOpleiding" "hoOpleidingPeriode" "studielast"])))
          (is (= "SBU"
                 (get-in-xml last-xml ["hoOpleiding" "hoOpleidingPeriode" "studielasteenheid"])))))

      (testing "scenario [1a]: Test /job/dry-run to see the difference between the edspec parent in OOAPI en de opleidingeenheid in RIO. You can expect them to be the same."
        (set! last-job (post-job :dry-run/upsert :education-specifications "parent-program"))
        (and
          (is (job-done? last-job))
          (is (job-dry-run-found? last-job))
          (is (job-without-diffs? last-job))))

      ;; insert eduspec "child-program"
      (testing "scenario [1c]: Test /job/upsert with the edspec child. You can expect 'done' and a variant in RIO is inserted met een relatie met de parent."
        (set! last-job (post-job :upsert :education-specifications "child-program"))
        (set! child-code (job-result-opleidingseenheidcode last-job))
        (set! generated-sleutel (UUID/randomUUID))
        (and
          (is (job-done? last-job))
          (is (rio-with-relation? parent-code child-code))
          (is (= "child-program education specification"
                 (get-in-xml (rio-opleidingseenheid child-code) ["hoOpleiding" "hoOpleidingPeriode" "naamLang"])))))

      ;; link eduspec "parent-program" to new sleutel
      (testing "scenario [2a]: Test /job/link of the edspec parent and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."
        (set! last-job (post-job :link parent-code :education-specifications generated-sleutel))
        (and
          (is (job-done? last-job))
          (is (= (str generated-sleutel)
                 (get-in (job-result last-job) [:attributes :eigenOpleidingseenheidSleutel :new-id])))
          (is (job-has-diffs? last-job))
          (is (= (str generated-sleutel)
                 (eigen-opleidingseenheid-sleutel parent-code)))))

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
        (set! program-code (job-result-aangebodenopleidingcode last-job))
        (and
          (is (job-done? last-job))
          (is program-code)
          (is (= (str (ooapi-id :programs "some"))
                 program-code)
              "aangebodenopleidingcode is the same as the OOAPI id")
          (set! last-xml (rio-aangebodenopleiding program-code))
          (is (= "2008-10-18"
                 (get-in-xml last-xml ["aangebodenHOOpleiding" "aangebodenHOOpleidingPeriode" "begindatum"])))
          (is (= ["1234asd12" "1234poi12" "1234qwe12"]
                 (sort
                   (get-all-in-xml last-xml ["aangebodenHOOpleiding" "aangebodenHOOpleidingCohort" "cohortcode"]))))))

      (testing "scenario [4a]: Test /job/dry-run to see the difference between the program in OOAPI en de opleidingeenheid in RIO. You can expect them to be the same."
        (set! last-job (post-job :dry-run/upsert :programs "some"))
        (and
          (is (job-done? last-job))
          (is (job-dry-run-found? last-job))
          (is (job-without-diffs? last-job))))

      ;; link program "some" to new sleutel. For program and courses, usually aangeboden-opleiding-code == sleutel

      (set! program-id (str (ooapi-id :programs "some")))
      (set! generated-sleutel (UUID/randomUUID))
      (testing "scenario [5a]: Test /job/link of the program and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."
        (set! last-job (post-job :link program-id :programs generated-sleutel))
        (and
          (is (job-done? last-job))
          (is (job-has-diffs? last-job))
          (is (= (str generated-sleutel)
                 (get-in (job-result-attributes last-job) [:eigenAangebodenOpleidingSleutel :new-id])))
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

      (testing "scenario [1e] Delete child eduspec."
        (set! last-job (post-job :delete :education-specifications "child-program"))
        (and
          (is (job-done? last-job))
          (is (nil? (rio-resolve "education-specification" child-code))))))))

(def ^:dynamic course-id nil)

(deftest ^:e2e test-course-with-eduspecs
  (binding [last-job (post-job :upsert :education-specifications "parent-course")
            course-id nil
            generated-sleutel nil
            parent-code nil
            last-xml nil]

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
      ;; link course "some" to new sleutel. For program and courses, usually aangeboden-opleiding-code == sleutel
      (testing "scenario [8a]: Test /job/link of the course and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."
        (set! last-job (post-job :link course-id :courses generated-sleutel))
        (and
          (is (job-done? last-job))
          (is (job-has-diffs? last-job))
          (is (= (str generated-sleutel)
                 (eigen-aangeboden-opleiding-sleutel course-id)))))

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
          (is (nil? (rio-resolve "course" course-id))))))))

(deftest ^:e2e test-accredited-program
  (binding [generated-sleutel (UUID/randomUUID)
            parent-code       "1001O5220"
            variant-code      nil
            last-job          nil]

    (testing "scenario [9a]: Link to accredited program. You can expect the eigenSleutel field to be set in RIO.
              scenario [9b]: Upsert accredited program. Not much should be changed in RIO, because it is an accredited program, where we're not allowed to change a lot. Link uses upsert"
      (set! last-job (post-job :link parent-code :education-specifications generated-sleutel))
      (and
        (is (job-done? last-job))
        (is (= (str generated-sleutel)
               (get-in (job-result last-job) [:attributes :eigenOpleidingseenheidSleutel :new-id])))
        (is (job-has-diffs? last-job))
        (is (= (str generated-sleutel)
               (eigen-opleidingseenheid-sleutel parent-code)))))

    (testing "scenario [9e]: Unlink from accredited program > done"
      (set! last-job (post-job :unlink parent-code :education-specifications))
      (and
        (is (job-done? last-job))
        (is (nil? (eigen-opleidingseenheid-sleutel parent-code)))))

    (testing "scenario [9c]: Upsert variant > done. The new variant should be added and have a relation to the accredited program."
      ;; insert eduspec with type "variant", then create relation. Delete after use
      (and
        (set! last-job (post-job :upsert :education-specifications "accredited-variant"))
        (set! variant-code (job-result-opleidingseenheidcode last-job))
        (is (rio-with-relation? parent-code variant-code))
        (set! last-job (post-job :delete :education-specifications "accredited-variant"))
        (is (nil? (rio-resolve "education-specification" parent-code)))))))
