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

(ns nl.surf.eduhub-rio-mapper.v5.endpoints.status-test
  (:require [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.rio.mutation-test-helper :as mutation-helper]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.utils.redis :as redis]
            [nl.surf.eduhub-rio-mapper.v5.endpoints.status :as status]))

(deftest successful-upsert-retains-rio-code-test
  (doseq [[description ooapi-type result expected-attributes]
          [["opleidingseenheid" "education-specification"
            (mutation-helper/successful-mutation "aanleveren_opleidingseenheid" "1010O8815")
            {:opleidingseenheidcode "1010O8815"}]
           ["aangeboden opleiding" "program"
            (assoc (mutation-helper/successful-mutation "aanleveren_aangebodenOpleiding"
                                                       "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672")
                   ::rio/aangeboden-opleiding-code "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672")
            {:aangebodenopleidingcode "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672"}]]]
    (testing description
      (let [stored-status (atom nil)]
        ;; Capture the persisted status without requiring Redis.
        (with-redefs [redis/set (fn [_conn _key value & _opts]
                                 (reset! stored-status value))]
          ((status/make-set-status-fn {:status-ttl-sec 60})
           {:token "test-upsert"
            :action "upsert"
            ::ooapi/type ooapi-type
            ::ooapi/id "12345678-1234-2345-3456-123456789abc"}
           :done result))
        (is (= :done (:status @stored-status)))
        (is (= expected-attributes (:attributes @stored-status)))))))
