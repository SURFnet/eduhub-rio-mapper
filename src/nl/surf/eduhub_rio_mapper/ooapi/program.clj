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

(ns nl.surf.eduhub-rio-mapper.ooapi.program
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.ooapi.enums :as enums]
            [nl.surf.eduhub-rio-mapper.ooapi.Program :as-alias Program]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec text-spec]]))

(s/def ::Program/abbreviation
  (text-spec 1 40))

(s/def ::Program/acceleratedRoute enums/acceleratedRoute)
(s/def ::Program/admissionRequirements ::common/LanguageTypedStrings)
(s/def ::Program/assessment ::common/LanguageTypedStrings)
(s/def ::Program/children (s/coll-of ::common/uuid))
(s/def ::Program/consentParticipationSTAP enums/consentParticipationSTAP)
(s/def ::Program/coordinators (s/coll-of string?))
(s/def ::Program/deficiency enums/deficiency)
(s/def ::Program/description ::common/LongLanguageTypedStrings)
(s/def ::Program/educationLocationCode string?)
(s/def ::Program/educationSpecification ::common/uuid)
(s/def ::Program/enrollment ::common/LanguageTypedStrings)
(s/def ::Program/firstStartDate ::common/date)
(s/def ::Program/foreignPartner string?)
(s/def ::Program/foreignPartners (s/coll-of ::Program/foreignPartner))
(s/def ::Program/jointPartnerCode (text-spec 1 1000))
(s/def ::Program/jointPartnerCodes (s/coll-of ::Program/jointPartnerCode))
(s/def ::Program/link string?)
(s/def ::Program/modeOfStudy enums/modeOfStudy)
(s/def ::Program/name ::common/LanguageTypedStrings)
(s/def ::Program/organization string?)
(s/def ::Program/parent string?)
(s/def ::Program/primaryCode ::common/codeTuple)
(s/def ::Program/programId ::common/uuid)
(s/def ::Program/programType enums/programType)
(s/def ::Program/propaedeuticPhase enums/propaedeuticPhase)
(s/def ::Program/qualificationAwarded string?)
(s/def ::Program/qualificationRequirements ::common/LanguageTypedStrings)
(s/def ::Program/requirementsActivities enums/requirementsActivities)
(s/def ::Program/resources (s/coll-of string?))
(s/def ::Program/studyChoiceCheck enums/studyChoiceCheck)
(s/def ::Program/teachingLanguage (re-spec #"[a-z]{3}"))
(s/def ::Program/validFrom ::common/date)
(s/def ::Program/validTo ::common/date)

(s/def ::Program/rio-consumer
  (s/merge ::common/rio-consumer
           (s/keys :req-un [::Program/consentParticipationSTAP
                            ::common/educationOffererCode]
                   :opt-un [::Program/acceleratedRoute
                            ::Program/educationLocationCode
                            ::Program/consentParticipationSTAP
                            ::Program/foreignPartners
                            ::Program/jointPartnerCodes
                            ::Program/propaedeuticPhase
                            ::Program/requirementsActivities
                            ::Program/studyChoiceCheck
                            ::common/level
                            ::common/sector
                            ::common/levelOfQualification])))

;; must have at least one rio consumer
(s/def ::Program/consumers
  (s/cat :head (s/* ::common/consumer)
         :rio ::Program/rio-consumer
         :tail (s/* ::common/consumer)))

(s/def ::Program
  (s/keys :req-un [::Program/programId
                   ::Program/consumers
                   ::Program/educationSpecification
                   ::Program/validFrom]
          :opt-un [::Program/abbreviation
                   ::Program/admissionRequirements
                   ::Program/assessment
                   ::Program/children
                   ::Program/coordinators
                   ::Program/description
                   ::common/duration
                   ::Program/enrollment
                   ::Program/link
                   ::Program/modeOfStudy
                   ::Program/name
                   ::Program/organization
                   ::Program/parent
                   ::Program/qualificationAwarded
                   ::Program/qualificationRequirements
                   ::Program/resources
                   ::Program/teachingLanguage
                   ::common/addresses
                   ::common/fieldsOfStudy
                   ::common/learningOutcomes
                   ::common/otherCodes
                   ::common/studyLoad]))

(s/def ::ProgramType
  (s/keys :req-un [::Program/modeOfStudy]))

(s/def ::ProgramConsumerType
  (s/keys :req-un [::Program/propaedeuticPhase
                   ::Program/studyChoiceCheck]))
