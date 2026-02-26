(ns nl.surf.eduhub-rio-mapper.specs.rio
  (:require [clojure.pprint :refer [pprint]]
            [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec]]))

(s/def ::OpleidingsEenheidID-v01 (re-spec #"\d{4}O\d{4}"))
(s/def ::opleidingscode ::OpleidingsEenheidID-v01)

;; TODO Remove, for debugging only
(defn spat
  ([entity]
   (spat "interaction.log" entity))
  ([file entity]
   (spit file (with-out-str (pprint (dissoc entity :trace-context))) :append true)))
