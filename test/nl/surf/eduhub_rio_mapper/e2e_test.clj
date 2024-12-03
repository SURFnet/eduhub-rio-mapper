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
  (:require [clojure.test :refer :all]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.e2e-helper :refer :all]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :refer [remote-entities-fixture]])
  (:import (java.util UUID)))

(use-fixtures :once with-running-mapper remote-entities-fixture)

(deftest ^:e2e create-edspecs-and-program
  (testing "create edspecs"
    (testing "scenario [1a]: Test /job/dry-run to see the difference between the edspec parent in OOAPI en de opleidingeenheid in RIO. You can expect RIO to be empty, when you start fresh."
      (let [job (post-job :dry-run/upsert :education-specifications "parent-program")]
        (and
          (is (job-done? job))
          (is (job-dry-run-not-found? job)))))

    (testing "scenario [1b]: Test /job/upsert with the program. You can expect 'done' and a opleidingeenheid in RIO is inserted."
      (let [parent-job (post-job :upsert :education-specifications "parent-program")
            parent-code (job-result-opleidingseenheidcode parent-job)]
        (and
          (is (job-done? parent-job))
          (is (job-result-opleidingseenheidcode parent-job))
          (let [xml (rio-opleidingseenheid (job-result-opleidingseenheidcode parent-job))]
            (is (= "parent-program education specification"
                   (get-in-xml xml ["hoOpleiding" "hoOpleidingPeriode" "naamLang"])))))

        (testing "(you can repeat this to test an update of the same data.)"
          (let [job (post-job :upsert :education-specifications "parent-program")]
            (is (job-done? job))))

        (testing "scenario [1a]: Test /job/dry-run to see the difference between the edspec parent in OOAPI en de opleidingeenheid in RIO. You can expect them to be the same."
          (let [job (post-job :dry-run/upsert :education-specifications "parent-program")]
            (and
              (is (job-done? job))
              (is (job-dry-run-found? job))
              (is (job-without-diffs? job)))))

        (testing "scenario [1c]: Test /job/upsert with the edspec child. You can expect 'done' and a variant in RIO is inserted met een relatie met de parent."
          (let [child-job (post-job :upsert :education-specifications "child-program")
                child-code (job-result-opleidingseenheidcode child-job)]
            (and
              (is (job-done? child-job))
              (is (rio-with-relation? parent-code child-code))
              (let [xml (rio-opleidingseenheid child-code)]
                (is (= "child-program education specification"
                       (get-in-xml xml ["hoOpleiding" "hoOpleidingPeriode" "naamLang"])))))

            (let [test-eigensleutel (UUID/randomUUID)]
              (testing "scenario [2a]: Test /job/link of the edspec parent and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."
                (let [job (post-job :link (job-result-opleidingseenheidcode parent-job)
                                    :education-specifications test-eigensleutel)]
                  (and
                    (is (job-done? job))
                    (is (job-has-diffs? job))))

                (testing "(you can repeat this to expect an error because the new 'eigen sleutel' already exists.)"
                  (let [job (post-job :link (job-result-opleidingseenheidcode child-job)
                                      :education-specifications test-eigensleutel)]
                    (is (job-error? job))))))))

        (testing "scenario [2d]: Test /job/unlink to reset the edspec parent to an empty 'eigen sleutel'."
          (let [job (post-job :unlink (job-result-opleidingseenheidcode parent-job)
                              :education-specifications)]
            (is (job-done? job))))

        (testing "scenario [2b]: Test /job/link to reset the edspec parent to the old 'eigen sleutel'."
          (let [job (post-job :link (job-result-opleidingseenheidcode parent-job)
                              :education-specifications "parent-program")]
            (and
              (is (job-done? job))
              (is (job-has-diffs? job))))))))

  (testing "create a program (for the edSpec child)"
    (testing "scenario [4a]: Test /job/dry-run to see the difference between the program in OOAPI en de aangeboden opleiding in RIO. You can expect RIO to be empty, when you start fresh."
      (let [job (post-job :dry-run/upsert :programs "some")]
        (and
          (is (job-done? job))
          (is (job-dry-run-not-found? job)))))

    (testing "scenario [4c]: Test /job/delete with the program. You can expect an error, because the program is not upserted yet."
      (let [job (post-job :delete :programs "some")]
        (is (job-error? job))))

    (testing "scenario [4b]: Test /job/upsert with the program. You can expect a new aangeboden opleiding. This aangeboden opleiding includes a periode and a cohort. (you can repeat this to test an update of the same data.)"
      (let [job (post-job :upsert :programs "some")]
        (and
          (is (job-done? job))
          (is (job-result-aangebodenopleidingcode job))
          (is (= (str (ooapi-id :programs "some"))
                 (job-result-aangebodenopleidingcode job))
              "aangebodenopleidingcode is the same as the OOAPI id")
          (let [xml (rio-aangebodenopleiding (job-result-aangebodenopleidingcode job))]
            (is (= "2008-10-18"
                   (get-in-xml xml ["aangebodenHOOpleiding" "aangebodenHOOpleidingPeriode" "begindatum"])))
            (is (= "1234qwe12"
                   (get-in-xml xml ["aangebodenHOOpleiding" "aangebodenHOOpleidingCohort" "cohortcode"])))))))

    (testing "scenario [4a]: Test /job/dry-run to see the difference between the program in OOAPI en de opleidingeenheid in RIO. You can expect them to be the same."
      (let [job (post-job :dry-run/upsert :programs "some")]
        (and
          (is (job-done? job))
          (is (job-dry-run-found? job))
          (is (job-without-diffs? job)))))

    (let [test-eigensleutel (UUID/randomUUID)]
      (testing "scenario [5a]: Test /job/link of the program and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."
        (let [job (post-job :link (str (ooapi-id :programs "some"))
                            :programs test-eigensleutel)]
          (and
            (is (job-done? job))
            (is (job-has-diffs? job))))

        (testing "(you can repeat this to expect an error because the new 'eigen sleutel' already exists.)"
          (let [job (post-job :link (str (ooapi-id :programs "some"))
                              :programs test-eigensleutel)]
            (and
              (is (job-done? job))
              (is (job-without-diffs? job))))))

      (testing "scenario [5d]: Test /job/unlink to reset the program to an empty 'eigen sleutel'."
        (let [job (post-job :unlink (str (ooapi-id :programs "some"))
                            :programs test-eigensleutel)]
          (is (job-done? job))))

      (testing "scenario [5b]: Test /job/link to reset the program to the old 'eigen sleutel'."
        (let [job (post-job :link (str (ooapi-id :programs "some"))
                            :programs (ooapi-id :programs "some"))]
          (and
            (is (job-done? job))
            (is (job-has-diffs? job))))))))

(deftest ^:e2e try-to-create-a-program-with-invalid-data
  (testing "scenario [6a]: Test /job/upsert with a program with an invalid onderwijsaanbieder attribute. You can expect 'error'."
    (let [job (post-job :upsert :programs "bad-edu-offerer")]
      (is (job-error? job))))
  (testing "scenario [6b]: Test /job/upsert with a program with an invalid onderwijslocatie attribute. You can expect 'error'."
    (let [job (post-job :upsert :programs "bad-edu-location")]
      (is (job-error? job)))))

(deftest ^:e2e create-a-course-with-its-own-edspec
  (testing "scenario [7a]: Test /job/upsert with the edspec for a course. You can expect 'done'."
    (let [job (post-job :upsert :education-specifications "parent-course")]
      (is (job-done? job))))

  (testing "scenario [7c]: Test /job/dry-run to see the difference between the course in OOAPI en de aangeboden opleiding in RIO. You can expect RIO to be empty, when you start fresh."
    (let [job (post-job :dry-run/upsert :courses "some")]
      (and
        (is (job-done? job))
        (is (job-dry-run-not-found? job)))))

  (testing "scenario [7e]: Test /job/delete with the course. You can expect an error, because the course is not upserted yet."
      (let [job (post-job :delete :courses "some")]
      (is (job-error? job))))

  (testing "scenario [7d]: Test /job/upsert with the course. You can expect a new aangeboden opleiding. This aangeboden opleiding includes a periode and a cohort. (you can repeat this to test an update of the same data.)"
    (let [job (post-job :upsert :courses "some")]
      (is (job-done? job))
      (let [xml (rio-aangebodenopleiding (job-result-aangebodenopleidingcode job))]
        (is (= "1994-09-05"
               (get-in-xml xml ["aangebodenHOOpleidingsonderdeel" "eersteInstroomDatum"])))
        (is (= "2050-11-10"
               (get-in-xml xml ["aangebodenHOOpleidingsonderdeel" "einddatum"]))))))

  (testing "scenario [7c]: Test /job/dry-run to see the difference between the course in OOAPI en de aangeboden opleiding in RIO. You can expect them to be the same."
    (let [job (post-job :dry-run/upsert :courses "some")]
      (and
        (is (job-done? job))
        (is (job-dry-run-found? job))
        (is (job-without-diffs? job)))))

  (let [test-eigensleutel (UUID/randomUUID)]
    (testing "scenario [8a]: Test /job/link of the course and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."
      (let [job (post-job :link (str (ooapi-id :courses "some"))
                          :courses test-eigensleutel)]
        (and
          (is (job-done? job))
          (is (job-has-diffs? job)))))

    (testing "(you can repeat this to expect an error because the new 'eigen sleutel' already exists.)"
      (let [job (post-job :link (str (ooapi-id :courses "some"))
                          :courses test-eigensleutel)]
        (and
          (is (job-done? job))
          (is (job-without-diffs? job)))))

    (testing "scenario [8d]: Test /job/unlink to reset the course to an empty 'eigen sleutel'."
      (let [job (post-job :unlink (str (ooapi-id :courses "some"))
                          :courses test-eigensleutel)]
        (is (job-done? job)))))

  (testing "scenario [8b]: Test /job/link to reset the course to the old 'eigen sleutel'."
    (let [job (post-job :link (str (ooapi-id :courses "some"))
                        :courses (ooapi-id :courses "some"))]
      (and
        (is (job-done? job))
        (is (job-has-diffs? job))))))

(deftest ^:e2e try-to-create-edspecs-with-invalid-data
  (testing "scenario [3a]: Test /job/upsert/<invalid type> to see how the rio mapper reacts on an invalid api call. You can expect a 404 response."
    (let [job (post-job :upsert "not-a-valid-type" (UUID/randomUUID))]
      (is (= http-status/not-found (:status job)))))

  (testing "scenario [3b]: Test /job/upsert with an edspec parent with an invalid type attribute. You can expect 'error'."
    (let [job (post-job :upsert :education-specifications "bad-type")]
      (and
        (is (job-error? job))
        (is (= "fetching-ooapi" (job-result job :phase)))))))
