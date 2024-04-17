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
  (:require [nl.surf.eduhub-rio-mapper.utils.redis :as redis])
  (:refer-clojure :exclude [get]))

(defn- status-key
  [{:keys [redis-key-prefix]
    :or {redis-key-prefix "eduhub-rio-mapper"}}
   token]
  (str redis-key-prefix ".status:" token))

(defn set!
  [{:keys [redis-conn status-ttl-sec] :as config}
   token
   status]
  {:pre [status-ttl-sec token status]}
  (redis/set redis-conn
             (status-key config token)
             status
             "EX" status-ttl-sec))

(defn get
  [{:keys [redis-conn] :as config} token]
  (redis/get redis-conn (status-key config token)))

(defn purge!
  [{:keys [redis-conn] :as config}]
  (doseq [k (redis/keys redis-conn (status-key config "*"))]
    (redis/del redis-conn k)))