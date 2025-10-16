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

(ns nl.surf.eduhub-rio-mapper.v5.specs.offerings
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as-alias ooapi]
            [nl.surf.eduhub-rio-mapper.v5.specs.common :as common]
            [nl.surf.eduhub-rio-mapper.v5.utils.ooapi :as ooapi-utils]))

(s/def ::consumer (s/keys))
(s/def ::priceInformationItem (s/keys))

(s/def ::offeringId ::common/uuid)
(s/def ::endDate ::common/date)
(s/def ::startDate ::common/date)
(s/def ::modeOfDelivery ::common/modeOfDelivery)
(s/def ::enrollStartDate ::common/date)
(s/def ::enrollEndDate ::common/date)
(s/def ::maxNumberStudents number?)
(s/def ::priceInformation (s/coll-of ::priceInformationItem))
(s/def ::consumers (s/coll-of ::consumer))
(s/def ::flexibleEntryPeriodStart ::common/date)
(s/def ::flexibleEntryPeriodEnd ::common/date)

(s/def ::Offering
  (s/and
    ooapi-utils/has-mode-of-delivery?
    ooapi-utils/has-registration-status?
    (s/keys :req-un [::offeringId
                     ::endDate
                     ::startDate
                     ::enrollStartDate]
            :opt-un [::enrollEndDate
                     ::maxNumberStudents
                     ::modeOfDelivery
                     ::priceInformation
                     ::consumers
                     ::flexibleEntryPeriodStart
                     ::flexibleEntryPeriodEnd])))

(s/def ::items (s/coll-of ::Offering))

(s/def ::OfferingsRequest
  (s/keys :req-un [::items]))
