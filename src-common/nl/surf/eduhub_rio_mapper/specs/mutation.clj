(ns nl.surf.eduhub-rio-mapper.specs.mutation
  (:require [clojure.spec.alpha :as s]))

(s/def ::mutation-response
  (s/and map?
         (s/keys :req-un [::action ::sender-oin ::rio-sexp]
                 :opt-un [::ooapi])))
