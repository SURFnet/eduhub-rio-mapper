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

(ns nl.surf.eduhub-rio-mapper.utils.http-utils
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :as trace-context])
  (:import (clojure.lang ExceptionInfo)
           (java.security.cert CertificateException CertificateExpiredException
                               CertificateNotYetValidException CertPathValidatorException)
           (javax.net.ssl SSLException)))

;; middleware to add to the http client

;; Can be rebound dynamically; this happens a.o. in the interaction test.
(defn ^:dynamic *vcr* [handler request]
  (handler request))

(def ^:dynamic *http-messages* nil)

(defn wrap-vcr [handler]
  (fn [request]
    (*vcr* handler request)))

(def http-message-req-keys [:url :method :params :body :headers])
(def http-message-res-keys [:status :body])

(defn- wrap-outgoing-request-logging
  [handler]
  (fn [{:keys [method url headers] :as req}]
    (let [res (try
                (handler req)
                (catch Exception ex
                  ;; Also record requests that didn't get a response (TLS errors, connection refused),
                  ;; so they show up in the job status.
                  (when *http-messages*
                    (swap! *http-messages* conj {:req   (select-keys req http-message-req-keys)
                                                 :error {:class   (.getName (class ex))
                                                         :message (ex-message ex)}}))
                  (throw ex)))]
      (log/debugf "%s - %s; %s; status %s"
                  (get headers "traceparent")
                  method
                  url
                  (:status res))
      (log/trace {:req req :res res})
      (when *http-messages*
        (swap! *http-messages* conj {:req (select-keys req http-message-req-keys)
                                     :res (select-keys res http-message-res-keys)}))
      res)))

(defn- add-request-options
  [{:keys [content-type] :as request}]
  {:pre [content-type]}
  (assoc request
         :content-type (case content-type
                         :json
                         "application/json"

                         :xml
                         "text/xml; charset=utf-8")
         :keystore-type    "jks"
         :trust-store-type "jks"))

(defn- wrap-request-options
  [handler]
  (fn with-request-options
    [request]
    (handler (add-request-options request))))

(defn- wrap-errors
  [handler]
  (fn with-errors
    [{:keys [throw-exceptions] :as request :or {throw-exceptions true}}]
    ;; we disable exception throwing down the stack, and throw them
    ;; here, if they're enabled in the request (default: true)
    (let [response (handler (assoc request :throw-exceptions false))]
      (when (and throw-exceptions
                 (not (http-status/success-status? (:status response))))
        (throw (ex-info (str "HTTP request failed: "
                             (:status response) " " (:method request) " " (:url request))
                        {:request request, :response response})))
      response)))

(defn- cause-chain
  "Returns seq with exception, then all its causes."
  [ex]
  (take-while some? (iterate ex-cause ex)))

(defn- find-cause
  "Finds exception of given class in chain of causes."
  [ex klass]
  (some #(when (instance? klass %) %) (cause-chain ex)))

(defn- connection-error-message
  "Describe an exception thrown while connecting to `url`."
  [url ex]
  (let [expired-ex   (find-cause ex CertificateExpiredException)
        not-valid-ex (find-cause ex CertificateNotYetValidException)
        cert-ex      (or (find-cause ex CertificateException)
                         (find-cause ex CertPathValidatorException))
        ssl-ex       (find-cause ex SSLException)]
    (cond
      expired-ex
      (str "Could not connect to " url ": TLS handshake failed - certificate expired (" (ex-message expired-ex) ")")

      not-valid-ex
      (str "Could not connect to " url ": TLS handshake failed - certificate not yet valid (" (ex-message not-valid-ex) ")")

      cert-ex
      (str "Could not connect to " url ": TLS handshake failed - invalid certificate (" (ex-message cert-ex) ")")

      ssl-ex
      (str "Could not connect to " url ": TLS error (" (ex-message ssl-ex) ")")

      :else
      (str "Could not connect to " url ": " (.getName (class ex)) " (" (ex-message ex) ")"))))

(defn- certificate-error? [ex]
  (boolean (or (find-cause ex CertificateException)
               (find-cause ex CertPathValidatorException)
               (some #(and (instance? SSLException %)
                           (some-> (ex-message %) (.contains "certificate")))
                     (cause-chain ex)))))

(defn- wrap-connection-errors
  "Rethrow low-level exceptions (I/O, TLS) with URL and readable message.

  Certificate errors are marked as not retryable."
  [handler]
  (fn with-connection-errors
    [{:keys [url] :as request}]
    (try
      (handler request)
      (catch ExceptionInfo ex
        (throw ex))
      (catch Exception ex
        (throw (ex-info (connection-error-message url ex)
                        (cond-> {:url url}
                          (certificate-error? ex)
                          (assoc :retryable? false))
                        ex))))))

(def ^{:arglists '([{:keys [url method] :as request}])} ;; set argument documention
  send-http-request
  "Sends an http request using `clj-http.client/request`.

  Takes a `request` map and returns a response."
  (-> (var http/request)                                    ; Can be changed dynamically
      wrap-vcr
      wrap-outgoing-request-logging
      wrap-request-options
      wrap-errors
      wrap-connection-errors
      trace-context/wrap-new-trace-context))
