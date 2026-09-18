;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2026 SURFnet B.V.
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

(ns nl.surf.eduhub-rio-mapper.rio.conversion-test
  (:require [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.rio.conversion :as conversion]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils]))

(def types
  [[:oe "hoOpleiding" "eigenOpleidingseenheidSleutel"]
   [:ao "aangebodenHOOpleiding" "eigenAangebodenOpleidingSleutel"]])

(defn- response [tag naam old-id]
  (str "<ns:response xmlns:ns='urn:test'><ns:" tag ">"
       (when (some? old-id)
         (str "<ns:kenmerken><ns:kenmerknaam>" naam "</ns:kenmerknaam>"
              "<ns:kenmerkwaardeTekst>" old-id "</ns:kenmerkwaardeTekst></ns:kenmerken>"))
       "</ns:" tag "></ns:response>"))

(deftest conversion-key-changes-test
  (doseq [[rio-type tag naam] types
          old-id [nil "old-id"]
          [opts expected] [[[] old-id]
                           [[:change-key "new-id"] "new-id"]
                           [[:change-key ""] ""]
                           [[:change-key nil] nil]]]
    (testing (str rio-type " existing " old-id " options " opts)
      (let [{:keys [rio-sexp] :as result}
            (apply conversion/rio-obj-raadplegen->beheren
                   (response tag naam old-id) rio-type opts)
            key-elements (filter #(= naam (xml-utils/sexp-child-text % :duo:kenmerknaam))
                                 (xml-utils/sexp-child-elements (first rio-sexp)))]
        (is (= old-id (:old-id result)))
        (is (= (if (nil? expected) 0 1) (count key-elements)))
        (is (= expected (conversion/eigen-sleutel (first rio-sexp) rio-type)))))))

(deftest set-eigen-sleutel-preserves-other-data-test
  (doseq [[rio-type _ naam] types]
    (let [unrelated [:duo:kenmerken [:duo:kenmerknaam "other"] [:duo:kenmerkwaardeTekst "keep"]]
          nested [:duo:periode [:duo:kenmerken [:duo:kenmerknaam naam] [:duo:kenmerkwaardeTekst "nested"]]]
          original [:duo:object "\n" unrelated nested]
          inserted (conversion/set-eigen-sleutel original rio-type "first")
          replaced (conversion/set-eigen-sleutel inserted rio-type "second")]
      (is (= "first" (conversion/eigen-sleutel inserted rio-type)))
      (is (= "second" (conversion/eigen-sleutel replaced rio-type)))
      (is (= original (conversion/set-eigen-sleutel replaced rio-type nil)))
      (is (= original (conversion/set-eigen-sleutel original rio-type nil))))))
