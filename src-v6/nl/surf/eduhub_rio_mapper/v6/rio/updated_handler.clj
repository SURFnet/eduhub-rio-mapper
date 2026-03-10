;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.v6.rio.updated-handler
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.specs.mutation :as mutation]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as-alias ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.v6.rio.aangeboden-opleiding :as aangeboden-opl]
            [nl.surf.eduhub-rio-mapper.v6.rio.opleidingseenheid :as opl-eenh]
            [nl.surf.eduhub-rio-mapper.v6.rio.relation-handler :as relation-handler]
            [nl.surf.eduhub-rio-mapper.v6.specs.ooapi :as ooapi-v6]))

;; We have full entities in the request for upserts and then we need to
;; also fetch the education-specification from the entity if it's a
;; coarse or program.
;;
;; For deletes we don't have a full entity in the request (since it's
;; been deleted) and we only need the education-specification id if
;; the root entity is an education-specification.

(defn update-mutation
  "Returned object conforms to ::Mutation/mutation-response."
  [{:keys [institution-oin args rio-type]
    ::ooapi/keys [id entity type]
    ::ooapi-v6/keys [specification-type]
    ::rio/keys [opleidingscode aangeboden-opleiding-code] :as job}]
  {:pre [(or (not= type "programme") specification-type)] ;; type programme implies specification-type present
   :post [(s/valid? ::mutation/mutation-response %)]}
  (assert institution-oin)
  (if (and (not= "relation" type)
           (not= :oe rio-type)
           (not opleidingscode))
    ;; If we're not inserting a new education-specification or a
    ;; relation we need a rio code (from an earlier inserted
    ;; education-specification).
    (let [id (-> entity :consumer :specificationId)]
      (throw (ex-info (str "Education specification " id " not yet known by RIO updating " type)
                      {:entity     entity
                       :retryable? false})))
    (let [entity (cond-> entity
                   (and opleidingscode
                        (= :oe rio-type))
                   (assoc :rioCode opleidingscode)

                   (and aangeboden-opleiding-code
                        (= :ao rio-type))
                   (assoc :rioCode aangeboden-opleiding-code))]
      (cond
        (= :oe rio-type)
        {:action     "aanleveren_opleidingseenheid"
         :ooapi      entity
         :sender-oin institution-oin
         :rio-sexp   [(opl-eenh/education-specification->opleidingseenheid entity)]}

        (= type "relation")
        (let [[object-code valid-from valid-to] args]
          (relation-handler/relation-mutation :insert institution-oin
                                              {:opleidingseenheidcodes #{id object-code}
                                               :valid-from             valid-from
                                               :valid-to               valid-to}))

        :else
        {:action     "aanleveren_aangebodenOpleiding"
         :ooapi      entity
         :sender-oin institution-oin
         :rio-sexp   [(aangeboden-opl/->aangeboden-opleiding entity (keyword type) opleidingscode (select-keys job [::ooapi-v6/specification-type]))]}))))

(defn deletion-mutation
  "Returned object conforms to ::mutation/mutation-response."
  [{:keys [::rio/opleidingscode ::rio/aangeboden-opleiding-code ::ooapi/type rio-type ::ooapi/id institution-oin args]}]
  {:pre [(#{:ao :oe} rio-type)]
   :post [(s/valid? ::mutation/mutation-response %)]}
  (assert institution-oin)
  (if (= type "relation")
    (let [[other-code valid-from] args]
      (relation-handler/relation-mutation :delete institution-oin
                                          {:opleidingseenheidcodes #{id other-code}
                                           :valid-from             valid-from}))
    (case rio-type
      :oe
      (if opleidingscode
        {:action     "verwijderen_opleidingseenheid"
         :sender-oin institution-oin
         :rio-sexp   [[:duo:opleidingseenheidcode opleidingscode]]}
        (throw (ex-info "Unable to delete 'opleidingseenheid' without 'opleidingscode'"
                        {:education-specification-id id,
                         :retryable?                 false})))

      :ao
      (if aangeboden-opleiding-code
        {:action     "verwijderen_aangebodenOpleiding"
         :sender-oin institution-oin
         :rio-sexp   [[:duo:aangebodenOpleidingCode aangeboden-opleiding-code]]}
        (throw (ex-info "Unable to delete 'aangebodenopleiding' without 'aangebodenopleidingscode'"
                        {(keyword (str type "-id"))  id,
                         :retryable?                 false}))))))
