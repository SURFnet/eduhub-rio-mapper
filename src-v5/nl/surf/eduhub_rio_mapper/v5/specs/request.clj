(ns nl.surf.eduhub-rio-mapper.v5.specs.request
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.v5.specs.common :as common]))

(s/def ::request (s/keys :req [::ooapi/root-url ::ooapi/type]
                         :req-un [::common/institution-schac-home ::common/gateway-credentials]
                         :opt [::ooapi/id ::rio/opleidingscode]))
