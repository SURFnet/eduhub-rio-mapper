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

(ns nl.surf.eduhub-rio-mapper.rio.opleidingseenheid-finder-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.v5.commands.dry-run :as dry-run]))

(defn generate-diff [a b] (dry-run/generate-diff-ooapi-rio {:rio-summary a :ooapi-summary b}))

(deftest generate-diff-ooapi-rio
  (is (= {}
         (generate-diff {} {})))
  (is (= {:fred {:diff false}}
         (generate-diff {:fred 1} {:fred 1})))
  (is (= {:fred   {:diff     true
                   :current  1
                   :proposed nil}
          :barney {:diff     true
                   :current  nil
                   :proposed 2}}
         (generate-diff {:fred 1} {:barney 2})))
  (is (= {:fred {:diff     true
                 :current  1
                 :proposed 2}}
         (generate-diff {:fred 1} {:fred 2}))))
