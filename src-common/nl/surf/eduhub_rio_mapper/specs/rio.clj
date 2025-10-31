(ns nl.surf.eduhub-rio-mapper.specs.rio
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec]]))

(s/def ::OpleidingsEenheidID-v01 (re-spec #"\d{4}O\d{4}"))
(s/def ::opleidingscode ::OpleidingsEenheidID-v01)
