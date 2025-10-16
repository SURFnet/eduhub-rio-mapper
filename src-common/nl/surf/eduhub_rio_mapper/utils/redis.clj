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

(ns nl.surf.eduhub-rio-mapper.utils.redis
  (:require [clojure.string :refer [upper-case]]
            [taoensso.carmine :as car :refer [wcar]]
            [taoensso.carmine.commands :as carcmd])
  (:refer-clojure :exclude [get keys set]))

(defmacro defcmd [n]
  (let [cmd (intern 'taoensso.carmine n)]
    `(defn ~n [conn# & args#]
       (wcar conn# (apply ~cmd args#)))))

(defcmd del)
(defcmd get)
(defcmd keys)
(defcmd llen)
(defcmd lpop)
(defcmd lpush)
(defcmd lrange)
(defcmd rpush)
(defcmd set)
(defcmd hincrby)
(defcmd hgetall)

(defmacro defcmd+ [n]
  `(let [cmd# (with-meta
               (fn [& x#]
                 (carcmd/enqueue-request 1 (into [~(upper-case (name n))] x#)))
               {:redis-api true})]
     (defn ~n [conn# & args#]
       (wcar conn# (apply cmd# args#)))))

(defcmd+ lmove) ; since redis 6.2.0
