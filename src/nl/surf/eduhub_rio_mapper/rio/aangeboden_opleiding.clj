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

(ns nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.rio.helper :as rio-helper]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.utils.ooapi :as ooapi-utils])
  (:import [java.time Period Duration]))

(defn- parse-duration [duration]
  (when duration
    (if (str/includes? duration "T")
      ;; If it contains a T, we treat it as a time period, and count in hours.
      (let [d (Duration/parse duration)]
        {:eenheid "U" :omvang (.toHours d)})
      (let [p (Period/parse duration)
            months (.getMonths p)]
        (cond
          ;; When less than a month, use days.
          (zero? (.toTotalMonths p))
          {:eenheid "D" :omvang (.getDays p)}

          ;; Whole number of years, use years.
          (zero? months)
          {:eenheid "J" :omvang (.getYears p)}

          ;; Otherwise use months.
          :else
          {:eenheid "M" :omvang (.toTotalMonths p)})))))

(defn ooapi-mapping? [name]
  (boolean (get-in rio-helper/specifications [:mappings name])))

(def education-specification-type-mapping
  {"course"         "aangebodenHOOpleidingsonderdeel"
   "cluster"        "aangebodenHOOpleidingsonderdeel"
   "program"        "aangebodenHOOpleiding"
   "privateProgram" "aangebodenParticuliereOpleiding"})

(def ^:private mapping-course-program->aangeboden-opleiding
  {:buitenlandsePartner [#(ooapi-utils/truncate (:foreignPartners %) 250) true]
   :eersteInstroomDatum [:firstStartDate false]
   :onderwijsaanbiedercode [:educationOffererCode true]
   :onderwijslocatiecode [:educationLocationCode true]
   :opleidingseenheidSleutel [::rio/opleidingscode false]
   :toestemmingDeelnameSTAP [:consentParticipationSTAP true]
   :voertaal [:teachingLanguage false]})

(def ^:private mapping-offering->cohort
  {:beginAanmeldperiode :enrollStartDate
   :deelnemersplaatsen :maxNumberStudents
   :einddatum :endDate
   :eindeAanmeldperiode :enrollEndDate
   :toelichtingVereisteToestemming #(-> % :explanationRequiredPermission (ooapi-utils/truncate 3000))})

(defn- course-program-timeline-override-adapter
  [{:keys [name description validFrom abbreviation link consumers] :as _periode}]
  (let [{:keys [acceleratedRoute deficiency foreignPartners jointPartnerCodes propaedeuticPhase
                requirementsActivities studyChoiceCheck]} (ooapi-utils/extract-rio-consumer consumers)]
    (fn [pk]
      (case pk
        :begindatum validFrom
        :buitenlandsePartner (ooapi-utils/truncate foreignPartners 250)
        :deficientie (rio-helper/ooapi-mapping "deficientie" deficiency)
        :eigenNaamAangebodenOpleiding (ooapi-utils/get-localized-value name ["nl-NL" "nl"] :maxlen 225)
        :eigenNaamInternationaal (ooapi-utils/get-localized-value-exclusive name ["en"] :maxlen 225)
        :eigenNaamKort (ooapi-utils/truncate abbreviation 40)
        :eigenOmschrijving (ooapi-utils/get-localized-value description ["nl-NL" "nl"] :maxlen 3000)
        :eisenWerkzaamheden (rio-helper/ooapi-mapping "eisenWerkzaamheden" requirementsActivities)
        :internationaleNaamDuits (ooapi-utils/get-localized-value-exclusive name ["de"] :maxlen 225)
        :propedeutischeFase (rio-helper/ooapi-mapping "propedeutischeFase" propaedeuticPhase)
        :samenwerkendeOnderwijsaanbiedercode jointPartnerCodes
        :studiekeuzecheck (rio-helper/ooapi-mapping "studiekeuzecheck" studyChoiceCheck)
        :versneldTraject (rio-helper/ooapi-mapping "versneldTraject" acceleratedRoute)
        :website link))))

;; Non-standard mapping for modeOfDelivery
;; See also https://github.com/open-education-api/specification/issues/295
(def consumer-modeOfDelivery-mapping
  {"online" "ONLINE"
   "hybrid" "KLASSIKAAL_EN_ONLINE"
   "situated" "KLASSIKAAL"
   "lecture" "LEZING"
   "self-study" "ZELFSTUDIE"
   "coaching" "COACHING"})

;; modeOfDelivery in rio-consumer of the offering has precedence over the one in the offering itself.
(defn- extract-opleidingsvorm [modeOfDelivery rio-consumer]
  (let [consumer-modeOfDelivery (:modeOfDelivery rio-consumer)
        mapped-values (if consumer-modeOfDelivery
                        (map consumer-modeOfDelivery-mapping consumer-modeOfDelivery)
                        (map #(rio-helper/ooapi-mapping "opleidingsvorm" %) modeOfDelivery))]
    (first (filter seq mapped-values))))

(defn- course-program-offering-adapter
  [{:keys [consumers startDate modeOfDelivery priceInformation
           flexibleEntryPeriodStart flexibleEntryPeriodEnd] :as offering}]
  (let [{:keys [registrationStatus requiredPermissionRegistration]
         :as   rio-consumer} (ooapi-utils/extract-rio-consumer consumers)]
    (fn [ck]
      (if-let [translation (mapping-offering->cohort ck)]
        (translation offering)
        (case ck
          :cohortcode (-> offering :primaryCode :code)
          :cohortstatus (rio-helper/ooapi-mapping "cohortStatus" registrationStatus)
          :opleidingsvorm (extract-opleidingsvorm modeOfDelivery rio-consumer)
          :toestemmingVereistVoorAanmelding (rio-helper/ooapi-mapping "toestemmingVereistVoorAanmelding"
                                                                      requiredPermissionRegistration)
          :bedrijfsopleiding nil    ; ignored
          :flexibeleInstroom (and flexibleEntryPeriodStart {:beginInstroomperiode flexibleEntryPeriodStart
                                                            :eindeInstroomperiode flexibleEntryPeriodEnd})
          :vastInstroommoment (when (nil? flexibleEntryPeriodStart) {:instroommoment startDate})
          :prijs (mapv (fn [h] {:soort (rio-helper/ooapi-mapping "soort" (:costType h)) :bedrag (:amount h)})
                       priceInformation))))))

(defn- course-program-adapter
  "Given a course or program, a rio-consumer object and an id, return a function.
   This function, given a attribute name from the RIO namespace, returns the corresponding value from the course or program,
   translated if necessary to the RIO domain."
  [{:keys [rioCode validFrom validTo offerings level modeOfStudy sector fieldsOfStudy consumers timelineOverrides] :as course-program}
   opleidingscode
   ooapi-type]
  (let [rio-consumer (ooapi-utils/extract-rio-consumer consumers)
        duration-map (some-> rio-consumer :duration parse-duration)
        id           ((if (= :course ooapi-type) :courseId :programId) course-program)
        periods      (map #(assoc (ooapi-type %)
                             :validFrom (:validFrom %)
                             :validTo   (:validTo %))
                          timelineOverrides)]
    (fn [k] {:pre [(keyword? k)]}
      (if (= k :opleidingseenheidSleutel)
        opleidingscode
        (if-let [[translation consumer] (mapping-course-program->aangeboden-opleiding k)]
          (if (ooapi-mapping? (name k))
            (rio-helper/ooapi-mapping (name k) (translation (if consumer rio-consumer course-program)))
            (translation (if consumer rio-consumer course-program)))
          (case k
            ;; Required field. If found in the resolve phase, will be added to the entity under the rioCode key,
            ;; otherwise use the eigen sleutel value (an UUID).
            :aangebodenOpleidingCode (or rioCode id)
            ;; See opleidingseenheid for explanation of timelineOverrides and periods.
            :begindatum (first (sort (conj (map :validFrom timelineOverrides) validFrom)))
            :einddatum (last (sort (conj (map :validTo timelineOverrides) validTo)))
            :ISCED (rio-helper/narrow-isced fieldsOfStudy)
            :afwijkendeOpleidingsduur (when duration-map {:opleidingsduurEenheid (:eenheid duration-map)
                                                          :opleidingsduurOmvang (:omvang duration-map)})
            :niveau (rio-helper/level-sector-mapping level sector)
            :vorm (rio-helper/ooapi-mapping "vorm" modeOfStudy)

            :cohorten (mapv #(course-program-offering-adapter %)
                            offerings)

            ;; See opleidingseenheid for explanation of timelineOverrides and periods.
            :periodes (->> (conj periods course-program)
                           (mapv #(course-program-timeline-override-adapter %)))

            ;; These are in the xsd but ignored by us
            :eigenAangebodenOpleidingSleutel (some-> id str/lower-case) ;; resolve to the ooapi id
            :opleidingserkenningSleutel nil
            :voVakerkenningSleutel nil))))))

(defn ->aangeboden-opleiding
  "Converts a program or course into the right kind of AangebodenOpleiding."
  [course-program ooapi-type opleidingscode education-specification-type]
  (-> (course-program-adapter course-program opleidingscode ooapi-type)
      rio-helper/wrapper-periodes-cohorten
      (rio-helper/->xml (education-specification-type-mapping education-specification-type))))
