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

(ns nl.surf.eduhub-rio-mapper.v6.commands.dry-run
  (:require [nl.surf.eduhub-rio-mapper.rio.helper :as rio.helper]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils]
            [nl.surf.eduhub-rio-mapper.v6.rio.aangeboden-opleiding :as aangeboden-opleiding]
            [nl.surf.eduhub-rio-mapper.v6.utils.ooapi :as ooapi-utils])
  (:import [java.time LocalDate]
           [org.w3c.dom Element Node]))

(def aangeboden-opleiding-namen (->> aangeboden-opleiding/specification-type-mapping
                                     vals
                                     (map keyword)
                                     set))
(def cohortnamen (->> aangeboden-opleiding-namen
                      (map name)
                      (map #(str % "Cohort"))
                      (map keyword)
                      set))

(defn generate-diff-ooapi-rio [& {:keys [rio-summary ooapi-summary]}]
  (reduce (fn [h k]
            (assoc h k
                   (if (= (k rio-summary) (k ooapi-summary))
                     {:diff false}
                     {:diff     true
                      :current  (k rio-summary)
                      :proposed (k ooapi-summary)})))
          {}
          (into (set (keys ooapi-summary)) (keys rio-summary))))

(def dutch-locales ["nl-NL" "nl"])
(def opleidingseenheidperiode-namen #{:hoOpleidingPeriode :particuliereOpleidingPeriode :hoOnderwijseenhedenclusterPeriode :hoOnderwijseenheidPeriode})

(def aangeboden-opleidingperiode-namen
  (->> aangeboden-opleiding-namen
       (map name)
       (map #(str % "Periode"))
       (map keyword)
       set))

(def opleidingseenheid-summary-attributes
  #{:begindatum :einddatum :naamKort :naamLang :omschrijving :internationaleNaam})

(def aangeboden-opleiding-summary-attributes
  #{:begindatum :onderwijsaanbiedercode :onderwijslocatiecode :einddatum :eigenNaamKort :eigenNaamAangebodenOpleiding :eigenNaamInternationaal :eigenOmschrijving})

(defn summarize-opleidingseenheid
  "Summarize the current period and identifying key of a RIO DOM element, or nil."
  [^Element rio-obj]
  (when rio-obj
    (let [element-text   (fn [^Element element]
                           (when (and element (.hasChildNodes element))
                             (.getTextContent element)))
          current-period (->> (.getElementsByTagNameNS rio-obj rio.loader/schema "*")
                              xml-utils/node-list->seq
                              (filter #(opleidingseenheidperiode-namen
                                        (keyword (.getLocalName ^Element %))))
                              (map (fn [^Element period]
                                     (into {}
                                           (keep (fn [^Node child]
                                                   (let [tag (some-> (.getLocalName child) keyword)]
                                                     (when (and (= rio.loader/schema (.getNamespaceURI child))
                                                                (opleidingseenheid-summary-attributes tag))
                                                       [tag (element-text child)]))))
                                           (xml-utils/node-list->seq (.getChildNodes period)))))
                              (filter #(neg? (compare (:begindatum %) (.format ooapi-utils/date-format (LocalDate/now)))))
                              (sort-by :begindatum)
                              last)
          ooapi-id       (some (fn [kenmerk]
                                (when (= "eigenOpleidingseenheidSleutel"
                                         (element-text (xml-utils/get-in-dom kenmerk rio.loader/schema ["kenmerknaam"])))
                                  (element-text (xml-utils/get-in-dom kenmerk rio.loader/schema ["kenmerkwaardeTekst"]))))
                              (xml-utils/node-list->seq
                               (.getElementsByTagNameNS rio-obj rio.loader/schema "kenmerken")))]
      (assoc current-period :eigenOpleidingseenheidSleutel ooapi-id))))

(defn summarize-prgspec [prgspec]
  (let [current-period (ooapi-utils/current-period (ooapi-utils/ooapi-to-periods prgspec :programme) :validFrom)]
    {:begindatum                    (-> current-period :validFrom rio.helper/datetime->date),
     :naamLang                      (ooapi-utils/get-localized-value (:name current-period) dutch-locales),
     :naamKort                      (:abbreviation current-period),
     :internationaleNaam            (ooapi-utils/get-localized-value (:name current-period)),
     :omschrijving                  (ooapi-utils/get-localized-value (:description current-period) dutch-locales),
     :eigenOpleidingseenheidSleutel (:programmeId prgspec)}))

(defn summarize-aangeboden-opleiding
  "Summarize the current period, provider, location and cohorts of a RIO DOM element, or nil."
  [^Element rio-obj]
  (when rio-obj
    (let [element-text   (fn [^Element element]
                           (when (and element (.hasChildNodes element))
                             (.getTextContent element)))
          descendants    (xml-utils/node-list->seq
                          (.getElementsByTagNameNS rio-obj rio.loader/schema "*"))
          current-period (->> descendants
                              (filter #(aangeboden-opleidingperiode-namen
                                        (keyword (.getLocalName ^Element %))))
                              (map (fn [^Element period]
                                     (into {}
                                           (keep (fn [^Node child]
                                                   (let [tag (some-> (.getLocalName child) keyword)]
                                                     (when (and (= rio.loader/schema (.getNamespaceURI child))
                                                                (aangeboden-opleiding-summary-attributes tag))
                                                       [tag (element-text child)]))))
                                           (xml-utils/node-list->seq (.getChildNodes period)))))
                              (filter #(neg? (compare (:begindatum %) (.format ooapi-utils/date-format (LocalDate/now)))))
                              (sort-by :begindatum)
                              last)
          finder         #(element-text (xml-utils/get-in-dom rio-obj rio.loader/schema [(name %)]))
          key-list       [:onderwijsaanbiedercode :onderwijslocatiecode]
          rio-summary    (zipmap key-list (map finder key-list))
          cohorten       (filter #(cohortnamen (keyword (.getLocalName ^Element %)))
                                 descendants)]
      (assoc (merge current-period rio-summary)
             :cohorten (->> cohorten
                            (mapv (fn [cohort]
                                    (let [keys   [:cohortcode :beginAanmeldperiode :eindeAanmeldperiode]
                                          finder #(element-text (xml-utils/get-in-dom cohort rio.loader/schema [(name %)]))]
                                      (zipmap keys (map finder keys)))))
                            (sort-by :cohortcode)
                            vec)))))

(defn summarize-course-program [course-program]
  (let [ooapi-type (if (:courseId course-program) :course :programme)
        current-period (ooapi-utils/current-period (ooapi-utils/ooapi-to-periods course-program ooapi-type) :validFrom)
        consumer (:consumer course-program)]
    {:begindatum                   (-> current-period :validFrom rio.helper/datetime->date)
     :onderwijsaanbiedercode       (:educationOffererCode consumer)
     :onderwijslocatiecode         (:educationLocationCode consumer)
     :eigenNaamAangebodenOpleiding (-> current-period
                                       :name
                                       (ooapi-utils/get-localized-value dutch-locales))
     :eigenNaamInternationaal      (-> current-period
                                       :name
                                       (ooapi-utils/get-localized-value))
     :eigenOmschrijving            (-> current-period
                                       :description
                                       (ooapi-utils/get-localized-value dutch-locales))
     :cohorten                     (-> course-program :offerings)}))

(defn summarize-offering [offering]
  (let [period (-> (:enrolmentPeriods offering) first)]
    {:cohortcode (-> offering :primaryCode :code)
     :beginAanmeldperiode (rio.helper/datetime->date (:startDateTime period))
     :eindeAanmeldperiode (rio.helper/datetime->date (:endDateTime period))}))
