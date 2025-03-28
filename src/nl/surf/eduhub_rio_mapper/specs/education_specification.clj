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

(ns nl.surf.eduhub-rio-mapper.specs.education-specification
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.enums :as enums]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [text-spec]]
            [nl.surf.eduhub-rio-mapper.specs.common :as common]
            [nl.surf.eduhub-rio-mapper.utils.ooapi :as ooapi-utils]))

;; Top level response keys
(s/def ::abbreviation (text-spec 1 255))
(s/def ::children (s/coll-of ::common/uuid))
(s/def ::description ::common/LongLanguageTypedStrings)
(s/def ::educationSpecificationId ::common/uuid)
(s/def ::educationSpecificationSubType #{"variant"})
(s/def ::educationSpecificationType
  enums/educationSpecificationTypes)

(s/def ::formalDocument enums/formalDocumentTypes)
(s/def ::name ::common/nlOrEnLanguageTypedStrings)
(s/def ::link (text-spec 1 2048))
(s/def ::parent ::common/uuid)
(s/def ::primaryCode ::common/codeTuple)
(s/def ::validFrom ::common/date)
(s/def ::validTo ::common/date)

(s/def ::category (s/coll-of string?))

(s/def ::eduspec-consumer
  (s/keys :req-un []
          :opt-un [::educationSpecificationSubType ::category]))

(s/def ::rio-consumer
  (s/merge ::common/rio-consumer
           ::eduspec-consumer))

(s/def ::consumers (s/coll-of ::common/consumer))

(s/def ::EducationSpecification
  (s/keys :req-un
          [::name]
          :opt-un
          [::abbreviation
           ::children
           ::consumers
           ::description
           ::educationSpecificationSubType
           ::formalDocument
           ::link
           ::parent
           ::common/fieldsOfStudy
           ::common/learningOutcomes
           ::common/level
           ::common/levelOfQualification
           ::common/otherCodes
           ::common/sector
           ::common/studyLoad]))

(s/def ::educationSpecification ::EducationSpecification)

;; extract attribute vector from specs for use in spec helper
(def education-specification-req-attrs (-> (s/describe ::educationSpecification) (nth 2)))
(def education-specification-opt-attrs (-> (s/describe ::educationSpecification) (nth 4)))
(def eduspec-consumer-req-attrs (-> (s/describe ::eduspec-consumer) (nth 2)))
(def eduspec-consumer-opt-attrs (-> (s/describe ::eduspec-consumer) (nth 4)))

(s/def ::timelineOverride
  (s/keys :req-un [::educationSpecification
                   ::validFrom]
          :opt-un [::validTo]))

(s/def ::timelineOverrides
  (s/coll-of ::timelineOverride))

(s/def ::EducationSpecificationTopLevel
  (s/and
    (s/merge ::EducationSpecification
             (s/keys :req-un [::educationSpecificationId
                              ::educationSpecificationType
                              ::primaryCode
                              ::validFrom]
                     :opt-un [::validTo
                              ::timelineOverrides]))
    ooapi-utils/valid-type-and-subtype?
    ooapi-utils/not-equal-to-parent?
    ooapi-utils/level-sector-map-to-rio?))
