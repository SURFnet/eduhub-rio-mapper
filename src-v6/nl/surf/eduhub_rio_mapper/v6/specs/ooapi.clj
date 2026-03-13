(ns nl.surf.eduhub-rio-mapper.v6.specs.ooapi
  (:require [clojure.spec.alpha :as s]))

;; Defined by .consumer.specificationType
(s/def ::specification-type
  #{"course"
    "programme"
    "variant"
    "cluster"
    "private"})
