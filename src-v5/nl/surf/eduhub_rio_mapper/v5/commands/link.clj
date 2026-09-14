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

(ns nl.surf.eduhub-rio-mapper.v5.commands.link
  (:require [nl.surf.eduhub-rio-mapper.rio.conversion :as conversion]
            [nl.surf.eduhub-rio-mapper.rio.helper :as rio-helper]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]))

(defn- execute-link [{::ooapi/keys [id] :keys [institution-oin rio-type] :as _request} rio-loader-fn rio-config]
  {:pre [(#{:oe :ao} rio-type)]}
  (let [sleutelnaam-kw (if (= :oe rio-type) :eigenOpleidingseenheidSleutel :eigenAangebodenOpleidingSleutel)
        {:keys [rio-sexp old-id]} (conversion/rio-obj-raadplegen->beheren (rio-loader-fn) rio-type :change-key id)
        mutation {:action     (if (= :oe rio-type) "aanleveren_opleidingseenheid" "aanleveren_aangebodenOpleiding")
                  :rio-sexp   rio-sexp
                  :sender-oin institution-oin}
        predicate (fn []
                    (= id (conversion/eigen-sleutel (conversion/parse-rio-obj (rio-loader-fn) rio-type)
                                                   rio-type)))]

    (mutator/mutate! mutation rio-config)

    ;; Ensure RIO has processed the update
    (rio-helper/blocking-retry predicate
                               rio-config
                               "Ensure link is processed by rio")

    {:rio-sexp (:rio-sexp mutation)
     :success  true
     :link     {sleutelnaam-kw (cond-> {:diff (not= old-id id)}
                                 (not= old-id id)
                                 (assoc :old-id old-id
                                        :new-id id))}}))

(defn make-linker [rio-config getter]
  {:pre [rio-config]}
  (fn [{::ooapi/keys [type] ::rio/keys [opleidingscode aangeboden-opleiding-code] :keys [institution-oin] :as request}]
    {:pre [(:institution-oin request)]}
    (let [rio-type (if (= "education-specification" type) :oe :ao)
          request (assoc request :rio-type rio-type)
          rio-code (if (= :oe rio-type) opleidingscode aangeboden-opleiding-code)
          load-rio-obj-for-link
          (fn []
            (getter (rio.loader/rio-entity-request rio-type rio-code institution-oin :literal)))]
      (execute-link request load-rio-obj-for-link rio-config))))
