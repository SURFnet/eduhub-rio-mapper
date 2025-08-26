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

(ns nl.surf.eduhub-rio-mapper.processing-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.rio.helper :as rio.helper]))

(deftest test-blocking-retry
  (let [retry-3-times #(rio.helper/blocking-retry % [0.001 0.001 0.001] nil)]
    (testing "first attempt successful"
      (let [atm (atom 1)]
        (is (= true (retry-3-times #(do (swap! atm dec)
                                     (= 0 @atm)))))))
    (testing "no successful attempts"
      (let [atm (atom 5)]
        (is (nil? (retry-3-times #(do (swap! atm dec)
                                     (= 0 @atm)))))))
    (testing "third attempt successful"
      (let [atm (atom 3)]
        (is (= true (retry-3-times #(do (swap! atm dec)
                                     (= 0 @atm)))))))))
