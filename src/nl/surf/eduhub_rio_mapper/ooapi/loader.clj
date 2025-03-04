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

(ns nl.surf.eduhub-rio-mapper.ooapi.loader
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.ooapi.base :as ooapi-base]
            [nl.surf.eduhub-rio-mapper.specs.course :as course]
            [nl.surf.eduhub-rio-mapper.specs.education-specification :as education-specification]
            [nl.surf.eduhub-rio-mapper.specs.offerings :as offerings]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.specs.program :as program]
            [nl.surf.eduhub-rio-mapper.specs.request :as request]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.utils.ooapi :as ooapi-utils]))

;; This limit will be lifted later, to be replaced by pagination.
;;
;; See also https://trello.com/c/LtBQ8aaA/46

(def ^:private max-offerings
  "Maximum amount of course and program offerings that will be mapped."
  250)

(defn- ooapi-type->path [ooapi-type id page]
  (if id
    (let [page-suffix (if page (str "&pageNumber=" page) "")
          path        (case ooapi-type
                        "education-specification" "education-specifications/%s?returnTimelineOverrides=true"
                        "program" "programs/%s?returnTimelineOverrides=true"
                        "course" "courses/%s?returnTimelineOverrides=true"
                        "course-offerings" (str "courses/%s/offerings?pageSize=" max-offerings "&consumer=rio" page-suffix)
                        "program-offerings" (str "programs/%s/offerings?pageSize=" max-offerings "&consumer=rio" page-suffix))]
      (format path id))
    (case ooapi-type
      "education-specifications" "education-specifications"
      "programs" "programs"
      "courses" "courses")))

(defn- ooapi-http-loader
  [{::ooapi/keys [root-url type id]
    :keys [institution-schac-home gateway-credentials connection-timeout page]
    :as ooapi-request}]
  {:pre [(s/valid? ::request/request ooapi-request)]}
  (let [path    (ooapi-type->path type id page)
        request (merge {:url                (str root-url path)
                        :content-type       :json
                        :method             :get
                        :connection-timeout connection-timeout
                        :headers            {"X-Route" (str "endpoint=" institution-schac-home)
                                             "Accept"  "application/json; version=5"}}
                       (when-let [{:keys [username password]} gateway-credentials]
                         {:basic-auth [username password]}))
        response-body (-> request http-utils/send-http-request :body (json/read-str :key-fn keyword))
        response-code (get-in response-body [:gateway :endpoints (keyword institution-schac-home) :responseCode])]
    (condp = response-code
      http-status/not-found
      (throw (ex-info "OOAPI object not found" {:status response-code
                                                :id id
                                                :type type}))
      http-status/unauthorized
      (throw (ex-info "Unauthorized for endpoint" {:status response-code
                                                   :id id
                                                   :type type}))

      http-status/ok
      (get-in response-body [:responses (keyword institution-schac-home)])

      ;; else
      (throw (ex-info "Endpoint returns unexpected status" {:status response-code
                                                            :id id
                                                            :type type})))))

;; For type "offerings", loads all pages and merges them into "items"
(defn- ooapi-http-recursive-loader
  [{:keys [page-size] :as ooapi-request} items]
  {:pre [(s/valid? ::request/request ooapi-request)]}
  (loop [next-page 2
         current-page-size (count items)
         all-items items]
    (if (< current-page-size (or page-size max-offerings))
      ;; Fewer items than maximum allowed means that this is the last page
      {:items all-items}
      ;; We need to iterate, not all offerings seen yet.
      (let [next-items (-> ooapi-request
                         (assoc :page next-page)
                         ooapi-http-loader
                         :items)]
        (recur (inc next-page) (count next-items) (into all-items next-items))))))

;; Returns function that takes context with the following keys:
;; ::ooapi/root-url, ::ooapi/id, ::ooapi/type, :gateway-credentials, institution-schac-home
(defn make-ooapi-http-loader
  [root-url credentials rio-config]
  (fn wrapped-ooapi-http-loader [context]
    (let [request (assoc context
                    ::ooapi/root-url root-url
                    :gateway-credentials credentials
                    :connection-timeout (:connection-timeout-millis rio-config))
          response (ooapi-http-loader request)]
      (if (#{"course-offerings" "program-offerings"} (::ooapi/type context))
        (ooapi-http-recursive-loader request (:items response))
        response))))

(defn ooapi-file-loader
  [{::ooapi/keys [type id]}]
  (let [path (str "dev/fixtures/" type "-" id ".json")]
    (json/read-str (slurp path) :key-fn keyword)))

(def type-to-spec-mapping
  {"course"                  ::course/course
   "program"                 ::program/program
   "education-specification" ::education-specification/EducationSpecificationTopLevel
   "course-offerings"        ::offerings/OfferingsRequest
   "program-offerings"       ::offerings/OfferingsRequest})

(defn load-offerings
  [loader {::ooapi/keys [id type] :as request}]
  (case type
    "education-specification"
    nil

    ("course" "program")
    (-> request
        (assoc ::ooapi/id id
               ::ooapi/type (str type "-offerings"))
        (loader)
        :items)))

(defn validate-entity [entity spec type]
  (when-not (s/valid? spec entity)
    (println "YES")
    (throw (ex-info (str "Entity does not conform to OOAPI type " type "\n" (s/explain-str spec entity))
                    {:entity     entity
                     ;; retrying a failing spec won't help
                     :retryable? false})))
  entity)

(defn validating-loader
  [loader]
  (fn wrapped-validating-loader [{::ooapi/keys [type] :as request}]
    {:pre [type]}
    (-> request
        (loader)
        (validate-entity (type-to-spec-mapping type) type))))

(defn load-entities
  "Loads ooapi entity, including associated offerings and education specification, if applicable."
  [loader {::ooapi/keys [type] :as request}]
  (let [entity                  (loader request)
        rio-consumer            (ooapi-utils/extract-rio-consumer (:consumers entity))
        joint-program?          (= "true" (str (:jointProgram rio-consumer)))
        offerings               (load-offerings loader request)
        eduspec-type            (cond
                                  joint-program?
                                  "program"

                                  (= type "education-specification")
                                  (:educationSpecificationType entity)

                                  :else
                                  (-> request
                                      (assoc ::ooapi/type "education-specification"
                                             ::ooapi/id (ooapi-base/education-specification-id entity))
                                      (loader)
                                      :educationSpecificationType))]

    (when (and (not= type "education-specification")
               (= "program" eduspec-type))
      (validate-entity entity ::program/ProgramType "ProgramType")
      (validate-entity rio-consumer ::program/ProgramConsumerType "ProgramConsumerType"))
    (cond-> request
            joint-program?
            (assoc
              ::rio/opleidingscode (:educationUnitCode rio-consumer))

            :always
            (assoc
              ::ooapi/entity (assoc entity :offerings offerings)
              ::ooapi/education-specification-type eduspec-type))))
