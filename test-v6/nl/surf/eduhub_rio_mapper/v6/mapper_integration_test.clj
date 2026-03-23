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

(ns nl.surf.eduhub-rio-mapper.v6.mapper-integration-test
  (:require
   [clj-http.client :as client]
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
   [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
   [nl.surf.eduhub-rio-mapper.utils.keystore :as keystore]
   [nl.surf.eduhub-rio-mapper.v6.rio.updated-handler :as updated-handler]
   [nl.surf.eduhub-rio-mapper.v6.specs.ooapi :as ooapi-v6]
   [nl.surf.eduhub-rio-mapper.v6.test-helper :as helper :refer [load-json]])
  (:import [clojure.lang ExceptionInfo]))

(def institution-oin "123O321")
(def rio-opleidingsid "1234O1234")
(def ooapi-id "f2d020bc-5fac-b2e9-4ea7-4b35a08dfbeb")
(def config {:rio-config
             {:credentials   (keystore/credentials "test/keystore.jks"
                                                   "xxxxxx"
                                                   "test-surf")
              :recipient-oin "12345"
              :read-url      "http://example.com"
              :update-url    "http://example.com"}})

(defn mock-ooapi-loader [{:keys [prgspec program-course offerings]}]
  (fn [{:keys [rio-type]}]
    (case rio-type
      :oe (load-json prgspec)
      :ao (load-json program-course)
      (load-json offerings))))

(defn- test-resolver [rio-type]
  (if (= rio-type :oe)
    rio-opleidingsid
    "12345678-9abc-def0-1234-56789abcdef0"))

(defn- simulate-upsert [ooapi-loader xml-response ooapi-type rio-type]
  {:pre [(some? xml-response) rio-type]}
  (binding [client/request (constantly {:status 200 :body xml-response})]
    (let [handle-updated #(helper/test-handler % test-resolver ooapi-loader)
          mutation       (handle-updated {::ooapi/id ooapi-id
                                          ::ooapi/type ooapi-type
                                          :rio-type rio-type
                                          :institution-oin institution-oin
                                          ::ooapi-v6/specification-type "programme"})]
      {:result (mutator/mutate! mutation (:rio-config config))
       :mutation mutation})))

(defn- simulate-delete [ooapi-type rio-type xml-response]
  {:pre [(some? xml-response)]}
  (binding [client/request (constantly {:status 200 :body xml-response})]
    (let [mutation (updated-handler/deletion-mutation (helper/test-resolve-request {::ooapi/id       ooapi-id
                                                                                    ::ooapi/type     ooapi-type
                                                                                    :rio-type        rio-type
                                                                                    :institution-oin institution-oin}
                                                                                   test-resolver))]
      (mutator/mutate! mutation (:rio-config config)))))

(def prgspec-req-0 {:prgspec        "fixtures/ooapi/integration-prgspec-0.json"
                    :program-course nil
                    :offerings      nil})

(def program-req-0 {:program-course "fixtures/ooapi/integration-program-0.json"
                    :offerings      "fixtures/ooapi/integration-program-offerings-0.json"})

(deftest test-handle-updated-prgspec-0
  (let [actual (helper/test-handler {::ooapi/id   ooapi-id
                                     ::ooapi/type "programme"
                                     :rio-type :oe
                                     :institution-oin institution-oin}
                                    test-resolver
                                    (mock-ooapi-loader prgspec-req-0))]
    (is (nil? (:errors actual)))
    (is (= "EN TRANSLATION: Computer Science" (-> actual :ooapi :name first :value)))))

(deftest test-make-prgspec-0
  (let [actual (:result (simulate-upsert (mock-ooapi-loader prgspec-req-0)
                                         (slurp (io/resource "fixtures/rio/integration-prgspec-0.xml"))
                                         "programme"
                                         :oe))]
    (is (nil? (:errors actual)))
    (is (= "true" (-> actual :aanleveren_opleidingseenheid_response :requestGoedgekeurd)))))

(deftest test-make-program-0
  (let [ooapi-loader (mock-ooapi-loader {:prgspec        "fixtures/ooapi/integration-prgspec-0.json"
                                         :program-course "fixtures/ooapi/integration-program-0.json"
                                         :offerings      "fixtures/ooapi/integration-program-offerings-0.json"})
        {:keys [result _mutation]} (simulate-upsert ooapi-loader
                                                   (slurp (io/resource "fixtures/rio/integratie-program-0.xml"))
                                                   "programme"
                                                    :ao)]
    (is (nil? (:errors result)))
    (is (= "true" (-> result :aanleveren_aangebodenOpleiding_response :requestGoedgekeurd)))))

(deftest test-joint-programme
  (let [ooapi-loader (mock-ooapi-loader program-req-0)
        upserter #(simulate-upsert % (slurp (io/resource "fixtures/rio/integratie-program-0.xml")) "programme" :ao)
        goedgekeurd? (fn [result] (= "true" (-> result :aanleveren_aangebodenOpleiding_response :requestGoedgekeurd)))
        extract-opleidingseenheidsleutel (fn [mutation]
                                           (first
                                            (filter (fn [v] (and (vector? v) (= (first v) :duo:opleidingseenheidSleutel)))
                                                    (-> mutation :rio-sexp first))))
        set-joint-programme-in-consumer (fn [entity unit-code]
                                        (update entity
                                                :consumer
                                                merge
                                                (cond-> {:jointProgramme true}
                                                  unit-code
                                                  (assoc :educationUnitCode unit-code))))]

    (testing "fake joint programme"
      ;; after loading program, set jointProgramme to true
      (let [ooapi-loader #(-> (ooapi-loader %)
                              (set-joint-programme-in-consumer nil))
            {:keys [result mutation]} (upserter ooapi-loader)]
        (is (nil? (:errors result)))
        (is (= [:duo:opleidingseenheidSleutel "1234O1234"]
               (extract-opleidingseenheidsleutel mutation)))
        (is (goedgekeurd? result))))

    (testing "normal joint programme"
      ;; after loading program, set jointProgramme to true
      (let [ooapi-loader #(-> (ooapi-loader %)
                              (set-joint-programme-in-consumer "1234O4323"))
            {:keys [result mutation]} (upserter ooapi-loader)]
        (is (nil? (:errors result)))
        (is (= [:duo:opleidingseenheidSleutel "1234O4323"]
               (extract-opleidingseenheidsleutel mutation)))
        (is (goedgekeurd? result))))

    (testing "joint-program-without-prgspec"
      (let [ooapi-loader #(-> (ooapi-loader %)
                              (set-joint-programme-in-consumer "1234O4323")
                              (dissoc :educationSpecification))
            {:keys [result mutation]} (upserter ooapi-loader)]
        (is (nil? (:errors result)))
        (is (= [:duo:opleidingseenheidSleutel "1234O4323"]
               (extract-opleidingseenheidsleutel mutation)))
        (is (goedgekeurd? result))))

    (testing "joint-program-invalid-code"
      (let [ooapi-loader #(-> (ooapi-loader %)
                              (set-joint-programme-in-consumer "ZAZA"))]
        (is (thrown? ExceptionInfo
                     (upserter ooapi-loader)))))))

(deftest test-remove-prgspec-0
  (let [actual (simulate-delete "programme" :oe
                                (slurp (io/resource "fixtures/rio/integration-deletion-prgspec-0.xml")))]
    (is (nil? (:errors actual)))
    (is (= "true" (-> actual :verwijderen_opleidingseenheid_response :requestGoedgekeurd)))))

(deftest test-remove-program-0
  (let [actual (simulate-delete "programme" :ao
                                (slurp (io/resource "fixtures/rio/integratie-deletion-program-0.xml")))]
    (is (nil? (:errors actual)))
    (is (= "true" (-> actual :verwijderen_aangebodenOpleiding_response :requestGoedgekeurd)))))
