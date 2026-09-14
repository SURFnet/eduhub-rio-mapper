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

(ns nl.surf.eduhub-rio-mapper.rio.conversion
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.rio.helper :as rio-helper]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils])
  (:import [org.w3c.dom Element Node]))

(defn- strip-duo [kw]
  (-> kw
      name
      (str/replace #"^duo:" "")))

(defn- duo-keyword [x]
  (keyword (str "duo:" (name x))))

(defn- rio-obj-name-in-set? [rio-obj name-set]
  (let [rio-obj-name (-> rio-obj first strip-duo keyword)]
    (some? (name-set rio-obj-name))))

(defn aangeboden-opleiding? [rio-obj]
  (rio-obj-name-in-set? rio-obj rio.loader/aangeboden-opleiding-namen))

(defn- dom->duo-hiccup [^Element element]
  (let [children (.getChildNodes element)]
    (into [(duo-keyword (.getLocalName element))]
          (keep (fn [i]
                  (let [^Node child (.item children i)]
                    (case (.getNodeType child)
                      1 (dom->duo-hiccup child)
                      3 (.getNodeValue child)
                      4 (.getNodeValue child)
                      nil))))
          (range (.getLength children)))))

(def ^:private kenmerk-value-tags
  #{:duo:kenmerkwaardeTekst :duo:kenmerkwaardeDatum
    :duo:kenmerkwaardeEnumeratiewaarde :duo:kenmerkwaardeGetal
    :duo:kenmerkwaardeBoolean})

(defn- kenmerk? [element naam]
  (and (xml-utils/sexp-element-with-tag? element :duo:kenmerken)
       (= naam (xml-utils/sexp-child-text element :duo:kenmerknaam))))

(defn- attribute-adapter [rio-obj k]
  (some (fn [element]
          (cond
            (xml-utils/sexp-element-with-tag? element (duo-keyword k))
            (xml-utils/sexp-element-text element)

            (kenmerk? element (name k))
            (some #(when (kenmerk-value-tags (first %))
                     (or (xml-utils/sexp-element-text %) ""))
                  (xml-utils/sexp-child-elements element))))
        (xml-utils/sexp-child-elements rio-obj)))

(declare link-item-adapter)

(defn- child-adapter [rio-obj k]
  (->> (xml-utils/sexp-child-elements rio-obj)
       (filter #(xml-utils/sexp-element-with-tag? % (duo-keyword k)))
       (map #(partial link-item-adapter %))))

;; Turns <prijs><soort>s</soort><bedrag>123</bedrag></prijs> into {:soort "s", bedrag 123}
(defn- nested-adapter [rio-obj k]
  (keep #(when (xml-utils/sexp-element-with-tag? % (duo-keyword k))
           (into {} (map (fn [child]
                           [(keyword (strip-duo (first child))) (xml-utils/sexp-element-text child)]))
                 (xml-utils/sexp-child-elements %)))
        (xml-utils/sexp-child-elements rio-obj)))

;; These attributes have nested elements, e.g.:
;; <prijs>
;;   <bedrag>99.50</bedrag>
;;   <soort>collegegeld</soort>
;; </prijs
(def ^:private attributes-with-children #{:vastInstroommoment :prijs :flexibeleInstroom})

(defn- link-item-adapter [rio-obj k]
  (if (string? k)
    (child-adapter rio-obj k)         ; If k is a string, it refers to a nested type: Periode or Cohort.
    (if (attributes-with-children k)  ; These attributes are the only ones with child elements.
      (vec (nested-adapter rio-obj k))
      ; The common case is handling attributes.
      (attribute-adapter rio-obj k))))

(defn parse-rio-obj
  "Parse a raadplegen XML response into a RIO object in duo hiccup format."
  [response-body rio-type]
  {:pre [(#{:oe :ao} rio-type)]}
  (let [name-set (if (= :oe rio-type)
                   rio.loader/opleidingseenheid-namen
                   rio.loader/aangeboden-opleiding-namen)
        elements (.getElementsByTagNameNS (xml-utils/str->dom response-body) "*" "*")
        rio-obj (or (some (fn [i]
                            (let [^Element element (.item elements i)]
                              (when (name-set (keyword (.getLocalName element)))
                                element)))
                          (range (.getLength elements)))
                    (throw (ex-info "404 Not Found" {:phase :resolving})))]
    (dom->duo-hiccup rio-obj)))

(defn- eigen-sleutel-name [rio-type]
  (case rio-type
    :oe "eigenOpleidingseenheidSleutel"
    :ao "eigenAangebodenOpleidingSleutel"))

(defn- eigen-sleutel-value [element rio-type]
  (when (kenmerk? element (eigen-sleutel-name rio-type))
    (xml-utils/sexp-child-text element :duo:kenmerkwaardeTekst)))

(defn set-eigen-sleutel
  "Insert or replace the eigen sleutel in a parsed RIO object; nil removes it.
  The beheren serializer determines the final XML element order."
  [rio-obj rio-type value]
  {:pre [(#{:oe :ao} rio-type) (or (nil? value) (string? value))]}
  (let [naam (eigen-sleutel-name rio-type)
        without-key (into [(first rio-obj)]
                          (remove #(kenmerk? % naam))
                          (rest rio-obj))]
    (cond-> without-key
      (some? value) (conj [:duo:kenmerken
                          [:duo:kenmerknaam naam]
                          [:duo:kenmerkwaardeTekst value]]))))

(defn eigen-sleutel
  "Read the eigen sleutel from a parsed RIO object (duo hiccup), or nil if absent.
  Does not fetch or convert the object to beheren format."
  [rio-obj rio-type]
  {:pre [(#{:oe :ao} rio-type)]}
  (some #(eigen-sleutel-value % rio-type) rio-obj))

(defn- adjust-beheren-fields
  "Adapt raadplegen field names and references to the beheren representation."
  [rio-obj]
  (let [aangeboden? (aangeboden-opleiding? rio-obj)]
    (into [(first rio-obj)]
          (comp
           ;; Beheren expects opleidingseenheidSleutel on aangeboden opleidingen.
           (map #(if (and aangeboden?
                          (xml-utils/sexp-element-with-tag? % :duo:opleidingseenheidcode))
                   (assoc % 0 :duo:opleidingseenheidSleutel)
                   %))
           ;; Beheren does not allow both an opleidingseenheid reference and
           ;; an opleidingserkenningSleutel.
           (remove #(xml-utils/sexp-element-with-tag? % :duo:opleidingserkenningSleutel)))
          (rest rio-obj))))

(defn rio-obj-raadplegen->beheren
  "Convert a raadplegen XML response to {:rio-sexp [object] :old-id eigen-sleutel}.
  rio-type is :oe (opleidingseenheid) or :ao (aangeboden opleiding).
  :rio-sexp always contains one beheren object, ready for the mutator.
  Omit :change-key to preserve the key; supply nil to remove it."
  [response-body rio-type & {:keys [change-key] :as opts}]
  (let [rio-obj (parse-rio-obj response-body rio-type)
        old-id (eigen-sleutel rio-obj rio-type)
        rio-obj (if (contains? opts :change-key)
                  (set-eigen-sleutel rio-obj rio-type change-key)
                  rio-obj)
        rio-obj (adjust-beheren-fields rio-obj)
        rio-new (rio-helper/->xml (partial link-item-adapter rio-obj)
                                 (-> rio-obj first strip-duo))]
    {:rio-sexp [rio-new]
     :old-id old-id}))
