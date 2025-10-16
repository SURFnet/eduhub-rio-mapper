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

(ns nl.surf.eduhub-rio-mapper.utils.authentication
  "Authenticate incoming HTTP API requests using SURFconext.

  This uses the OAuth2 Client Credentials flow for authentication. From
  the perspective of the RIO Mapper HTTP API (a Resource Server in
  OAuth2 / OpenID Connect terminology), this means that:

  1. Calls to the API should contain an Authorization header with a
     Bearer token.

  2. The token is verified using the Token Introspection endpoint,
     provided by SURFconext.

  The Token Introspection endpoint is described in RFC 7662.

  The SURFconext service has extensive documentation. For our use
  case you can start here:
  https://wiki.surfnet.nl/display/surfconextdev/Documentation+for+Service+Providers

  The flow we use is documented at https://wiki.surfnet.nl/pages/viewpage.action?pageId=23794471 "
  (:require [clj-http.client :as client]
            [clojure.core.memoize :as memo]
            [clojure.tools.logging :as log]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.utils.logging :refer [with-mdc]]
            [ring.util.response :as response]))

(defn bearer-token
  [{{:strs [authorization]} :headers}]
  (some->> authorization
           (re-matches #"Bearer ([^\s]+)")
           second))

(defn make-token-authenticator
  "Make a token authenticator that uses the OIDC `introspection-endpoint`.

  Returns a authenticator that tests the token using the given
  `instrospection-endpoint` and returns the token's client id if the
  token is valid.
  Returns nil unless the authentication service returns a response with a 200 code."
  [{:keys [introspection-endpoint client-id client-secret]}]
  {:pre [introspection-endpoint client-id client-secret]}
  (fn token-authenticator
    [token]
    ;; TODO: Pass trace id?
    (try
      (let [{:keys [status] :as response}
            (client/post (str introspection-endpoint)       ;; may be URI object
                         {:form-params {:token token}
                          :accept      :json
                          :coerce      :always
                          :as          :json
                          :basic-auth  [client-id client-secret]})]
        (when (= http-status/ok status)
          ;; See RFC 7662, section 2.2
          (let [active (get-in response [:body :active])]
            (when-not (boolean? active)
              (throw (ex-info "Invalid response for token introspection, active is not boolean."
                              {:body (:body response)})))
            (when active
              (get-in response [:body :client_id])))))
      (catch Exception ex
        (log/error ex "Error in token-authenticator")
        nil))))

(defn cache-token-authenticator
  "Cache results of the authenticator for `ttl-minutes` minutes."
  [authenticator {:keys [ttl-minutes]}]
  (assert (< 0 ttl-minutes))
  (memo/ttl authenticator :ttl/threshold (* 1000 60 ttl-minutes)))

(defn wrap-authentication
  "Authenticate calls to ring handler `f` using `token-authenticator`.

  The token authenticator will be called with the Bearer token from
  the incoming http request. If the authenticator returns a client-id,
  the client-id gets added to the request as `:client-id` and the
  request is handled by `f`. If the authenticator returns `nil` or
  if the http status of the authenticator call is not successful, the
  request is forbidden.

  If no bearer token is provided, the request is executed without a client-id."
  [f token-authenticator]
  (fn [request]
    (if-let [token (bearer-token request)]
      (if-let [client-id (token-authenticator token)]
        ;; set client-id on request and response (for tracing)
        (with-mdc {:client-id client-id}
                  (-> request
                      (assoc :client-id client-id)
                      f
                      (assoc :client-id client-id)))
        (response/status http-status/forbidden))
      (f request))))
