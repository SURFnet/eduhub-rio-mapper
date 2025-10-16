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

(ns nl.surf.eduhub-rio-mapper.endpoints.health
  (:require [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.worker :as worker]))

;; Should always return true if redis is up
(defn redis-up? [config]
  (try
    (worker/occupied-queues config)
    (catch Exception _ex
      false)))

(defn wrap-health
  [app config]
  (fn with-health [req]
    (let [res (app req)]
      (if (:health res)
        (if (redis-up? config)
          (assoc res :status http-status/ok
                     :body "OK")
          (assoc res :status http-status/service-unavailable
                     :body "Service Unavailable"))
        res))))
