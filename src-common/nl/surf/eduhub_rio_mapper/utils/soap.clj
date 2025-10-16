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

(ns nl.surf.eduhub-rio-mapper.utils.soap
  (:require
   [clojure.data.xml :as clj-xml]
   [clojure.string :as str]
   [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils])
  (:import
   (java.io ByteArrayOutputStream)
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest Signature)
   (java.time OffsetDateTime)
   (java.time.format DateTimeFormatter DateTimeFormatterBuilder)
   (java.util Base64 UUID)
   (javax.xml.crypto.dsig CanonicalizationMethod)
   (org.apache.xml.security Init)
   (org.apache.xml.security.c14n Canonicalizer)
   (org.w3c.dom Document Element NodeList)))

;;; Constants

(def x509v3 "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509v3")
(def base64binary "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary")
(def digest-algorithm "http://www.w3.org/2001/04/xmlenc#sha256")
(def signature-algorithm "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256")

(def wsu-schema "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd")
(def ws-addressing "http://www.w3.org/2005/08/addressing")
(def soap-envelope "http://schemas.xmlsoap.org/soap/envelope/")

(def expiry-in-minutes 10)

(def ^:private millisecond-precision 3)
(def ^:private ^DateTimeFormatter instant-formatter (.toFormatter (.appendInstant (DateTimeFormatterBuilder.) millisecond-precision)))
(defn format-instant [instant] (.format instant-formatter instant))

(defn- generate-timestamp ^OffsetDateTime [] (OffsetDateTime/now))
(defn- generate-message-id [] (UUID/randomUUID))
(defn- base64encode [bytes] (.encodeToString (Base64/getEncoder) bytes))
(defn- text-content= [^Element element ^String content] (.setTextContent element content))

(defn- to-ns [prefix schema] (str "xmlns:" prefix "=\"" schema "\""))
(defn xmlns [prefix-schema] (str/join " " (map #(to-ns (first %) (last %)) prefix-schema)))

(def parts-data {"From" ["duo soapenv" ["soapenv:Header" "wsa:From"]]
                 "To"        ["duo soapenv" ["soapenv:Header" "wsa:To"]]
                 "Action"    ["duo soapenv" ["soapenv:Header" "wsa:Action"]]
                 "MessageID" ["duo soapenv" ["soapenv:Header" "wsa:MessageID"]]
                 "Body"      ["duo" ["soapenv:Body"]]
                 "Timestamp" ["wsse wsa duo soapenv" ["soapenv:Header" "wsu:Timestamp"]]})

(defn- wrap-in-envelope [sexp-body contract schema action from to certificate parts]
  {:pre [(some? certificate)]}
  (let [timestamp (generate-timestamp)
        bst-id (UUID/randomUUID)
        message-id (generate-message-id)]
    [:soapenv:Envelope {:xmlns:duo schema
                        :xmlns:soapenv "http://schemas.xmlsoap.org/soap/envelope/"
                        :xmlns:wsu wsu-schema}
     [:soapenv:Header {:xmlns:wsa "http://www.w3.org/2005/08/addressing"}
      [:wsse:Security {:xmlns:wsse "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"}
       [:wsse:BinarySecurityToken {:EncodingType base64binary :ValueType x509v3 :wsu:Id (str "X509-" bst-id)}
        (base64encode certificate)]
       [:ds:Signature {:xmlns:ds "http://www.w3.org/2000/09/xmldsig#"}
        [:ds:SignedInfo {:xmlns:ec CanonicalizationMethod/EXCLUSIVE}
         [:ds:CanonicalizationMethod {:Algorithm CanonicalizationMethod/EXCLUSIVE}
          [:ec:InclusiveNamespaces {:PrefixList "wsa duo soapenv"}]]
         [:ds:SignatureMethod {:Algorithm signature-algorithm}]
         (for [[nodename [prefixlist _path]] parts]
           [:ds:Reference {:URI (str "#id-" nodename)}
            [:ds:Transforms
             [:ds:Transform {:Algorithm CanonicalizationMethod/EXCLUSIVE}
              [:ec:InclusiveNamespaces {:PrefixList prefixlist}]]]
            [:ds:DigestMethod {:Algorithm digest-algorithm}]
            [:ds:DigestValue]])]
        [:ds:SignatureValue]
        [:ds:KeyInfo
         [:wsse:SecurityTokenReference
          [:wsse:Reference {:URI (str "#X509-" bst-id) :ValueType x509v3}]]]]
       [:wsu:Timestamp {:wsu:Id "id-Timestamp"}
        [:wsu:Created (format-instant timestamp)]
        [:wsu:Expires (format-instant (.plusMinutes timestamp expiry-in-minutes))]]]
      [:wsa:Action {:wsu:Id "id-Action"} (str contract "/" action)]
      [:wsa:From {:wsu:Id "id-From"}
       [:wsa:Address from]]
      [:wsa:MessageID {:wsu:Id "id-MessageID"} message-id]
      [:wsa:To {:wsu:Id "id-To"} to]]
     [:soapenv:Body {:wsu:Id "id-Body"} sexp-body]]))

;; uri looks like: #id-Name
;; Remove first 4 characters to get the name.
(defn- id-reference-to-part-name [^String uri] (.substring uri 4))

(defn digest-sha256
  "Returns sha-256 digest in base64 format."
  [^String inputstring]
  (let [input-bytes (.getBytes inputstring StandardCharsets/UTF_8)
        digest (.digest (MessageDigest/getInstance "SHA-256") input-bytes)]
    (.encodeToString (Base64/getEncoder) digest)))

(defn- do-byte-array-outputstream [f]
  (let [baos (ByteArrayOutputStream.)]
    (f baos)
    (.toString baos StandardCharsets/UTF_8)))

(defn- canonicalize-excl
  "Returns a canonical string representation of the supplied Element."
  [^Element element inclusive-ns]
  (Init/init)
  (do-byte-array-outputstream
    #(.canonicalizeSubtree (Canonicalizer/getInstance CanonicalizationMethod/EXCLUSIVE) element inclusive-ns false %)))

(defn- add-digest-to-references [envelope-node ^Element signed-info-node]
  (let [^NodeList elements (.getElementsByTagName signed-info-node "ds:Reference")]
    (doseq [i (range (.getLength elements))]
      (let [^Element reference (.item elements i)
            [prefixlist path] (parts-data (id-reference-to-part-name (.getAttribute reference "URI")))]
        (text-content= (xml-utils/get-in-dom reference ["ds:DigestValue"])
                       (digest-sha256
                         (canonicalize-excl (xml-utils/get-in-dom envelope-node path)
                                                      prefixlist)))))))

(defn- sign-sha256rsa
  "Returns signature of string input with supplied PrivateKey."
  [^String input pkey]
  (let [signature (Signature/getInstance "SHA256withRSA")
        ^bytes input-bytes (.getBytes input StandardCharsets/UTF_8)]
    (.initSign signature pkey)
    (.update signature input-bytes)
    (.encodeToString (Base64/getEncoder) (.sign signature))))

(defn- calculate-signature [signed-info private-key]
  (sign-sha256rsa (canonicalize-excl signed-info "wsa duo soapenv") private-key))

(defn request-body [action rio-sexp schema sender-oin recipient-oin]
  {:pre [sender-oin recipient-oin (not (str/blank? action))]}
  (into [(keyword (str "duo:" action "_request")) {:xmlns:duo schema}
         [:duo:identificatiecodeBedrijfsdocument (UUID/randomUUID)]
         [:duo:verzendendeInstantie sender-oin]
         [:duo:ontvangendeInstantie recipient-oin]
         [:duo:datumTijdBedrijfsdocument (format-instant (generate-timestamp))]]
        rio-sexp))

(defn convert-to-signed-dom-document
  "Takes a XML document representing a RIO-request, and an action, and wraps it in a signed SOAP org.w3c.dom.Document."
  [sexp-body {:keys [contract schema to-url from-url]} action {:keys [private-key certificate]}]
  {:pre [(some? certificate)]}
  (let [^Document document (-> sexp-body
                               (wrap-in-envelope contract schema action from-url to-url certificate parts-data)
                               clj-xml/sexp-as-element
                               clj-xml/emit-str
                               xml-utils/str->dom)
        ^Element envelope-node (.getDocumentElement document)
        signature-node (xml-utils/get-in-dom envelope-node ["soapenv:Header" "wsse:Security" "ds:Signature"])
        signed-info-node (xml-utils/get-in-dom signature-node ["ds:SignedInfo"])
        signature-value-node (xml-utils/get-in-dom signature-node ["ds:SignatureValue"])]
    (add-digest-to-references envelope-node signed-info-node)
    (text-content= signature-value-node (calculate-signature signed-info-node private-key))
    document))

(defn guard-valid-sexp [sexp validator]
  (-> sexp clj-xml/sexp-as-element clj-xml/emit-str validator)
  sexp)

(defn prepare-soap-call
  "Converts `rio-sexp` to a signed soap document. See GLOSSARY.md for information about arguments.
   Returns nil if document is invalid according to the XSD."
  [action rio-sexp {:keys [validator schema sender-oin recipient-oin] :as rio-datamap} credentials]
  {:pre [sender-oin recipient-oin]}
  (-> action
      (request-body rio-sexp schema sender-oin recipient-oin)
      (guard-valid-sexp validator)
      (convert-to-signed-dom-document rio-datamap action credentials)
      xml-utils/dom->str))
