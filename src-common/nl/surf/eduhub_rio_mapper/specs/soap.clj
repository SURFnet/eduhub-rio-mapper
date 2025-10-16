(ns nl.surf.eduhub-rio-mapper.specs.soap
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec]]))

(s/def ::http-url (re-spec #"https?://.*"))
(s/def ::schema ::http-url)
(s/def ::contract ::http-url)
(s/def ::to-url ::http-url)
(s/def ::from-url ::http-url)
