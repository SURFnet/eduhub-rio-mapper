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

(ns nl.surf.eduhub-rio-mapper.ooapi.course
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.ooapi.Course :as-alias Course]
            [nl.surf.eduhub-rio-mapper.ooapi.enums :as enums]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [text-spec]]))

(s/def ::Course/abbreviation string?)
(s/def ::Course/consentParticipationSTAP enums/consentParticipationSTAP)
(s/def ::Course/courseId ::common/uuid)
(s/def ::Course/description ::common/LongLanguageTypedStrings)
(s/def ::Course/educationLocationCode string?)
(s/def ::Course/educationSpecification ::common/uuid)
(s/def ::Course/firstStartDate ::common/date)
(s/def ::Course/foreignPartner string?)
(s/def ::Course/foreignPartners (s/coll-of ::Course/foreignPartner))
(s/def ::Course/jointPartnerCode (text-spec 1 1000))
(s/def ::Course/jointPartnerCodes (s/coll-of ::Course/jointPartnerCode))
(s/def ::Course/link string?)
(s/def ::Course/name ::common/LanguageTypedStrings)
(s/def ::Course/teachingLanguage string?)
(s/def ::Course/validFrom ::common/date)
(s/def ::Course/validTo ::common/date)

(s/def ::Course/rio-consumer
  (s/merge ::common/rio-consumer
           (s/keys :req-un [::Course/consentParticipationSTAP
                            ::common/educationOffererCode]
                   :opt-un [::Course/educationLocationCode
                            ::Course/foreignPartners
                            ::Course/jointPartnerCodes])))

;; must have at least one rio consumer
(s/def ::Course/consumers
  (s/cat :head (s/* ::common/consumer)
         :rio ::Course/rio-consumer
         :tail (s/* ::common/consumer)))

(s/def ::Course
  (s/keys :req-un [::Course/consumers
                   ::Course/courseId
                   ::common/duration
                   ::Course/educationSpecification
                   ::Course/validFrom]
          :opt-un [::Course/abbreviation
                   ::Course/description
                   ::Course/link
                   ::Course/name
                   ::Course/teachingLanguage]))
