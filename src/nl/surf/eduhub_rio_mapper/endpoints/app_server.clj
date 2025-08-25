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

(ns nl.surf.eduhub-rio-mapper.endpoints.app-server
  (:require [ring.adapter.jetty :as jetty])
  (:import [org.eclipse.jetty.server HttpConnectionFactory]))

(defn run-jetty [app host port options]
  (println (str "Starting Jetty on port " port))
  ;; Configure Jetty to not send server version
  (let [configurator (fn [jetty]
                       (doseq [connector (.getConnectors jetty)]
                         (doseq [connFact (.getConnectionFactories connector)]
                           (when (instance? HttpConnectionFactory connFact)
                             (.setSendServerVersion (.getHttpConfiguration connFact) false)))))]
    (jetty/run-jetty app (assoc options
                                :host         host
                                :port         port
                                :daemon?      true
                                :configurator configurator))))
