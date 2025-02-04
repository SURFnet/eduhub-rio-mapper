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
    [nl.surf.eduhub-rio-mapper.ooapi.base :as ooapi-base]
    [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as-alias ooapi]
    [nl.surf.eduhub-rio-mapper.specs.rio :as-alias rio])
  (:import
    [java.io PushbackReader]))

(defn load-json [path]
  (some-> path
          io/resource
          slurp
          (json/read-str :key-fn keyword)))

(defn wrap-load-entities
  [f ooapi-loader]
  (let [loader (ooapi.loader/validating-loader ooapi-loader)]
    (fn wrapped-load-entities [{:keys [::ooapi/type] :as request}]
      (f
        (cond->> request
                 (not= "relation" type) (ooapi.loader/load-entities loader))))))

(defn wrap-resolver
  "Get the RIO opleidingscode and aangeboden opleiding code for the given entity.

  Inserts the codes in the request as ::rio/opleidingscode
  and ::rio/aangeboden-opleiding-code (if entity is a course or
  program)."
  [f resolver]
  (fn with-resolver [{:keys [institution-oin] ::ooapi/keys [type id entity] ::rio/keys [opleidingscode] :as request}]
    (f (cond-> request
               (#{"course" "program"} type)
               (assoc ::rio/aangeboden-opleiding-code
                      (resolver type id institution-oin))

               :always
               (assoc ::rio/opleidingscode
                      (or opleidingscode
                          (resolver "education-specification"
                                    (if (= type "education-specification")
                                      id
                                      (ooapi-base/education-specification-id entity))
                                    institution-oin)))))))

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
