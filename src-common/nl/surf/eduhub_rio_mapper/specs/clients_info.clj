(ns nl.surf.eduhub-rio-mapper.specs.clients-info
  (:require [clojure.spec.alpha :as s]))

(s/def ::client-info
  (s/keys :req-un [::client-id]
          :opt-un [::institution-name
                   ::institution-oin
                   ::institution-schac-home]))

(s/def ::clients
  (s/coll-of ::client-info))

(s/def ::data
  (s/keys :req-un [::clients]))
