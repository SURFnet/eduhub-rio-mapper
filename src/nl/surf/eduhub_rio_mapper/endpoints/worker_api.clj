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

(ns nl.surf.eduhub-rio-mapper.endpoints.worker-api
  (:require [compojure.core :refer [GET]]
            [compojure.route :as route]
            [nl.jomco.ring-trace-context :refer [wrap-trace-context]]
            [nl.surf.eduhub-rio-mapper.endpoints.app-server :as app-server]
            [nl.surf.eduhub-rio-mapper.endpoints.health :as health]
            [nl.surf.eduhub-rio-mapper.endpoints.ring-middleware-json :refer [wrap-json-response]]
            [nl.surf.eduhub-rio-mapper.utils.logging :refer [wrap-logging]]
            [ring.middleware.defaults :as defaults]))

(def public-routes
  (-> (compojure.core/routes
        (GET "/health" []
             {:health true}))))

(def routes
  (-> (compojure.core/routes
        public-routes
        (route/not-found nil))))

(defn make-app [config]
  (-> routes
      (health/wrap-health config)
      (wrap-json-response)
      (wrap-logging)
      (wrap-trace-context)
      (defaults/wrap-defaults defaults/api-defaults)))

(defn serve-api
  [{{:keys [^Integer port host]} :worker-api-config :as config}]
  (app-server/run-jetty (make-app config) host port))
