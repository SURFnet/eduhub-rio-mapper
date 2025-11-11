(ns nl.surf.eduhub-rio-mapper.specs.ooapi
  (:require [clojure.spec.alpha :as s]))

(s/def ::id string?)
(s/def ::type
  #{"course"
    "education-specification"
    "program"
    "program-offerings"
    "course-offerings"})
