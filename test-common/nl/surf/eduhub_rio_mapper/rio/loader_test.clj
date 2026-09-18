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

(ns nl.surf.eduhub-rio-mapper.rio.loader-test
  (:require [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.rio.loader :as loader]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils])
  (:import [clojure.lang ExceptionInfo]
           [org.w3c.dom Element]))

(defn- response [approved content]
  (str "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ns2=\"urn:rio\">"
       "<SOAP-ENV:Body><ns2:opvragen_opleidingseenheid_response>"
       "<ns2:requestGoedgekeurd>" approved "</ns2:requestGoedgekeurd>"
       content
       "</ns2:opvragen_opleidingseenheid_response></SOAP-ENV:Body></SOAP-ENV:Envelope>"))

(defn- getter [request]
  (#'loader/rio-get request {} (constantly "request")))

(deftest dom-response-test
  (with-redefs [http-utils/send-http-request
                (constantly {:body (response "true" "<ns2:hoOpleiding/>")})]
    (let [element (getter {::rio/type loader/opleidingseenheid-type
                          ::rio/opleidingscode "1010O8815"
                          :response-type :dom})]
      (is (instance? Element element))
      (is (some? (xml-utils/get-in-dom element ["ns2:hoOpleiding"]))))))

(deftest raw-dom-response-test
  (doseq [[approved content]
          [["true" "<ns2:hoOpleiding/>"]
           ["false" "<ns2:foutmelding/>"]]]
    (testing (str "raw DOM preserves the response when requestGoedgekeurd is " approved)
      (let [parse xml-utils/str->dom
            parse-count (atom 0)]
        (with-redefs [http-utils/send-http-request (constantly {:body (response approved content)})
                      xml-utils/str->dom (fn [xml]
                                           (swap! parse-count inc)
                                           (parse xml))]
          (let [element (getter {::rio/type loader/opleidingseenheid-type
                                 ::rio/opleidingscode "1010O8815"
                                 :response-type :raw-dom})]
            (is (instance? Element element))
            (is (= "opvragen_opleidingseenheid_response" (.getLocalName element)))
            (is (= approved (xml-utils/single-xml-unwrapper element "ns2:requestGoedgekeurd")))
            (is (some? (xml-utils/get-in-dom element [(if (= "true" approved) "ns2:hoOpleiding" "ns2:foutmelding")]))))
          (is (= 1 @parse-count)))))))

(deftest find-eigen-opleidingseenheid-sleutel-test
  (doseq [[description content expected]
          [["matching key among multiple kenmerken"
            (str "<ns2:kenmerken><ns2:kenmerknaam>other</ns2:kenmerknaam>"
                 "<ns2:kenmerkwaardeTekst>wrong</ns2:kenmerkwaardeTekst></ns2:kenmerken>"
                 "<ns2:kenmerken><ns2:kenmerknaam>eigenOpleidingseenheidSleutel</ns2:kenmerknaam>"
                 "<ns2:kenmerkwaardeTekst>new-uuid</ns2:kenmerkwaardeTekst></ns2:kenmerken>")
            "new-uuid"]
           ["no kenmerken" "" nil]
           ["no matching kenmerk"
            "<ns2:kenmerken><ns2:kenmerknaam>other</ns2:kenmerknaam></ns2:kenmerken>" nil]
           ["matching kenmerk without a value"
            "<ns2:kenmerken><ns2:kenmerknaam>eigenOpleidingseenheidSleutel</ns2:kenmerknaam></ns2:kenmerken>" nil]]]
    (testing description
      (with-redefs [http-utils/send-http-request
                    (constantly {:body (response "true" (str "<ns2:hoOpleiding>" content "</ns2:hoOpleiding>"))})]
        (is (= expected (loader/find-eigen-opleidingseenheid-sleutel "1010O8815" getter "oin"))))))
  (testing "rejected response, such as a missing opleidingseenheid"
    (with-redefs [http-utils/send-http-request
                  (constantly {:body (response "false" "<ns2:foutmelding/>")})]
      (is (thrown? ExceptionInfo
                   (loader/find-eigen-opleidingseenheid-sleutel "1010O8815" getter "oin"))))))

(deftest opleidingeenheid-exists-test
  (doseq [tag loader/opleidingseenheid-namen]
    (with-redefs [http-utils/send-http-request
                  (constantly {:body (response "true" (str "<ns2:" (name tag) "/>"))})]
      (is (true? (loader/opleidingeenheid-exists? "1010O8815" getter "oin")))))
  (with-redefs [http-utils/send-http-request
                (constantly {:body (response "false" "<ns2:foutmelding/>")})]
    (is (false? (loader/opleidingeenheid-exists? "1010O8815" getter "oin")))))
