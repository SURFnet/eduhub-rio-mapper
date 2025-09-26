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

(ns nl.surf.eduhub-rio-mapper.utils.logging
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nl.jomco.http-status-codes :as http-status])
  (:import java.util.UUID
           org.slf4j.MDC))

(def redact-key-re
  "Regex matching keys for entries that should be redacted from logs."
  #"(?i:pass|secret|client.id|proxy.options|auth)") ;; proxy options can contain authentication info

(def redact-placeholder
  "String that will replace a redacted entry.

  Also see [[redact-key-re]] and [[with-mdc]]."
  "XXX-REDACTED")

(defn ->mdc-key
  "Convert x to key to use in the Mapped Diagnostic Context.

  MDC keys must be strings. For qualified keywords includes the
  namespace in the MDC key; \"my.foo/bar\", otherwise
  uses [[clojure.core/str]] to coerce to string."
  [x]
  (if (keyword? x)
    (subs (str x) 1) ;; convert with ns if fully qualified key, but
    ;; leave out colon: :foo/bar => "foo/bar"

    (str x)))

(declare ->mdc-entry)

(defn ->mdc-val
  "Convert x to value to use in the Mapped Diagnostic Context.

  MDC values must be strings -- the MDC is a flat string/string
  map. Assumes x is a keyword, a sequential collection of 'simple'
  values (converted to a comma-separated string) or has a reasonable
  [[clojure.core/str]] coercion."
  [x]
  (cond
    (keyword? x)
    (subs (str x) 1)

    (map? x)
    (->> x
         (map #(str/join " => " (->mdc-entry %)))
         (str/join ", ")
         (format "{ %s }"))

    (sequential? x)
    (str/join ", " (map #(str "'" (->mdc-val %) "'") x))

    :else
    (str x)))

(defn ->mdc-entry
  "Convert a `[key value]` pair to a Mapped Diagnostic Context entry.

  Uses [[->mdc-key]] and [[->mdc-val]] to convert. In entries with
  key matching [[redact-key-re]], the value will be replaced with
  [[redact-placeholder]]"
  [[k v]]
  (let [k (->mdc-key k)]
    [k (if (re-find redact-key-re k)
         redact-placeholder
         (->mdc-val v))]))

(defmacro with-mdc
  "Adds the map `m` to the Mapped Diagnostic Context (MDC) and executes `body`.

  Enties in `m` are processed using [[->mdc-entry]].

  Removes the added keys from the MDC after `body` is executed.

  See also [[redact-key-re]] and https://logback.qos.ch/manual/mdc.html"
  [m & body]
  `(let [m# ~m]
     (try
       (doseq [[k# v#] (map ->mdc-entry m#)]
         (MDC/put k# v#))
       ~@body
       (finally
         (doseq [k# (keys m#)]
           (MDC/remove (->mdc-key k#)))))))

(defn wrap-request-logging
  "Wrap a ring handler to handle trace-ids and such for logging."
  [f]
  (fn [{:keys                        [request-method uri
                                      client-id institution-schac-home institution-oin]
        {:keys [trace-id parent-id]} :trace-context
        :as                          request}]
    ;; We get the institution-schac-home etc from either the request
    ;; or the response, since this is information that might be added
    ;; by middleware down the stack.
    (let [method (str/upper-case (name request-method))]
      (with-mdc (cond-> {:request_method method
                         :url            uri
                         :trace-id       trace-id
                         :parent-id      parent-id}
                  client-id
                  (assoc :client-id client-id)

                  institution-schac-home
                  (assoc :institution-schac-home institution-schac-home)

                  institution-oin
                  (assoc :institution-oin institution-oin))
        (when-let [{:keys [status
                           client-id
                           institution-schac-home
                           institution-oin
                           institution-name
                           token] :as response}
                   (f request)]

          (with-mdc (cond-> {:http_status status}
                      client-id
                      (assoc :client-id client-id)

                      institution-schac-home
                      (assoc :institution-schac-home institution-schac-home)

                      token
                      (assoc :token token)

                      institution-name
                      (assoc :institution-name institution-name)

                      institution-oin
                      (assoc :institution-oin institution-oin))
            (log/info status method uri)
            response))))))

(defn log-exception
  [ex id]
  {:pre [(instance? Throwable ex)]}
  ;; Request info is provided in MDC, see wrap-request-logging
  (with-mdc (assoc (ex-data ex) :error-id id)
    (log/error ex (str (ex-message ex) " error-id: " id))))

(defn wrap-exception-logging
  "Wrap a ring handler to catch and log exceptions.
  When an exception is caught an Internal Server Error is returned
  containing an identifier which can be used to be lookup in the log
  files for debugging."
  [f]
  (fn [request]
    (try
      (f request)
      (catch Throwable ex
        (let [id (str (UUID/randomUUID))]
          (log-exception ex id)
          {:status http-status/internal-server-error
           :body   {:error    "Internal Server Error"
                    :error-id id}})))))

(defn wrap-logging
  "Wrap a ring handler with logging related middleware.
  See also: `wrap-request-logging` and `wrap-exception-logging`."
  [f]
  (-> f
      wrap-exception-logging
      wrap-request-logging))
