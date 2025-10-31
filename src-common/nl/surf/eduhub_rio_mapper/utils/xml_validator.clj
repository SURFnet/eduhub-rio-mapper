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

(ns nl.surf.eduhub-rio-mapper.utils.xml-validator
  (:require [clojure.java.io :as io])
  (:import java.io.StringReader
           javax.xml.XMLConstants
           javax.xml.transform.stream.StreamSource
           javax.xml.validation.SchemaFactory
           org.xml.sax.SAXException))

(defn create-problems-fn
  [^String schema-path]
  (let [validator (-> (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)
                      (.newSchema (-> schema-path
                                      io/resource
                                      io/input-stream
                                      StreamSource.))
                      .newValidator)]
    (fn problems
      [^String xmldoc]
      (try
        (->> xmldoc StringReader. StreamSource. (.validate validator))
        nil
        (catch SAXException ex
          ex)))))

(defn create-validation-fn
  "Creates an XML validator function with given schema-path.
  The function expects an XML string and throws an error if it does
  not conform to the schema."
  [^String schema-path]
  (let [problems (create-problems-fn schema-path)]
    (fn validation
      [^String xmldoc]
      (when-let [ex (problems xmldoc)]
        (throw (ex-info (str "XSD validation error in document: " (ex-message ex) "; schema-path: " schema-path)
                        {:doc        xmldoc
                         :retryable? false}
                        ex)))
      xmldoc)))
