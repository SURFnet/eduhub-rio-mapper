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

(ns nl.surf.eduhub-rio-mapper.mapper-integration-test
  (:require
    [clj-http.client :as client]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
    [nl.surf.eduhub-rio-mapper.rio.updated-handler :as updated-handler]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.test-helper :as helper :refer [load-json]]
    [nl.surf.eduhub-rio-mapper.utils.keystore :as keystore])
  (:import [clojure.lang ExceptionInfo]))

(def institution-oin "123O321")
(def rio-opleidingsid "1234O1234")
(def ooapi-id "f2d020bc-5fac-b2e9-4ea7-4b35a08dfbeb")
(def config {:rio-config
             {:credentials   (keystore/credentials "test/keystore.jks"
                                                 "xxxxxx"
                                                 "test-surf"
                                                 "truststore.jks"
                                                 "xxxxxx")
              :recipient-oin "12345"
              :read-url      "http://example.com"
              :update-url    "http://example.com"}})

(defn mock-ooapi-loader [{:keys [eduspec program-course offerings]}]
  (fn [{:keys [::ooapi/type]}]
    (case type
      "education-specification" (load-json eduspec)
      ("course" "program") (load-json program-course)
      (load-json offerings))))

;; resolver takes sender-oin and ooapi-id and returns code
;; ooapi-loader takes request with type and id and returns request
;; mutator takes {:keys [action sender-oin rio-sexp]} returns json
(defn- mock-handle-updated [ooapi-loader]
  (as-> updated-handler/update-mutation $
        (helper/wrap-resolver $ (fn rio-resolver [ootype _id _oin] (if (= ootype "education-specification")
                                                                              rio-opleidingsid
                                                                              "12345678-9abc-def0-1234-56789abcdef0")))
        (helper/wrap-load-entities $ ooapi-loader)))

;; resolver takes sender-oin and ooapi-id and returns code
;; mutator takes {:keys [action sender-oin rio-sexp]} returns json
(defn- mock-handle-deleted [id type institution-oin]
  (let [handle-deleted (as-> updated-handler/deletion-mutation $
                             (helper/wrap-resolver $ (fn rio-resolver [ootype _id _sender-oin] (if (= ootype "education-specification")
                                                                                                          rio-opleidingsid
                                                                                                          "12345678-9abc-def0-1234-56789abcdef0"))))]
    (handle-deleted {::ooapi/id       id
                     ::ooapi/type     type
                     :institution-oin institution-oin})))

(defn- simulate-upsert [ooapi-loader xml-response ooapi-type]
  {:pre [(some? xml-response)]}
  (binding [client/request (constantly {:status 200 :body xml-response})]
    (let [handle-updated (mock-handle-updated ooapi-loader)
          mutation       (handle-updated {::ooapi/id ooapi-id
                                          ::ooapi/type ooapi-type
                                          :institution-oin institution-oin
                                          ::ooapi/education-specification-type "program"})]
      {:result (mutator/mutate! mutation (:rio-config config))
       :mutation mutation})))

(defn- simulate-delete [ooapi-type xml-response]
  {:pre [(some? xml-response)]}
  (binding [client/request (constantly {:status 200 :body xml-response})]
    (let [mutation (mock-handle-deleted ooapi-id ooapi-type institution-oin)]
      (mutator/mutate! mutation (:rio-config config)))))

(deftest test-handle-updated-eduspec-0
  (let [ooapi-loader (mock-ooapi-loader {:eduspec        "fixtures/ooapi/integration-eduspec-0.json"
                                         :program-course nil
                                         :offerings      nil})
        handle-updated (mock-handle-updated ooapi-loader)
        actual (handle-updated {::ooapi/id   ooapi-id
                                ::ooapi/type "education-specification"
                                :institution-oin institution-oin})]
    (is (nil? (:errors actual)))
    (is (= "EN TRANSLATION: Computer Science" (-> actual :ooapi :name first :value)))))

(deftest test-handle-updated-eduspec-upcase
  (let [ooapi-loader (mock-ooapi-loader {:eduspec        "fixtures/ooapi/integration-eduspec-0.json"
                                         :program-course nil
                                         :offerings      nil})
        ooapi-loader #(let [x (ooapi-loader %)] (assoc x :educationSpecificationId (str/upper-case (:educationSpecificationId x))))
        handle-updated (mock-handle-updated ooapi-loader)
        actual (handle-updated {::ooapi/id   "790c6569-2bcc-d046-dae2-7b73e77231f3"
                                ::ooapi/type "education-specification"
                                :institution-oin institution-oin})]
    (is (nil? (:errors actual)))
    (is (= "790c6569-2bcc-d046-dae2-7b73e77231f3" (get-in actual [:rio-sexp 0 4 2 1])))
    (is (= "EN TRANSLATION: Computer Science" (-> actual :ooapi :name first :value)))))


(deftest test-make-eduspec-0
  (let [ooapi-loader (mock-ooapi-loader {:eduspec        "fixtures/ooapi/integration-eduspec-0.json"
                                         :program-course nil
                                         :offerings      nil})
        r (simulate-upsert ooapi-loader
                           (slurp (io/resource "fixtures/rio/integration-eduspec-0.xml"))
                           "education-specification")
        actual (:result r)]
    (is (nil? (:errors actual)))
    (is (= "true" (-> actual :aanleveren_opleidingseenheid_response :requestGoedgekeurd)))))

(deftest test-make-program-0
  (let [ooapi-loader (mock-ooapi-loader {:eduspec        "fixtures/ooapi/integration-eduspec-0.json"
                                         :program-course "fixtures/ooapi/integration-program-0.json"
                                         :offerings      "fixtures/ooapi/integration-program-offerings-0.json"})
        r (simulate-upsert ooapi-loader
                           (slurp (io/resource "fixtures/rio/integratie-program-0.xml"))
                           "program")
        actual (:result r)
        mutation (:mutation r)]
    (is (nil? (:errors actual)))
    (is (= [:duo:cohortcode "34333"] (get-in mutation [:rio-sexp 0 9 1])))
    (is (= "true" (-> actual :aanleveren_aangebodenOpleiding_response :requestGoedgekeurd)))))

(deftest test-make-fake-joint-program
  (let [ooapi-loader (mock-ooapi-loader {:program-course "fixtures/ooapi/integration-program-0.json"
                                         :offerings      "fixtures/ooapi/integration-program-offerings-0.json"})
        ooapi-loader #(-> (ooapi-loader %)
                          (update-in [:consumers 1] merge {:jointProgram true}))
        r (simulate-upsert ooapi-loader
                           (slurp (io/resource "fixtures/rio/integratie-program-0.xml"))
                           "program")
        actual (:result r)
        mutation (:mutation r)]
    (is (nil? (:errors actual)))
    (is (= [:duo:opleidingseenheidSleutel "1234O1234"]
           (first
             (filter (fn [v] (and (vector? v) (= (first v) :duo:opleidingseenheidSleutel)))
                     (-> mutation :rio-sexp first)))))
    (is (= "true" (-> actual :aanleveren_aangebodenOpleiding_response :requestGoedgekeurd)))))

(deftest test-make-joint-program
  (let [ooapi-loader (mock-ooapi-loader {:program-course "fixtures/ooapi/integration-program-0.json"
                                         :offerings      "fixtures/ooapi/integration-program-offerings-0.json"})
        ooapi-loader #(-> (ooapi-loader %)
                          (update-in [:consumers 1] merge {:jointProgram      true
                                                           :educationUnitCode "1234O4323"}))
        r (simulate-upsert ooapi-loader
                           (slurp (io/resource "fixtures/rio/integratie-program-0.xml"))
                           "program")
        actual (:result r)
        mutation (:mutation r)]
    (is (nil? (:errors actual)))
    (is (= [:duo:opleidingseenheidSleutel "1234O4323"]
           (first
             (filter (fn [v] (and (vector? v) (= (first v) :duo:opleidingseenheidSleutel)))
                     (-> mutation :rio-sexp first)))))
    (is (= "true" (-> actual :aanleveren_aangebodenOpleiding_response :requestGoedgekeurd)))))

(deftest test-make-joint-program-without-eduspec
  (let [ooapi-loader (mock-ooapi-loader {:program-course "fixtures/ooapi/integration-program-0.json"
                                         :offerings      "fixtures/ooapi/integration-program-offerings-0.json"})
        ooapi-loader #(-> (ooapi-loader %)
                          (update-in [:consumers 1] merge {:jointProgram      true
                                                           :educationUnitCode "1234O4323"})
                          (dissoc :educationSpecification))
        r (simulate-upsert ooapi-loader
                           (slurp (io/resource "fixtures/rio/integratie-program-0.xml"))
                           "program")
        actual (:result r)
        mutation (:mutation r)]
    (is (nil? (:errors actual)))
    (is (= [:duo:opleidingseenheidSleutel "1234O4323"]
           (first
             (filter (fn [v] (and (vector? v) (= (first v) :duo:opleidingseenheidSleutel)))
                     (-> mutation :rio-sexp first)))))
    (is (= "true" (-> actual :aanleveren_aangebodenOpleiding_response :requestGoedgekeurd)))))

(deftest test-make-joint-program-invalid-code
  (let [ooapi-loader (mock-ooapi-loader {:program-course "fixtures/ooapi/integration-program-0.json"
                                         :offerings      "fixtures/ooapi/integration-program-offerings-0.json"})
        ooapi-loader #(-> (ooapi-loader %)
                          (update-in [:consumers 1] merge {:jointProgram      true
                                                           :educationUnitCode "ZAZA"}))]
    (is (thrown? ExceptionInfo
                 (simulate-upsert ooapi-loader
                                  (slurp (io/resource "fixtures/rio/integratie-program-0.xml"))
                                  "program")))))

(deftest test-remove-eduspec-0
  (let [actual (simulate-delete "education-specification"
                                (slurp (io/resource "fixtures/rio/integration-deletion-eduspec-0.xml")))]
    (is (nil? (:errors actual)))
    (is (= "true" (-> actual :verwijderen_opleidingseenheid_response :requestGoedgekeurd)))))

(deftest test-remove-program-0
  (let [actual (simulate-delete "program"
                                (slurp (io/resource "fixtures/rio/integratie-deletion-program-0.xml")))]
    (is (nil? (:errors actual)))
    (is (= "true" (-> actual :verwijderen_aangebodenOpleiding_response :requestGoedgekeurd)))))
