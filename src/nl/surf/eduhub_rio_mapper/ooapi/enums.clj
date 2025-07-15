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

;; DO NOT EDIT - CHANGES WILL BE OVERWRITTEN
;; This file is generated automatically via make generate-enums $PATH

(ns nl.surf.eduhub-rio-mapper.ooapi.enums)

(def codeTypes
  "Enum used in EducationSpecification for primaryCode and otherCodes."
  #{"brin" "crohoCreboCode" "programCode" "componentCode" "offeringCode" "organizationId" "buildingId" "bagId" "roomCode" "systemId" "productId" "nationalIdentityNumber" "studentNumber" "studielinkNumber" "esi" "userName" "accountId" "emailAdress" "groupCode" "isbn" "issn" "orcId" "uuid" "schacHome" "identifier"})

(def modesOfDelivery
  "Enum used in Offerings for modeOfDelivery."
  #{"distance-learning" "on campus" "online" "hybrid" "situated"})

(def modeOfStudy
  "Enum used in Programs for modeOfStudy."
  #{"full-time" "part-time" "dual training" "self-paced"})

(def programType
  "Enum used in Programs for programType."
  #{"program" "minor" "honours" "specialization" "track"})

(def acceleratedRoute
  "Enum used in Programs for acceleratedRoute."
  #{"accelerated_route" "no_accelerated_route"})

(def deficiency
  "Enum used in Programs for deficiency."
  #{"deficiencies" "no_deficiencies"})

(def propaedeuticPhase
  "Enum used in Programs for propaedeuticPhase."
  #{"no_propaedeutic_phase" "propaedeutic_phase_exam" "propaedeutic_phase_no_exam"})

(def requirementsActivities
  "Enum used in Programs for requirementsActivities."
  #{"requirements" "no_requirements"})

(def studyChoiceCheck
  "Enum used in Programs for studyChoiceCheck."
  #{"no_study_choice_check" "study_choice_check_available" "study_choice_check_mandatory"})

(def educationSpecificationTypes
  "Enum used in EducationSpecification for educationSpecificationType."
  #{"program" "privateProgram" "cluster" "course"})

(def formalDocumentTypes
  "Enum used in EducationSpecification for formalDocument."
  #{"diploma" "certificate" "no official document" "testimonial" "school advice"})

(def levels
  "Enum used in EducationSpecification for level."
  #{"secondary vocational education" "secondary vocational education 1" "secondary vocational education 2" "secondary vocational education 3" "secondary vocational education 4" "associate degree" "bachelor" "master" "doctoral" "undefined" "undivided" "nt2-1" "nt2-2"})

(def sectors
  "Enum used in EducationSpecification for sector."
  #{"secondary vocational education" "higher professional education" "university education"})

(def studyLoadUnits
  "Enum used in EducationSpecification for studyLoad."
  #{"contacttime" "ects" "sbu" "sp" "hour"})
