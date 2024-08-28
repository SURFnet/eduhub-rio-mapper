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

(ns nl.surf.eduhub-rio-mapper.endpoints.status
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :as trace-context]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.utils.logging :as logging]
            [nl.surf.eduhub-rio-mapper.utils.redis :as redis])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(defn- status-key
  [{:keys [redis-key-prefix]
    :or {redis-key-prefix "eduhub-rio-mapper"}}
   token]
  (str redis-key-prefix ".status:" token))

(defn- rset!
  [{:keys [redis-conn status-ttl-sec] :as config}
   token
   status]
  {:pre [status-ttl-sec token status]}
  (redis/set redis-conn
             (status-key config token)
             status
             "EX" status-ttl-sec))

(defn rget
  [{:keys [redis-conn] :as config} token]
  (redis/get redis-conn (status-key config token)))

(defn purge!
  [{:keys [redis-conn] :as config}]
  (doseq [k (redis/keys redis-conn (status-key config "*"))]
    (redis/del redis-conn k)))

(def callback-retry-sleep-ms 30000)

(defn- do-async-callback [config {:keys [token trace-context] :as job}]
  (let [status (rget config token)
        req    {:url                    (::job/callback-url job)
                :method                 :post
                :content-type           :json
                :institution-schac-home (:institution-schac-home job)
                :institution-name       (:institution-name job)
                :body                   (json/json-str status)
                :connection-timeout     (-> config :rio-config :connection-timeout-millis)
                :throw-exceptions       false}]
    (async/thread
      (trace-context/with-context trace-context
                                  (logging/with-mdc (assoc trace-context
                                                      :token                  token
                                                      :url                    (::job/callback-url job)
                                                      :institution-schac-home (:institution-schac-home job)
                                                      :institution-name       (:institution-name job))
                                                    (try
                                                      (loop [retries-left 3]
                                                        (let [status (-> req http-utils/send-http-request :status)]
                                                          (when-not (http-status/success-status? status)
                                                            (log/debugf "Could not reach webhook %s, %d retries left" (:url req) retries-left)
                                                            (Thread/sleep callback-retry-sleep-ms)
                                                            (when (pos? retries-left)
                                                              (recur (dec retries-left))))))
                                                      (catch Exception ex
                                                        (logging/log-exception ex nil))))))))

(def final-status? #{:done :error :time-out})

(defn errors?
  "Return true if `x` has errors."
  [x]
  (and (map? x)
       (contains? x :errors)))

(defn retryable?
  "Return true if `x` has errors and can be retried."
  [x]
  (and (errors? x)
       (some-> x :errors :retryable? boolean)))

(def datetime-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn make-set-status-fn [config]
  (fn [{::job/keys [callback-url] :keys [token action] ::ooapi/keys [id type] :as job}
       status & [data]]
    (let [opleidingseenheidcode (-> data :aanleveren_opleidingseenheid_response :opleidingseenheidcode)
          aangeb-opleidingcode  (-> data ::rio/aangeboden-opleiding-code)
          value                 (cond-> {:status   status
                                         :token    token
                                         :action   action
                                         :resource (str type "/" id)}

                                        (and (= :done status)
                                             opleidingseenheidcode)
                                        ;; Data is result of run-job-fn, which is result of
                                        ;; job/run!, which is result of update-and-mutate
                                        ;; or delete-and-mutate which is the result of the
                                        ;; mutator/make-mutator, which is the result of
                                        ;; handle-rio-mutate-response, which is the parsed
                                        ;; xml response converted to edn.
                                        (assoc :attributes {:opleidingseenheidcode opleidingseenheidcode})

                                        (and (= (System/getenv "STORE_HTTP_REQUESTS") "true")
                                             (#{:done :error :time-out} status)
                                             (-> data :http-messages))
                                        (assoc :http-messages (-> data :http-messages))

                                        (and (= :done status)
                                             (:aanleveren_aangebodenOpleiding_response data))
                                        (assoc :attributes {:aangebodenopleidingcode aangeb-opleidingcode})

                                        (:dry-run data)
                                        (assoc :attributes (:dry-run data))

                                        (:link data)
                                        (assoc :attributes (:link data))

                                        (#{:error :time-out} status)
                                        (assoc :phase (-> data :errors :phase)
                                               :message (-> data :errors :message)))]

      (logging/with-mdc value
                        (case status
                          :done (log/infof "Finished job, token %s, type %s, id %s" token type id)
                          :error (log/warnf "Failed job, token %s, type %s, id %s" token type id)
                          :time-out (log/warnf "Timed out job, token %s, type %s, id %s" token type id)
                          ;; Only log final statuses, not statuses such as "in-progress"
                          nil))

      (rset! config token value))

    (when (and callback-url (final-status? status))
      (logging/with-mdc
        {:token                  (:token job)
         :url                    callback-url
         :institution-name       (:institution-name job)
         :institution-schac-home (:institution-schac-home job)}
        (do-async-callback config job)))))
