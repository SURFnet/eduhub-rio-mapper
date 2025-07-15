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

(ns nl.surf.eduhub-rio-mapper.specs.course
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec text-spec]]
            [nl.surf.eduhub-rio-mapper.specs.common :as common]))

(s/def ::abbreviation string?)
(s/def ::courseId ::common/uuid)
(s/def ::description ::common/LongLanguageTypedStrings)
(s/def ::educationLocationCode string?)
(s/def ::educationSpecification ::common/uuid)
(s/def ::firstStartDate ::common/date)
(s/def ::foreignPartner string?)
(s/def ::foreignPartners (s/coll-of ::foreignPartner))
(s/def ::jointPartnerCode (text-spec 1 1000))
(s/def ::jointPartnerCodes (s/coll-of ::jointPartnerCode))
(s/def ::link string?)
(s/def ::name ::common/LanguageTypedStrings)
(s/def ::teachingLanguage (re-spec #"[a-z]{3}"))
(s/def ::validFrom ::common/date)
(s/def ::validTo ::common/date)

(s/def ::course-consumer
  (s/keys :req-un [::common/educationOffererCode]
          :opt-un [::educationLocationCode
                   ::foreignPartners
                   ::jointPartnerCodes]))

(s/def ::rio-consumer
  (s/merge ::common/rio-consumer
           ::course-consumer))

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


(s/def ::course
  (s/keys :req-un [::name]
          :opt-un [::abbreviation
                   ::description
                   ::link
                   ::teachingLanguage]))

(s/def ::timelineOverride
  (s/keys :req-un [::course
                   ::validFrom]
          :opt-un [::validTo]))

(s/def ::timelineOverrides
  (s/coll-of ::timelineOverride))

(s/def ::courseTopLevel
  (s/keys :req-un [::consumers
                   ::courseId
                   ::common/duration
                   ::educationSpecification
                   ::validFrom]
          :opt-un [::timelineOverrides]))

;; extract attribute vector from specs for use in spec helper
(def course-req-attrs (common/extract-req-attrs ::course))
(def course-opt-attrs (common/extract-opt-attrs ::course))
(def course-consumer-req-attrs (common/extract-req-attrs ::course-consumer))
(def course-consumer-opt-attrs (common/extract-opt-attrs ::course-consumer))
