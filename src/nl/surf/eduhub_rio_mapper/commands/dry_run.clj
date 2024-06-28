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

(ns nl.surf.eduhub-rio-mapper.commands.dry-run
  (:require [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding :as aangeboden-opleiding]
            [nl.surf.eduhub-rio-mapper.utils.ooapi :as ooapi-utils]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils]))

(def aangeboden-opleiding-namen (->> aangeboden-opleiding/education-specification-type-mapping
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

(defn extract-period-summary [xmlseq attributes]
  (->> (:content xmlseq)
       (filter #(attributes (:tag %)))
       (map (fn [e] {(:tag e) (-> e :content first)}))
       (into {})))

(def opleidingseenheid-summary-attributes
  #{:begindatum :einddatum :naamKort :naamLang :omschrijving :internationaleNaam})

(def aangeboden-opleiding-summary-attributes
  #{:begindatum :onderwijsaanbiedercode :onderwijslocatiecode :einddatum :eigenNaamKort :eigenNaamAangebodenOpleiding :eigenNaamInternationaal :eigenOmschrijving})

(defn- kenmerk-content [xmlspec naam kenmerk-type]
  (xml-utils/find-in-xmlseq
    xmlspec
    #(let [[n v] (:content %)]
       (and (= :kenmerken (:tag %))
            (= :kenmerknaam (:tag n))
            (= naam (first (:content n)))
            (= kenmerk-type (:tag v))
            (first (:content v))))))

(defn summarize-eduspec [eduspec]
  (let [current-period (ooapi-utils/current-period (ooapi-utils/ooapi-to-periods eduspec :educationSpecification) :validFrom)]
    {:begindatum                    (:validFrom current-period),
     :naamLang                      (ooapi-utils/get-localized-value (:name current-period) dutch-locales),
     :naamKort                      (:abbreviation current-period),
     :internationaleNaam            (ooapi-utils/get-localized-value (:name current-period) []),
     :omschrijving                  (ooapi-utils/get-localized-value (:description current-period) dutch-locales),
     :eigenOpleidingseenheidSleutel (:educationSpecificationId eduspec)}))

(defn summarize-course-program [course-program]
  (let [ooapi-type (if (:courseId course-program) :course :program)
        current-period (ooapi-utils/current-period (ooapi-utils/ooapi-to-periods course-program ooapi-type) :validFrom)
        consumer (->> course-program
                      :consumers
                      (filter #(= "rio" (:consumerKey %)))
                      first)]
    {:begindatum                   (:validFrom current-period)
     :onderwijsaanbiedercode       (:educationOffererCode consumer)
     :onderwijslocatiecode         (:educationLocationCode consumer)
     :eigenNaamAangebodenOpleiding (-> current-period
                                       :name
                                       (ooapi-utils/get-localized-value dutch-locales))
     :eigenNaamInternationaal      (-> current-period
                                       :name
                                       (ooapi-utils/get-localized-value []))
     :eigenOmschrijving            (-> current-period
                                       :description
                                       (ooapi-utils/get-localized-value dutch-locales))
     :cohorten                     (-> course-program :offerings)}))

(defn- summarize-cohort-xml [cohort-xml-seq]
  (let [keys [:cohortcode :beginAanmeldperiode :eindeAanmeldperiode]
        finder #(xml-utils/find-content-in-xmlseq cohort-xml-seq %)]
    (zipmap keys (map finder keys))))

(defn summarize-offering [offering]
  {:cohortcode (-> offering :primaryCode :code)
   :beginAanmeldperiode (:enrollStartDate offering)
   :eindeAanmeldperiode (:enrollEndDate offering)})

(defn summarize-opleidingseenheid [opleidingseenheid]
  (let [periods     (xml-utils/find-all-in-xmlseq (xml-seq opleidingseenheid)
                                                  #(and (opleidingseenheidperiode-namen (:tag %))
                                                        %))
        period-data (map #(extract-period-summary % opleidingseenheid-summary-attributes) periods)
        current-period (ooapi-utils/current-period period-data :begindatum)
        ooapi-id (kenmerk-content (xml-seq opleidingseenheid) "eigenOpleidingseenheidSleutel" :kenmerkwaardeTekst)]
    (assoc current-period
      :eigenOpleidingseenheidSleutel ooapi-id)))

(defn summarize-aangebodenopleiding-xml [rio-obj]
  (when rio-obj
    (let [periods     (xml-utils/find-all-in-xmlseq (xml-seq rio-obj)
                                                    #(and (aangeboden-opleidingperiode-namen (:tag %))
                                                          %))
          period-data (map #(extract-period-summary % aangeboden-opleiding-summary-attributes) periods)
          current-period (ooapi-utils/current-period period-data :begindatum)

          finder      (fn [k] (xml-utils/find-in-xmlseq (xml-seq rio-obj)
                                                        #(and (= k (:tag %))
                                                              (-> % :content first))))
          key-list    [:onderwijsaanbiedercode :onderwijslocatiecode]
          rio-summary (reduce (fn [m k] (assoc m k (finder k))) {} key-list)
          cohorten    (xml-utils/find-all-in-xmlseq (xml-seq rio-obj)
                                                    #(when (contains? cohortnamen (:tag %)) %))]
      (assoc (merge current-period rio-summary)
        :cohorten (->> cohorten
                       (mapv (comp summarize-cohort-xml xml-seq))
                       (sort-by :cohortcode)
                       vec)))))
