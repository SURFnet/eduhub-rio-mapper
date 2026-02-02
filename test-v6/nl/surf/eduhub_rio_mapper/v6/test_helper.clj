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

(ns nl.surf.eduhub-rio-mapper.v6.test-helper
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [environ.core :refer [env]]
   [nl.surf.eduhub-rio-mapper.specs.ooapi :as-alias ooapi]
   [nl.surf.eduhub-rio-mapper.specs.rio :as-alias rio]
   [nl.surf.eduhub-rio-mapper.v6.config :as config]
   [nl.surf.eduhub-rio-mapper.v6.ooapi.loader :as ooapi.loader]
   [nl.surf.eduhub-rio-mapper.v6.rio.updated-handler :as updated-handler]))

(defn load-json [path]
  (some-> path
          io/resource
          slurp
          (json/read-str :key-fn keyword)))

(defn test-resolve-request [{::ooapi/keys [type] ::rio/keys [opleidingscode] :as request} resolver]
  (cond-> request
    (#{"course" "program"} type)
    (assoc ::rio/aangeboden-opleiding-code
           (resolver type))

    :always
    (assoc ::rio/opleidingscode
           (or opleidingscode
               (resolver "education-specification")))))

(def test-client-info {:client-id              "rio-mapper-dev.jomco.nl"
                       :institution-schac-home "demo06.test.surfeduhub.nl"
                       :institution-oin        "0000000700025BE00000"})

(defn test-handler
  "Loads ooapi fixtures from file and fakes resolver."
  [{::ooapi/keys [type] :as req} resolver ooapi-loader]
  (-> (cond->> (merge req test-client-info)
               (not= "relation" type)
               (ooapi.loader/load-entities ooapi-loader))
      (test-resolve-request resolver)
      updated-handler/update-mutation))

(defn wait-while-predicate [predicate val-atom max-sec]
  (loop [ttl (* max-sec 10)]
    (when (and (pos? ttl) (predicate @val-atom))
      (Thread/sleep 100)
      (recur (dec ttl)))))

(defn wait-for-expected [expected val-atom max-sec]
  (wait-while-predicate #(not= % expected) val-atom max-sec)
  (is (= expected @val-atom)))

;; The truststore used for unit tests has a different password than the official password.
;; The truststore (as opposed to the keystore) does not contain sensitive or private data.
(defn make-test-config []
  (config/make-config (assoc env :keystore "test/keystore.jks"
                             :keystore-password "xxxxxx"
                             :truststore-password "xxxxxx"
                             :keystore-alias "test-surf")))
