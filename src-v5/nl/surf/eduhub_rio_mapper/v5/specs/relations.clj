(ns nl.surf.eduhub-rio-mapper.v5.specs.relations
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.RelationChild :as-alias RelationChild]
            [nl.surf.eduhub-rio-mapper.RelationParent :as-alias RelationParent]
            [nl.surf.eduhub-rio-mapper.v5.specs.common :as common]))

(s/def ::opleidingseenheidcodes
  (s/and set? (s/coll-of string?)))

(s/def ::valid-from ::common/date)

(s/def ::relation
  (s/keys :req-un [::opleidingseenheidcodes ::valid-from]
          :opt-un [::valid-to]))

(s/def ::relation-vector
  (s/and vector? (s/coll-of ::relation)))

(s/def ::relation-set
  (s/and set? (s/coll-of ::relation)))

(s/def ::relation-collection
  (s/coll-of ::relation))

(s/def ::missing ::relation-set)
(s/def ::superfluous ::relation-set)

(s/def ::relation-diff
  (s/keys :req-un [::missing ::superfluous]))

(s/def ::educationSpecificationType #(= % "program"))
(s/def ::RelationParent/educationSpecificationSubType nil?)
(s/def ::RelationChild/educationSpecificationSubType #(= % "variant"))
(s/def ::parent (s/keys :req-un [::educationSpecificationType]
                                 :opt-un [::RelationParent/educationSpecificationSubType]))
(s/def ::child (s/keys :req-un [::educationSpecificationType]
                                :opt-un [::RelationChild/educationSpecificationSubType]))
