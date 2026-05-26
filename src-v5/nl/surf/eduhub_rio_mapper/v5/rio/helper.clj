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

(ns nl.surf.eduhub-rio-mapper.v5.rio.helper
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io PushbackReader]))

(defn edn-read-resource [resource]
  (edn/read (PushbackReader. (io/reader (io/resource resource)))))

(def specifications (edn-read-resource "ooapi-mappings-v5.edn"))

(defn ooapi-mapping? [name]
  (boolean (get-in specifications [:mappings name])))

(defn ooapi-mapping
  "Look up the matching rio key for given ooapi key (or keys) of rio type `name` (ooapi-mappings.edn)."
  [name key]
  {:pre [(string? name)]}
  (when key
    (if (coll? key)
      (mapv #(get-in specifications [:mappings name %]) key)
      (get-in specifications [:mappings name key]))))

;; Helpers

(defn level-sector-mapping
  "Map level and sector to RIO `niveau`.

  Returns nil on invalid level+sector mapping."
  [level sector]
  (case level
    "undefined" "ONBEPAALD"
    "nt2-1" "NT2-I"
    "nt2-2" "NT2-II"
    (case sector
      "secondary vocational education"
      (case level
        "secondary vocational education" "MBO"
        "secondary vocational education 1" "MBO-1"
        "secondary vocational education 2" "MBO-2"
        "secondary vocational education 3" "MBO-3"
        "secondary vocational education 4" "MBO-4"
        nil)

      "higher professional education"
      (case level
        "associate degree" "HBO-AD"
        "bachelor" "HBO-BA"
        "master" "HBO-MA"
        "doctoral" "HBO-PM"
        "undivided" "HBO-O"
        nil)

      "university education"
      (case level
        "bachelor" "WO-BA"
        "master" "WO-MA"
        "doctoral" "WO-PM"
        "undivided" "WO-O"
        nil)
      nil)))
