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

(ns nl.surf.eduhub-rio-mapper.re-spec-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.re-spec :as sut]))

(deftest test-dangerous-codes
  (is (not (sut/looks-like-html? "foobar < thing")))
  (is (sut/looks-like-html? "foobar &lt; thing"))
  (is (sut/looks-like-html? "foo<bar"))
  (is (sut/looks-like-html? "foo&lt;bar"))
  (is (sut/looks-like-html? "foo&lt;bar"))
  (is (sut/looks-like-html? "foo &#34; bar")))
