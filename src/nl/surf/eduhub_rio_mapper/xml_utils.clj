(ns nl.surf.eduhub-rio-mapper.xml-utils
  (:require [clojure.data.xml :as clj-xml])
  [:import [java.io StringWriter StringReader ByteArrayOutputStream FileInputStream]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest Signature KeyStore KeyStore$PrivateKeyEntry KeyStore$PasswordProtection]
           [java.util Base64]
           [javax.xml.crypto.dsig CanonicalizationMethod]
           [javax.xml.parsers DocumentBuilderFactory]
           [javax.xml.transform TransformerFactory]
           [javax.xml.transform.dom DOMSource]
           [javax.xml.transform.stream StreamResult]
           [org.apache.xml.security Init]
           [org.apache.xml.security.c14n Canonicalizer]
           [org.w3c.dom Element]
           [org.xml.sax InputSource]])

(defn digest-sha256
  "Returns sha-256 digest in base64 format."
  [^String inputstring]
  (let [input-bytes (.getBytes inputstring StandardCharsets/UTF_8)
        digest (.digest (MessageDigest/getInstance "SHA-256") input-bytes)]
    (.encodeToString (Base64/getEncoder) digest)))

(defn sign-sha256rsa
  "Returns signature of string input with supplied PrivateKey."
  [^String input pkey]
  (let [signature (Signature/getInstance "SHA256withRSA")
        ^bytes input-bytes (.getBytes input StandardCharsets/UTF_8)]
    (.initSign signature pkey)
    (.update signature input-bytes)
    (.encodeToString (Base64/getEncoder) (.sign signature))))

(defn sexp->xml
  "Returns string with XML document of data.xml representation in s-expression format."
  [sexp]
  (clj-xml/emit-str (clj-xml/sexp-as-element sexp)))

(defn- db-factory ^DocumentBuilderFactory []
  (let [factory (DocumentBuilderFactory/newInstance)]
    (.setNamespaceAware factory true)
    factory))

(defn xml->dom
  "Parses string with XML content into org.w3c.dom.Document."
  [^String xml]
  (let [builder (.newDocumentBuilder (db-factory))
        doc (.parse builder (InputSource. (StringReader. xml)))]
    (.normalize (.getDocumentElement doc))
    doc))

(defn- do-string-writer [f]
  (let [sw (StringWriter.)]
    (f sw)
    (.toString sw)))

(defn- do-byte-array-outputstream [f]
  (let [baos (ByteArrayOutputStream.)]
    (f baos)
    (.toString baos StandardCharsets/UTF_8)))

(defn dom->xml
  "Renders org.w3c.dom.Document to a String."
  [dom]
  (do-string-writer
    #(-> (TransformerFactory/newInstance)
         .newTransformer
         (.transform (DOMSource. dom) (StreamResult. ^StringWriter %)))))

(defn sexp->dom
  "Converts XML document of data.xml representation in s-expression format into org.w3c.dom.Document."
  [sexp]
  (-> sexp sexp->xml xml->dom))

(defn- dom-reducer [^Element element tagname] (.item (.getElementsByTagName element tagname) 0))

(defn get-in-dom
  "Walks through the DOM-tree starting with element, choosing the first element with matching qualified name."
  [current-element tag-names]
  (reduce dom-reducer current-element tag-names))

(defn write-dom-to-file [dom filename] (spit filename (dom->xml dom)))

(defn canonicalize-excl
  "Returns a canonical string representation of the supplied Element."
  [^Element element inclusive-ns]
  (Init/init)
  (do-byte-array-outputstream
    #(.canonicalizeSubtree (Canonicalizer/getInstance CanonicalizationMethod/EXCLUSIVE) element inclusive-ns false %)))

(defn private-key-certificate [^String keystore-file-name ^String keystore-password alias]
  (let [jks (KeyStore/getInstance "JKS")
        password (.toCharArray keystore-password)
        fis (FileInputStream. keystore-file-name)]
    (.load jks fis password)
    (let [^KeyStore$PrivateKeyEntry entry (.getEntry jks alias (KeyStore$PasswordProtection. password))]
      {:private-key (.getKey jks alias password)
       :certificate (.getEncoded (.getCertificate entry))})))