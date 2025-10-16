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

(ns nl.surf.eduhub-rio-mapper.utils.xml-utils
  (:require [clojure.data.xml :as clj-xml])
  (:import [java.io StringReader StringWriter]
           [javax.xml.parsers DocumentBuilderFactory]
           [javax.xml.transform Transformer TransformerFactory]
           [javax.xml.transform.dom DOMSource]
           [javax.xml.transform.stream StreamResult]
           [org.w3c.dom Document Element]
           [org.xml.sax InputSource]))

(defn- do-string-writer [write]
  (-> (StringWriter.)
      (doto write)
      (.toString)))

(defn- db-factory ^DocumentBuilderFactory []
  (doto (DocumentBuilderFactory/newInstance)
    (.setNamespaceAware true)))

(defn- different-keys? [content]
  (when content
    (let [unique-tags (set (keep :tag content))]
      (= (count unique-tags) (count content)))))

(defn xml-event-tree->edn
  "Convert xml event tree (as produced by clojure.data.xml/parse-str) on simplified edn structure.
   It uses a map with keywords :content, :tag and :attrs for individual nodes."
  [element]
  (cond
    (nil? element) nil
    (string? element) element

    (sequential? element) (if (> (count element) 1)
                            (if (different-keys? element)
                              (reduce into {} (map (partial xml-event-tree->edn ) element))
                              (map xml-event-tree->edn element))
                            (xml-event-tree->edn  (first element)))

    (and (map? element) (empty? element)) {}
    (map? element) (if (:attrs element)
                     {(:tag element) (xml-event-tree->edn (:content element))
                      (keyword (str (name (:tag element)) "Attrs")) (:attrs element)}
                     {(:tag element) (xml-event-tree->edn  (:content element))})

    :else nil))

;;; Conversion functions between the following formats:
;;; sexp: hiccup-like s-expressions
;;; xml: string with xml document
;;; dom: Java Document with parsed xml
;;; element: Single Element within a DOM tree
;;; edn: Clojure representation of XML document

(defn str->dom
  "Parses string with XML content into org.w3c.dom.Document."
  ^Document [^String xml]
  (let [builder (.newDocumentBuilder (db-factory))
        doc (.parse builder (InputSource. (StringReader. xml)))]
    (.normalize (.getDocumentElement doc))
    doc))

(defn dom->str
  "Renders org.w3c.dom.Document to a String."
  ([dom]
   (dom->str dom (-> (TransformerFactory/newInstance) .newTransformer)))
  ([dom ^Transformer transformer]
   (do-string-writer
     #(.transform transformer (DOMSource. dom) (StreamResult. ^StringWriter %)))))

(defn element->edn
  "Convert org.w3c.dom.Element into simplified edn structure."
  [^Element element]
  (-> element
      dom->str
      clj-xml/parse-str
      xml-event-tree->edn))

(defn- dom-reducer-jvm [^Element element tagname]
  (when element
    (.item (.getElementsByTagName element tagname) 0)))

(defn get-in-dom
  "Get element in DOM tree using path of tag-names.

  Walks through the DOM-tree starting with element, choosing the first
  element with matching qualified name, returns `nil` if no matching
  element is found."
  ^Element [current-element tag-names]
  (reduce dom-reducer-jvm current-element tag-names))

(defn find-in-xmlseq [xmlseq pred]
  (loop [xmlseq xmlseq]
    (when-let [element (first xmlseq)]
      (or (pred element)
          (recur (rest xmlseq))))))

(defn find-content-in-xmlseq [xmlseq k]
  {:pre [(seq? xmlseq)
         (:tag (first xmlseq))]}
  (find-in-xmlseq xmlseq #(and (= k (:tag %)) (-> % :content first))))

(defn find-all-in-xmlseq [xmlseq pred]
  (loop [xmlseq xmlseq
         acc    []]
    (if-let [element (first xmlseq)]
      (let [x (pred element)]
        (recur (rest xmlseq) (if x (conj acc x) acc)))
      acc)))



;; A simple pretty printer for debugging purposes.  It's slow,
;; incomplete and may emit unparseable XML but is useful for human
;; consumption.
(defmulti ^:private debug-print-xml-node (fn [node _ _] (map? node)))

(defmethod debug-print-xml-node true
  [node indent indent-str]
  (print (str indent "<" (name (:tag node))))
  (when (:attrs node)
    (doseq [attr (:attrs node)]
      (print (str " " (name (key attr)) "=\"" (val attr) "\""))))
  (if (:content node)
    (do
      (println ">")
      (doseq [c (:content node)]
	(debug-print-xml-node c (str indent indent-str) indent-str))
      (print indent)
      (println (str "</" (name (:tag node)) ">")))
    (println "/>")))

(defmethod debug-print-xml-node false
  [node indent _]
  (println (str indent node)))

(defn debug-print-xml
  "Basic XML pretty printer for debugging.

  It's slow, incomplete and may emit unparseable XML but is useful for
  human consumption.  Expects a `root` are returned by
  `clojure.xml.parse`.  Use `initial-indent` to set initial
  indentation string and `indent-str` to whatever is added as
  indentation."
  [root & {:keys [initial-indent
                  indent-str]
           :or   {initial-indent ""
                  indent-str     "  "}}]
  (debug-print-xml-node root initial-indent indent-str))

(defn single-xml-unwrapper
  "Find the content of the first child of `element` with type `tag`.

  Returns `nil` if no matching element is there"
  [element tag]
  (some-> element
          (get-in-dom [tag])
          (.getFirstChild)
          (.getTextContent)))
