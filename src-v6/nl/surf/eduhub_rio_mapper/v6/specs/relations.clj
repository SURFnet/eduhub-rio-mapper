(ns nl.surf.eduhub-rio-mapper.v6.specs.relations
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec]]
            [nl.surf.eduhub-rio-mapper.v6.utils.ooapi :as ooapi-utils]))

(s/def ::date
  (s/and (re-spec #"\d\d\d\d-[01]\d-[0123]\d")
         ooapi-utils/valid-date?))

(s/def ::opleidingseenheidcodes
  (s/and set? (s/coll-of string?)))

(s/def ::valid-from ::date)

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
