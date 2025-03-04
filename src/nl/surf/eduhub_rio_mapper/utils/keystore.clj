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

(ns nl.surf.eduhub-rio-mapper.utils.keystore
  (:require [clojure.java.io :as io])
  (:import (java.security KeyStore
                          KeyStore$PasswordProtection
                          KeyStore$PrivateKeyEntry)))

(defn keystore
  ^KeyStore [path password]
  (with-open [in (io/input-stream path)]
    (doto (KeyStore/getInstance "JKS")
      (.load in (char-array password)))))

(defn- get-entry
  ^KeyStore$PrivateKeyEntry [^KeyStore keystore alias password]
  (.getEntry keystore
             alias
             (KeyStore$PasswordProtection. (char-array password))))

(defn get-key
  [^KeyStore keystore alias password]
  (.getKey keystore alias (char-array password)))

(defn get-certificate
  [^KeyStore keystore alias password]
  (-> keystore
      (get-entry alias password)
      .getCertificate
      .getEncoded))

(defn credentials
  [keystore-path keystore-pass keystore-alias
   trust-store-path trust-store-pass]
  {:post [(some? (:certificate %))]}
  (let [ks (keystore keystore-path keystore-pass)]
    {:keystore        ks
     :trust-store     (keystore trust-store-path trust-store-pass)
     :keystore-pass   keystore-pass
     :trust-store-pass trust-store-pass
     :private-key     (get-key ks keystore-alias keystore-pass)
     :certificate     (get-certificate ks keystore-alias keystore-pass)}))
