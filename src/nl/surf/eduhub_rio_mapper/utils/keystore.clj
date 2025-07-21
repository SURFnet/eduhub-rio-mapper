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
  (:import [java.security KeyStore
                          KeyStore$PasswordProtection
                          KeyStore$PrivateKeyEntry]))

(defn- validate-keystore [ks]
  (when (< 1 (.size ks))
    (throw (ex-info (str "Keystore contains multiple entries.\n"
                          "Since the latest clj-http (3.13.1) doesn't support aliases, use only 1 entry in a keystore file.\n"
                          "For more info: https://github.com/dakrone/clj-http/issues/656")
                    {}))))

(defn keystore
  ^KeyStore [path password]
  (with-open [in (io/input-stream path)]
    (doto (KeyStore/getInstance "JKS")
      (.load in (char-array password))
      (validate-keystore))))

(defn keystore-resource
  ^KeyStore [resource-path password]
  (with-open [in (io/input-stream (io/resource resource-path))]
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
  {:pre [keystore alias password]}
  (-> (get-entry keystore alias password)
      .getCertificate
      .getEncoded))

(defn credentials
  [keystore-path keystore-pass keystore-alias]
  {:post [(some? (:certificate %))]}
  (let [ks (keystore keystore-path keystore-pass)]
    {:keystore        ks
     :keystore-alias  keystore-alias
     :trust-store     (keystore-resource "truststore.jks" "")
     :keystore-pass   keystore-pass
     :trust-store-pass ""
     :private-key     (get-key ks keystore-alias keystore-pass)
     :certificate     (get-certificate ks keystore-alias keystore-pass)}))
