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

(ns nl.surf.eduhub-rio-mapper.rio.mutator
  (:require [clojure.data.xml :as clj-xml]
            [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.rio.loader :as loader]
            [nl.surf.eduhub-rio-mapper.specs.mutation :as mutation]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.utils.soap :as soap]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils]
            [nl.surf.eduhub-rio-mapper.utils.xml-validator :as xml-validator])
  (:import (org.w3c.dom Element)))

(def schema
  "http://duo.nl/schema/DUO_RIO_Beheren_OnderwijsOrganisatie_V4")

(def contract
  "http://duo.nl/contract/DUO_RIO_Beheren_OnderwijsOrganisatie_V4")

(def validator
  (xml-validator/create-validation-fn "DUO_RIO_Beheren_OnderwijsOrganisatie_V4.xsd"))

(defn make-datamap
  [sender-oin recipient-oin]
  (assert [sender-oin recipient-oin])
  {:schema    schema
   :contract  contract
   :validator validator
   :sender-oin sender-oin
   :recipient-oin recipient-oin
   :to-url    (str "https://duo.nl/RIO/services/beheren4.0?oin=" recipient-oin)
   :from-url  (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" sender-oin)})

;; Set of rio error codes delivered by Surf that cannot be recovered from:
(def unrecoverable-codes
  #{
    "AUT401" ;; Er is geen autorisatie voor deze bewerking
    "A01060" ;; De onderwijslocatie komt niet voor in RIO
    "A01160" ;; Externe identificatie niet uniek is niet recoverable dus zou niet moeten retryen
    "K01010" ;; 'propedeutischeFase' komt niet vaak genoeg voor als kenmerk
    "P01081" ;; Er bestaat al een opleidingseenheid met dezelfde eigenOpleidingseenheidSleutel
    "N11020" ;; De einddatum van een aanmeldperiode moet na de begindatum liggen
    "N11030" ;; De einddatum van een instroomperiode moet na de begindatum liggen
    })

;; Note: `P01810: Er bestaan nog verwijzingen naar de te verwijderen
;; opleidingseenheid; deze dienen eerst verwijderd te worden`
;;
;; This error is sometimes recoverable; deletes are processed with
;; delay by RIO, meaning that deleting an entity recusively sometimes
;; fails because we attempt to delete related entities too quickly.

(defn- guard-rio-mutate-response [^Element element description]
  {:pre [(some? element)]}
  (loader/log-rio-action-response description element)
  (when-not (loader/goedgekeurd? element)
    (let [code (some-> element
                       (xml-utils/get-in-dom ["ns2:foutmelding" "ns2:foutcode"])
                       (.getFirstChild)
                       (.getTextContent))
          msg  (some-> element
                       (xml-utils/get-in-dom ["ns2:foutmelding" "ns2:fouttekst"])
                       (.getFirstChild)
                       (.getTextContent))]
      (throw (ex-info (str "Rejected by RIO: " code ": " msg)
                      {:element element,
                       :code code,
                       :retryable? (not (unrecoverable-codes code))}))))
  (-> element
      xml-utils/dom->str
      clj-xml/parse-str
      xml-utils/xml-event-tree->edn))

(defn mutate! [{:keys [action sender-oin rio-sexp] :as mutation}
               {:keys [recipient-oin credentials update-url connection-timeout-millis]}]
  {:pre [action recipient-oin sender-oin rio-sexp update-url
         (s/valid? ::mutation/mutation-response mutation)
         (vector? (first rio-sexp))
         sender-oin]}
  (-> {:url                update-url
       :method             :post
       :body               (soap/prepare-soap-call action
                                                   rio-sexp
                                                   (make-datamap sender-oin recipient-oin)
                                                   credentials)
       :headers            {"SOAPAction" (str contract "/" action)}
       :connection-timeout connection-timeout-millis
       :content-type       :xml}
      (merge credentials)
      (http-utils/send-http-request)
      (get :body)
      (xml-utils/str->dom)
      (.getDocumentElement)
      (xml-utils/get-in-dom ["SOAP-ENV:Body" (str "ns2:" action "_response")])
      (guard-rio-mutate-response (str action))))
