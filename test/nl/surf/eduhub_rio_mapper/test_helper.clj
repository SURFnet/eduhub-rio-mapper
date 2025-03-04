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

(ns nl.surf.eduhub-rio-mapper.test-helper
  (:require
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [clojure.test :refer :all]
    [environ.core :refer [env]]
    [nl.surf.eduhub-rio-mapper.config :as config]
    [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
    [nl.surf.eduhub-rio-mapper.rio.updated-handler :as updated-handler]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as-alias ooapi]
    [nl.surf.eduhub-rio-mapper.specs.rio :as-alias rio])
  (:import
    [java.io PushbackReader]))

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
               (ooapi.loader/load-entities (ooapi.loader/validating-loader ooapi-loader)))
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

(defn- ls [dir-name]
  (map #(.getName %) (.listFiles (io/file dir-name))))

(defn only-one-if-any [list]
  (assert (< (count list) 2) (prn-str list))
  (first list))

(defn- numbered-file [basedir nr]
  {:post [(some? %)]}
  (let [filename (->> basedir
                      (ls)
                      (filter #(.startsWith % (str nr "-")))
                      (only-one-if-any))]
    (when-not filename (throw (ex-info (format "No recorded request found for dir %s nr %d" basedir nr) {})))
    (str basedir "/" filename)))

(defn req-name [request]
  (let [action (get-in request [:headers "SOAPAction"])]
    (if action
      (last (str/split action #"/"))
      (-> request :url
          (subs (count "https://gateway.test.surfeduhub.nl/"))
          (str/replace \/ \-)
          (str/split #"\?")
          first))))

(defn- make-playbacker [root idx _]
  (let [count-atom (atom 0)
        dir        (numbered-file root idx)]
    (fn [_ actual-request]
      (let [i                (swap! count-atom inc)
            fname            (numbered-file dir i)
            recording        (with-open [r (io/reader fname)] (edn/read (PushbackReader. r)))
            recorded-request (:request recording)]
        (doseq [property-path [[:url] [:method] [:headers "SOAPAction"]]]
          (let [expected (get-in recorded-request property-path)
                actual   (get-in actual-request property-path)]
            (is (= expected actual)
                (str "Unexpected property " (last property-path)))))
        (:response recording)))))

(defn- make-recorder [root idx desc]
  (let [mycounter (atom 0)]
    (fn [handler request]
      (let [response  (handler request)
            counter   (swap! mycounter inc)
            file-name (str root "/" idx "-" desc "/" counter "-" (req-name request) ".edn")
            headers   (select-keys (:headers request) ["SOAPAction" "X-Route"])]
        (io/make-parents file-name)
        (with-open [w (io/writer file-name)]
          (pprint {:request  (assoc (select-keys request [:method :url :body])
                               :headers headers)
                   :response (select-keys response [:status :body])}
                  w))
        response))))

(defn make-vcr [method]
  (case method
    :playback make-playbacker
    :record   make-recorder))

;; The truststore used for unit tests has a different password than the official password.
;; The truststore (as opposed to the keystore) does not contain sensitive or private data.
(defn make-test-config []
  (config/make-config (assoc env :truststore-password "xxxxxx")))
