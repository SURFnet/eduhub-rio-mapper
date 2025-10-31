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

(ns nl.surf.eduhub-rio-mapper.re-spec
  "Define a spec with a generator for a regular expression."
  (:require [clojure.spec.alpha :as s]
            [miner.strgen :as strgen]))

(defmacro re-spec
  "Defines a spec with a generator for regular expression `re`.

  This is a macro and not a function, because using a macro will print
  the literal regular expression used in spec failures."
  [re]
  `(s/spec (s/and string? #(re-matches ~re %))
           :gen #(strgen/string-generator ~re)))

(defn looks-like-html?
  "Test if a text string contains HTML constructs."
  [s]
  (re-find #"(<(\S|\Z))|(&\S+;)" s))

(defn length-between?
  [min-length max-length s]
  (<= min-length (count s) max-length))

(defmacro text-spec
  "Define a string spec with a minimum and maximum length.

  Also ensures that the string does not contain any text sequences
  that are considered invalid by the RIO API (meaning, we disallow
  anything that looks like HTML tags or escape codes)

  This is a macro and not a function so that min-length and max-length
  will be reported in spec errors."
  [min-length max-length]
  `(s/spec (s/and string?
                  #(length-between? ~min-length ~max-length %)
                  #(not (looks-like-html? %)))))
