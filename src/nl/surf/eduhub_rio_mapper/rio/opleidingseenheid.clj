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

(ns nl.surf.eduhub-rio-mapper.rio.opleidingseenheid
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.rio.helper :as rio-helper]
            [nl.surf.eduhub-rio-mapper.utils.ooapi :as ooapi-utils]))

(def ^:private education-specification-type-mapping
  {"course"         "hoOnderwijseenheid"
   "program"        "hoOpleiding"
   "privateProgram" "particuliereOpleiding"
   "cluster"        "hoOnderwijseenhedencluster"})

(defn- program-subtype-mapping [consumers]
  (when-let [rio-consumer (some->> consumers (filter #(= (:consumerKey %) "rio")) first)]
    (when (= "variant" (:educationSpecificationSubType rio-consumer)) "VARIANT")))

(defn- soort-mapping [{:keys [educationSpecificationType consumers]}]
  (case educationSpecificationType
    "cluster" "HOEC"
    "program" (or (program-subtype-mapping consumers) "OPLEIDING")
    nil))

(defn- education-specification-timeline-override-adapter
  [{:keys [abbreviation description formalDocument name studyLoad validFrom] :as eduspec}]
  (fn [pk]
    (case pk
      :begindatum validFrom
      :internationaleNaam (ooapi-utils/get-localized-value-exclusive name ["en"] :maxlen 225)
      :naamKort (ooapi-utils/truncate abbreviation 40)
      :naamLang (ooapi-utils/get-localized-value name ["nl-NL" "nl"] :maxlen 225)
      :omschrijving (ooapi-utils/get-localized-value description ["nl-NL" "nl"] :maxlen 3000)
      :studielast (if (= "VARIANT" (soort-mapping eduspec)) nil (:value studyLoad))
      :studielasteenheid (rio-helper/ooapi-mapping "studielasteenheid" (:studyLoadUnit studyLoad))
      :waardedocumentsoort (rio-helper/ooapi-mapping "waardedocumentsoort" formalDocument))))

(def ^:private mapping-eduspec->opleidingseenheid
  {:eigenOpleidingseenheidSleutel #(some-> % :educationSpecificationId str/lower-case)
   :opleidingseenheidcode         :rioCode})

(defn- education-specification-adapter
  [{:keys [validFrom validTo formalDocument level levelOfQualification sector fieldsOfStudy timelineOverrides] :as eduspec}
   {:keys [category] :as _rio-consumer}]
  (fn [opl-eenh-attr-name]
    (let [periods     (ooapi-utils/ooapi-to-periods eduspec :educationSpecification)
          translation (mapping-eduspec->opleidingseenheid opl-eenh-attr-name)]
      (if translation
        (translation eduspec)
        (case opl-eenh-attr-name
          ;; The main education specification object represents the current situation, while the timelineOverrides
          ;; specify past and future states. However, in RIO's opleidingseenheid, the main object's begindatum and
          ;; einddatum represent the entire lifespan of an opleidingseenheid, while its periodes represent each
          ;; temporary state. Therefore, we calculate the lifespan of an opleidingseenheid below.
          :begindatum (first (sort (conj (map :validFrom timelineOverrides) validFrom)))
          :einddatum (last (sort (conj (map :validTo timelineOverrides) validTo)))
          :ISCED (rio-helper/narrow-isced fieldsOfStudy)
          :categorie (rio-helper/ooapi-mapping "categorie" category)
          :eqf (rio-helper/ooapi-mapping "eqf" levelOfQualification)
          :niveau (rio-helper/level-sector-mapping level sector)
          :nlqf (rio-helper/ooapi-mapping "nlqf" levelOfQualification)
          ;; eduspec itself is used to represent the main object without adaptations from timelineOverrides.
          :periodes (mapv education-specification-timeline-override-adapter periods)
          :soort (soort-mapping eduspec)
          :waardedocumentsoort (rio-helper/ooapi-mapping "waardedocumentsoort" formalDocument))))))

(defn education-specification->opleidingseenheid
  "Converts a education specification into the right kind of Opleidingseenheid."
  [eduspec]
  (-> (education-specification-adapter eduspec (ooapi-utils/extract-rio-consumer (:consumers eduspec)))
      rio-helper/wrapper-periodes-cohorten
      (rio-helper/->xml (education-specification-type-mapping (:educationSpecificationType eduspec)))))
