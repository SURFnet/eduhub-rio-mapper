;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022, 2026 SURFnet B.V.
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

(ns nl.surf.eduhub-rio-mapper.v6.ooapi.loader
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.openapi.v3.validator :as validator]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.v6.specs.ooapi :as ooapi-v6]))

(def ^:private page-size
  "Maximum amount of items to fetch in a single page."
  250)

(defn- ooapi-type->path [ooapi-type id page]
  {:pre  [(string? ooapi-type)
          (or (#{"programme" "programme-offerings" "course" "course-offerings"} ooapi-type) (prn ooapi-type))]}
  (if id
    (let [page-suffix (if page (str "&pageNumber=" page) "")
          path        (case ooapi-type
                        "programme"               "programmes/%s?returnTimelineOverrides=true"
                        "course"                  "courses/%s?returnTimelineOverrides=true"
                        "course-offerings"        (str "courses/%s/course-offerings?pageSize=" page-size "&consumer=rio" page-suffix)
                        "programme-offerings"     (str "programmes/%s/programme-offerings?pageSize=" page-size "&consumer=rio" page-suffix))]
      (format path id))
    (case ooapi-type
      "programmes" "programmes"
      "courses" "courses"
      (throw (ex-info "No id, wrong type" {:id id, :ooapi-type ooapi-type})))))

(defn- wrap-ooapi-request->ring-request
  "Middleware translating ::ooapi/request into ring-style HTTP request.

  Returns the response body as the result."
  [handler]
  (fn [{::ooapi/keys [root-url type id]
        :keys        [institution-schac-home gateway-credentials connection-timeout page]
        :as          request}]
    (let [url (str root-url (ooapi-type->path type id page))
          uri (string/replace url #"\?.*" "")]
      (:body (handler (merge request
                             {:url                url
                              :uri                uri ;; used by openapi-validator
                              :content-type       :json
                              :method             :get
                              :connection-timeout connection-timeout
                              :headers            {"X-Route" (str "endpoint=" institution-schac-home)
                                                   "Accept"  "application/json; version=6"}}
                             (when-let [{:keys [username password]} gateway-credentials]
                               {:basic-auth [username password]})))))))

(defn- wrap-ooapi-envelop
  "Middleware unpacking OOAPI Gateway envelopes.

  Assumes the 200 OK responses contain envelops, extracts the status
  and body of the response for the given `institution-schac-home`,
  replacing the status and body of the gateway response."
  [handler]
  (fn [{::ooapi/keys [type id] :keys [institution-schac-home] :as request}]
    (let [response (handler request)
          body (-> response :body (json/read-str :key-fn keyword))
          status (get-in body [:gateway :endpoints (keyword institution-schac-home) :responseCode])]
      (condp = status
        http-status/not-found
        (throw (ex-info "OOAPI object not found" {:status status
                                                  :id id
                                                  :type type}))
        http-status/unauthorized
        (throw (ex-info "Unauthorized for endpoint" {:status status
                                                     :id id
                                                     :type type}))

        http-status/ok
        (assoc response
               :status status
               :body (get-in body [:responses (keyword institution-schac-home)]))

        ;; else
        (throw (ex-info "Endpoint returns unexpected status" {:status status
                                                              :id id
                                                              :type type}))))))

(def ^:private validator-context
  (-> "ooapiv6.json"
      io/resource
      io/reader
      json/read
      (validator/validator-context {})))

(defn- response-validator
  "Return an OOAPI v6 response validator for requests at the given `root-url`."
  [root-url]
  (let [uri-prefix (string/replace root-url #"https?://[^/]*" "")]
    (-> validator-context
        (assoc :uri-prefix uri-prefix)
        (validator/response-validator))))

(defn- wrap-response-validator
  "Middleware validating OOAPI responses.

  When a response is not valid according to the OOAPI v6 spec, throws
  an exception."
  [handler]
  (fn [{root-url ::ooapi/root-url type ::ooapi/type :as request}]
    {:pre [root-url type]}
    (let [validate-response (response-validator root-url)
          response (assoc (handler request) :headers {"content-type" "application/json"})
          to-be-validated {:request request :response (update response :body walk/stringify-keys)}
          issues (validate-response to-be-validated [])]
      (when issues
        (throw (ex-info "Error validating OOAPI Response"
                        {:issues issues
                         :request request
                         :response response})))
      response)))

(def max-pages 50)

(defn- wrap-pagination
  "Middleware for fetching paged items.

  If the response is paged (has a :pageNumber and :items),
  fetch all remaining pages and combine items in the result.

  Fetches no more than `max-pages`."
  [handler]
  (fn [ooapi-request]
    (loop [{:keys [items hasNextPage pageNumber] :as response} (handler ooapi-request)]
      (if (and items pageNumber) ;; paged result
        (if (and hasNextPage (< pageNumber max-pages))
          (recur (-> (handler (assoc ooapi-request :page (inc pageNumber)))
                     (update :items (fn [next-items]
                                      (into items next-items)))))
          response)
        response))))

;; This function fetches OOAPI data over http.
;;
;; It expects the request to contain ::ooapi/root-url,
;; ::ooapi/id, ::ooapi/type, :gateway-credentials,
;; institution-schac-home
(def ^:private ooapi-http-loader
  (-> http-utils/send-http-request
      wrap-ooapi-envelop
      wrap-response-validator
      wrap-ooapi-request->ring-request
      wrap-pagination))

(defn make-ooapi-http-loader
  "Returns an ooapi-loader function for the given configuration.

  The returned loader takes a map with ::ooapi/id, ::ooapi/type
  attributes"
  [root-url credentials rio-config]
  (fn wrapped-ooapi-http-loader [context]
    (let [request (assoc context
                         ::ooapi/root-url root-url
                         :gateway-credentials credentials
                         :connection-timeout (:connection-timeout-millis rio-config))]
      (ooapi-http-loader request))))

(defn ooapi-file-loader
  [{::ooapi/keys [type id]}]
  {:pre [type id]}
  (let [path (str "test-v6/fixtures/ooapi-loader/" type "-" id ".json")]
    (json/read-str (slurp path) :key-fn keyword)))

(defn load-offerings
  [loader {::ooapi/keys [id type] :as request}]
  {:pre [(#{"programme" "course"} type)]}
  (-> request
      (assoc ::ooapi/id id
             ::ooapi/type (str type "-offerings"))
      (loader)
      :items))

(defn load-entities
  "Loads ooapi entity, including associated offerings and education specification, if applicable."
  [loader {::ooapi/keys [type] :as request}]
  {:pre  [(#{"programme" "course"} type)]
   :post [(some? (::ooapi/entity %))
          (not-empty (::ooapi/entity %))]}
  (let [entity                  (loader request)
        consumer                (:consumer entity)
        joint-programme?          (= "true" (str (:jointProgramme consumer)))
        ;; load offerings unless prgspec (type = programme and programmeType = specification)
        offerings               (when (or (not= type "programme")
                                          (not= "specification" (:programmeType entity)))
                                  (load-offerings loader request))
        prgspec-type            (cond
                                  joint-programme?
                                  "programme"

                                  (= "specification" (:programmeType entity))
                                  (:specificationType consumer)

                                  :else
                                  (-> request
                                      (assoc ::ooapi/type "programme"
                                             ::ooapi/id (:specificationId consumer))
                                      (loader)
                                      (get-in [:consumer :specificationType])))]
    (cond-> request
      joint-programme?
      (assoc
       ::rio/opleidingscode (:educationUnitCode consumer))

      :always
      (assoc
       ::ooapi/entity (assoc entity :offerings offerings)
       ::ooapi-v6/specification-type prgspec-type))))
