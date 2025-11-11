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

(ns nl.surf.eduhub-rio-mapper.utils.rio-utils
  (:require [clojure.tools.logging :as log]
            [nl.surf.eduhub-rio-mapper.utils.logging :as logging]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils])
  (:import (org.w3c.dom Element)))

(defn goedgekeurd? [^Element element]
  {:pre [element]}
  (= "true" (xml-utils/single-xml-unwrapper element "ns2:requestGoedgekeurd")))

(defn log-rio-action-response [msg element]
  (logging/with-mdc
    {:identificatiecodeBedrijfsdocument (xml-utils/single-xml-unwrapper element "ns2:identificatiecodeBedrijfsdocument")}
    (log/debugf (format "RIO %s; SUCCESS: %s" msg (goedgekeurd? element)))))
