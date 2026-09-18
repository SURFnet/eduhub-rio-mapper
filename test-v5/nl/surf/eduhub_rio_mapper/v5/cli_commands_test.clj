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

(ns nl.surf.eduhub-rio-mapper.v5.cli-commands-test
  (:require [clojure.test :refer [deftest is]]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.rio.mutation-test-helper :as mutation-helper]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.v5.cli-commands :as cli]))

(deftest test-rio-uses-inserted-opleidingseenheidcode-test
  (let [rio-code "1010O8815"
        inserted-request (atom nil)
        linked-request (atom nil)
        lookup-code (atom nil)
        client {:client-id "test-client"
                :institution-oin "123"
                :institution-schac-home "example.edu"}
        handlers {:getter (constantly nil)
                  :insert! (fn [request]
                             (reset! inserted-request request)
                             (mutation-helper/successful-mutation "aanleveren_opleidingseenheid" rio-code))
                  :link! (fn [request]
                           (reset! linked-request request)
                           {:success true})}]
    ;; Keep the queue check successful so the CLI cannot call System/exit.
    ;; Assert the captured lookup arguments after the command returns.
    (with-redefs [rio.loader/find-eigen-opleidingseenheid-sleutel
                  (fn [code _getter _oin]
                    (reset! lookup-code code)
                    (::ooapi/id @linked-request))]
      (with-out-str
        (cli/process-command "test-rio" ["test-client"]
                             {:config {:clients [client]} :handlers handlers})))
    (is (some? @inserted-request))
    (is (some? @linked-request))
    (is (not= (::ooapi/id @inserted-request) (::ooapi/id @linked-request)))
    (is (= rio-code (::rio/opleidingscode @linked-request))
        "Link the opleidingseenheid returned by insert!")
    (is (= rio-code @lookup-code)
        "Verify the new key on the same opleidingseenheid")))
