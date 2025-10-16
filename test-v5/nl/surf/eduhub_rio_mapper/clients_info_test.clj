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

(ns nl.surf.eduhub-rio-mapper.clients-info-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]))

(deftest test-make-clients-info
  (let [info (clients-info/read-clients-data {:path "test/test-clients.json"})]
    (is (= {:client-id              "rio-mapper-dev6.jomco.nl"
            :institution-schac-home "demo06.test.surfeduhub.nl"
            :institution-oin        "0000000700025BE00000"
            :institution-name       "Jom College"}
           (clients-info/client-info info "rio-mapper-dev6.jomco.nl")))))
