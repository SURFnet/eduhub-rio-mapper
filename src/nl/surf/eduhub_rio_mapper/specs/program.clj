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

(ns nl.surf.eduhub-rio-mapper.specs.program
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.enums :as enums]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec text-spec]]
            [nl.surf.eduhub-rio-mapper.specs.common :as common]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]))

(s/def ::abbreviation
  (text-spec 1 40))

(s/def ::acceleratedRoute enums/acceleratedRoute)
(s/def ::children (s/coll-of ::common/uuid))
(s/def ::deficiency enums/deficiency)
(s/def ::description ::common/LongLanguageTypedStrings)
(s/def ::educationLocationCode string?)
(s/def ::educationSpecification ::common/uuid)
(s/def ::educationUnitCode ::rio/OpleidingsEenheidID-v01)
(s/def ::jointProgram boolean?)
(s/def ::firstStartDate ::common/date)
(s/def ::foreignPartner string?)
(s/def ::foreignPartners (s/coll-of ::foreignPartner))
(s/def ::jointPartnerCode (text-spec 1 1000))
(s/def ::jointPartnerCodes (s/coll-of ::jointPartnerCode))
(s/def ::link string?)
(s/def ::modeOfStudy enums/modeOfStudy)
(s/def ::name ::common/LanguageTypedStrings)
(s/def ::parent string?)
(s/def ::primaryCode ::common/codeTuple)
(s/def ::programId ::common/uuid)
(s/def ::programType enums/programType)
(s/def ::propaedeuticPhase enums/propaedeuticPhase)
(s/def ::requirementsActivities enums/requirementsActivities)
(s/def ::studyChoiceCheck enums/studyChoiceCheck)
(s/def ::teachingLanguage (re-spec #"[a-z]{3}"))
(s/def ::validFrom ::common/date)
(s/def ::validTo ::common/date)

(s/def ::program-consumer
  (s/keys :req-un [::common/educationOffererCode]
          :opt-un [::acceleratedRoute
                   ::educationLocationCode
                   ::educationUnitCode
                   ::foreignPartners
                   ::jointPartnerCodes
                   ::jointProgram
                   ::propaedeuticPhase
                   ::requirementsActivities
                   ::studyChoiceCheck
                   ::common/level
                   ::common/sector
                   ::common/levelOfQualification]))


(s/def ::rio-consumer
  (s/merge ::common/rio-consumer
           ::program-consumer))

;; must have at least one rio consumer
(s/def ::consumers
  (s/with-gen
    (s/and
      not-empty                                             ; added to improve explain error message
      (s/cat :head (s/* ::common/consumer)
             :rio ::rio-consumer
             :tail (s/* ::common/consumer)))
    #(s/gen (s/cat :head (s/* ::common/consumer)
                   :rio ::rio-consumer
                   :tail (s/* ::common/consumer)))))

(s/def ::program
  (s/keys :req-un [::programId
                   ::consumers
                   ::name
                   ::validFrom]
          :opt-un [::abbreviation
                   ::children
                   ::description
                   ::common/duration
                   ::educationSpecification
                   ::link
                   ::modeOfStudy
                   ::parent
                   ::teachingLanguage
                   ::common/addresses
                   ::common/fieldsOfStudy
                   ::common/learningOutcomes
                   ::common/otherCodes
                   ::common/studyLoad]))

;; extract attribute vector from specs for use in spec helper
(def program-req-attrs (common/extract-req-attrs ::program))
(def program-opt-attrs (common/extract-opt-attrs ::program))
(def program-consumer-req-attrs (common/extract-req-attrs ::program-consumer))
(def program-consumer-opt-attrs (common/extract-opt-attrs ::program-consumer))

(s/def ::ProgramType
  (s/keys :req-un [::modeOfStudy]))

(s/def ::ProgramConsumerType
  (s/keys :req-un [::propaedeuticPhase
                   ::studyChoiceCheck]
          :opt-un [::educationUnitCode
                   ::jointProgram]))
