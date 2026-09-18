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

(ns nl.surf.eduhub-rio-mapper.rio.mutation-test-helper
  (:require [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.utils.soap :as soap]))

(defn successful-mutation
  "Parse a successful SOAP response using the real mutator."
  [action code]
  (let [code-tag (case action
                   "aanleveren_opleidingseenheid" "opleidingseenheidcode"
                   "aanleveren_aangebodenOpleiding" "aangebodenOpleidingCode")
        response (str "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\""
                      " xmlns:ns2=\"" mutator/schema "\"><SOAP-ENV:Body>"
                      "<ns2:" action "_response>"
                      "<ns2:requestGoedgekeurd>true</ns2:requestGoedgekeurd>"
                      "<ns2:" code-tag ">" code "</ns2:" code-tag ">"
                      "</ns2:" action "_response></SOAP-ENV:Body></SOAP-ENV:Envelope>")]
    ;; Stub request construction and transport, preserving response parsing.
    (with-redefs [soap/prepare-soap-call (constantly "request")
                  http-utils/send-http-request (constantly {:status 200 :body response})]
      (mutator/mutate! {:action action :sender-oin "123" :rio-sexp [[:duo:placeholder]]}
                       {:recipient-oin "456" :update-url "https://example.invalid/rio"}))))
