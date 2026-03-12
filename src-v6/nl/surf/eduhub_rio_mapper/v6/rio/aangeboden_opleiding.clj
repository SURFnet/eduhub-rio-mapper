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

(ns nl.surf.eduhub-rio-mapper.v6.rio.aangeboden-opleiding
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.rio.helper :as rio-helper]
            [nl.surf.eduhub-rio-mapper.v6.specs.ooapi :as ooapi-v6]
            [nl.surf.eduhub-rio-mapper.v6.utils.ooapi :as ooapi-utils])
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

;; keys are ::ooapi/specification-type
(def specification-type-mapping
  {"course"         "aangebodenHOOpleidingsonderdeel"
   "cluster"        "aangebodenHOOpleidingsonderdeel"
   "programme"      "aangebodenHOOpleiding"
   "private"        "aangebodenParticuliereOpleiding"
   "variant"        "aangebodenHOOpleiding"})

(def ^:private mapping-course-program->aangeboden-opleiding
  {:buitenlandsePartner [:foreignPartners true]
   :onderwijsaanbiedercode [:educationOffererCode true]
   :onderwijslocatiecode [:educationLocationCode true]})

(def ^:private mapping-offering->cohort
  {:beginAanmeldperiode :enrollStartDate
   :deelnemersplaatsen :maxNumberStudents
   :einddatum :endDate
   :eindeAanmeldperiode :enrollEndDate
   :toelichtingVereisteToestemming :explanationRequiredPermission})

(defn- course-program-timeline-override-adapter
  [{:keys [name description validFrom abbreviation link consumer] :as _periode}]
  (let [{:keys [acceleratedRoute deficiency foreignPartners jointPartnerCodes propaedeuticPhase
                requirementsActivities studyChoiceCheck]} consumer]
    (fn [pk]
      (case pk
        :begindatum validFrom
        :buitenlandsePartner foreignPartners
        :deficientie (rio-helper/ooapi-mapping "deficientie" deficiency)
        :eigenNaamAangebodenOpleiding (ooapi-utils/get-localized-value name ["nl-NL" "nl"])
        :eigenNaamInternationaal (ooapi-utils/get-localized-value-exclusive name ["en"])
        :eigenNaamKort abbreviation
        :eigenOmschrijving (ooapi-utils/get-localized-value description ["nl-NL" "nl"])
        :eisenWerkzaamheden (rio-helper/ooapi-mapping "eisenWerkzaamheden" requirementsActivities)
        :internationaleNaamDuits (ooapi-utils/get-localized-value-exclusive name ["de"])
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
   "blended" "KLASSIKAAL_EN_ONLINE"
   "presential" "KLASSIKAAL"
   "lecture" "LEZING"
   "self_study" "ZELFSTUDIE"
   "coaching" "COACHING"})

(defn- lookup-consumer-mode-of-delivery [mode-of-delivery]
  (let [opleidingsvorm (get consumer-modeOfDelivery-mapping mode-of-delivery)]
    (when-not opleidingsvorm
      (throw (ex-info "modeOfDelivery cannot be mapped to RIO" {:modeOfDelivery mode-of-delivery})))
    opleidingsvorm)
  )

;; modeOfDelivery in rio-consumer of the offering has precedence over the one in the offering itself.
(defn- extract-opleidingsvorm [modeOfDelivery rio-consumer]
  (let [consumer-modeOfDelivery (:modeOfDelivery rio-consumer)
        mapped-values (if consumer-modeOfDelivery
                        (map lookup-consumer-mode-of-delivery consumer-modeOfDelivery)
                        (map #(rio-helper/ooapi-mapping "opleidingsvorm" %) modeOfDelivery))]
    (first (filter seq mapped-values))))

(defn- course-program-offering-adapter
  [{:keys [consumer startDate modeOfDelivery priceInformation
           flexibleEntryPeriodStart flexibleEntryPeriodEnd] :as offering}]
  (let [{:keys [registrationStatus requiredPermissionRegistration]} consumer]
    (fn [ck]
      (if-let [translation (mapping-offering->cohort ck)]
        (translation offering)
        (case ck
          :bedrijfsopleiding nil    ; ignored
          :cohortcode (-> offering :primaryCode :code)
          :cohortstatus (rio-helper/ooapi-mapping "cohortStatus" registrationStatus)
          :flexibeleInstroom (and flexibleEntryPeriodStart {:beginInstroomperiode flexibleEntryPeriodStart
                                                            :eindeInstroomperiode flexibleEntryPeriodEnd})
          :opleidingsvorm (extract-opleidingsvorm modeOfDelivery consumer)
          :prijs (mapv (fn [h] {:soort (rio-helper/ooapi-mapping "soort" (:costType h)) :bedrag (:amount h)})
                       priceInformation)
          :toestemmingVereistVoorAanmelding (rio-helper/ooapi-mapping "toestemmingVereistVoorAanmelding"
                                                                      requiredPermissionRegistration)
          :vastInstroommoment (when (nil? flexibleEntryPeriodStart) {:instroommoment startDate}))))))

(defn- course-program-adapter
  "Given a course or program, a rio-consumer object and an id, return a function.
   This function, given a attribute name from the RIO namespace, returns the corresponding value from the course or program,
   translated if necessary to the RIO domain."
  [{:keys [rioCode validFrom validTo offerings level modeOfStudy sector fieldsOfStudy consumer teachingLanguage timelineOverrides firstStartDateTime] :as course-program}
   opleidingscode
   ooapi-type]
  (let [duration-map (some-> consumer :duration parse-duration)
        id           ((if (= :course ooapi-type) :courseId :programmeId) course-program)
        periods      (map #(assoc (ooapi-type %)
                                  :validFrom (:validFrom %)
                                  :validTo   (:validTo %))
                          timelineOverrides)]
    (fn [k] {:pre [(keyword? k)]}
      (if-let [[translation consumer?] (mapping-course-program->aangeboden-opleiding k)]
        (if (ooapi-mapping? (name k))
          (rio-helper/ooapi-mapping (name k) (translation (if consumer? consumer course-program)))
          (translation (if consumer? consumer course-program)))
        (case k
          :opleidingseenheidSleutel opleidingscode
          ;; Required field. If found in the resolve phase, will be added to the entity under the rioCode key,
          ;; otherwise use the eigen sleutel value (an UUID).
          :aangebodenOpleidingCode (or rioCode id)
          ;; See opleidingseenheid for explanation of timelineOverrides and periods.
          :begindatum (first (sort (conj (map :validFrom timelineOverrides) validFrom)))
          :einddatum (last (sort (conj (map :validTo timelineOverrides) validTo)))
          :ISCED (rio-helper/narrow-isced fieldsOfStudy)
          :afwijkendeOpleidingsduur (when duration-map {:opleidingsduurEenheid (:eenheid duration-map)
                                                        :opleidingsduurOmvang  (:omvang duration-map)})
          :niveau (rio-helper/level-sector-mapping level sector)
          :vorm (rio-helper/ooapi-mapping "vorm" modeOfStudy)
          :voertaal (rio-helper/ooapi-mapping
                     "voertaal"
                     (or (:teachingLanguages consumer)
                         teachingLanguage))
          :eersteInstroomDatum (rio-helper/datetime->date firstStartDateTime)

          :cohorten (mapv #(course-program-offering-adapter %)
                          offerings)

          ;; See opleidingseenheid for explanation of timelineOverrides and periods.
          :periodes (->> (conj periods course-program)
                         (mapv #(course-program-timeline-override-adapter %)))

          ;; These are in the xsd but ignored by us
          :eigenAangebodenOpleidingSleutel (some-> id str/lower-case) ;; resolve to the ooapi id
          :laatsteInstroomdatum (:lastStartDate consumer)
          :opleidingserkenningSleutel nil
          :voVakerkenningSleutel nil)))))

(defn ->aangeboden-opleiding
  "Converts a program or course into the right kind of AangebodenOpleiding."
  [course-program ooapi-type opleidingscode {::ooapi-v6/keys [specification-type]}]
  {:pre [(string? specification-type)]}
  (-> (course-program-adapter course-program opleidingscode ooapi-type)
      rio-helper/wrapper-periodes-cohorten
      (rio-helper/->xml (specification-type-mapping specification-type))))
