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

(defn mock-ooapi-loader-simple [{:keys [eduspec program-course offerings]} ooapi-type]
  (case ooapi-type
    "education-specification" (load-json eduspec)
    ("course" "program") (load-json program-course)
    (load-json offerings)))

(defn- test-resolver [ootype]
  (if (= ootype "education-specification")
    rio-opleidingsid
    "12345678-9abc-def0-1234-56789abcdef0"))

(defn- simulate-upsert [ooapi-loader xml-response ooapi-type]
  {:pre [(some? xml-response)]}
  (binding [client/request (constantly {:status 200 :body xml-response})]
    (let [handle-updated #(helper/test-handler % test-resolver ooapi-loader)
          mutation       (handle-updated {::ooapi/id ooapi-id
                                          ::ooapi/type ooapi-type
                                          :institution-oin institution-oin
                                          ::ooapi/education-specification-type "program"})]
      {:result (mutator/mutate! mutation (:rio-config config))
       :mutation mutation})))

(defn- simulate-delete [ooapi-type xml-response]
  {:pre [(some? xml-response)]}
  (binding [client/request (constantly {:status 200 :body xml-response})]
    (let [mutation (updated-handler/deletion-mutation (helper/test-resolve-request {::ooapi/id       ooapi-id
                                                                                    ::ooapi/type     ooapi-type
                                                                                    :institution-oin institution-oin}
                                                                                   test-resolver))]
      (mutator/mutate! mutation (:rio-config config)))))

(def eduspec-req-0 {:eduspec        "fixtures/ooapi/integration-eduspec-0.json"
                    :program-course nil
                    :offerings      nil})

(def program-req-0 {:program-course "fixtures/ooapi/integration-program-0.json"
                    :offerings      "fixtures/ooapi/integration-program-offerings-0.json"})

(deftest test-handle-updated-eduspec-0
  (let [actual (helper/test-handler {::ooapi/id   ooapi-id
                                     ::ooapi/type "education-specification"
                                     :institution-oin institution-oin}
                                    test-resolver
                                    (mock-ooapi-loader eduspec-req-0))]
    (is (nil? (:errors actual)))
    (is (= "EN TRANSLATION: Computer Science" (-> actual :ooapi :name first :value)))))

(deftest test-handle-updated-eduspec-upcase
  (let [ooapi-loader (mock-ooapi-loader eduspec-req-0)
        ooapi-loader #(let [x (ooapi-loader %)] (assoc x :educationSpecificationId (str/upper-case (:educationSpecificationId x))))
        actual (helper/test-handler {::ooapi/id   "790c6569-2bcc-d046-dae2-7b73e77231f3"
                                     ::ooapi/type "education-specification"
                                     :institution-oin institution-oin}
                                    test-resolver
                                    ooapi-loader)]
    (is (nil? (:errors actual)))
    (is (= "790c6569-2bcc-d046-dae2-7b73e77231f3" (get-in actual [:rio-sexp 0 4 2 1])))
    (is (= "EN TRANSLATION: Computer Science" (-> actual :ooapi :name first :value)))))


(deftest test-make-eduspec-0
  (let [actual (:result (simulate-upsert (mock-ooapi-loader eduspec-req-0)
                                         (slurp (io/resource "fixtures/rio/integration-eduspec-0.xml"))
                                         "education-specification"))]
    (is (nil? (:errors actual)))
    (is (= "true" (-> actual :aanleveren_opleidingseenheid_response :requestGoedgekeurd)))))

(deftest test-make-program-0
  (let [ooapi-loader (mock-ooapi-loader {:eduspec        "fixtures/ooapi/integration-eduspec-0.json"
                                         :program-course "fixtures/ooapi/integration-program-0.json"
                                         :offerings      "fixtures/ooapi/integration-program-offerings-0.json"})
        {:keys [result mutation]} (simulate-upsert ooapi-loader
                                                   (slurp (io/resource "fixtures/rio/integratie-program-0.xml"))
                                                   "program")]
    (is (nil? (:errors result)))
    (is (= [:duo:cohortcode "34333"] (get-in mutation [:rio-sexp 0 9 1])))
    (is (= "true" (-> result :aanleveren_aangebodenOpleiding_response :requestGoedgekeurd)))))

(deftest test-joint-program
  (let [ooapi-loader (mock-ooapi-loader program-req-0)
        upserter #(simulate-upsert % (slurp (io/resource "fixtures/rio/integratie-program-0.xml")) "program")
        goedgekeurd? (fn [result] (= "true" (-> result :aanleveren_aangebodenOpleiding_response :requestGoedgekeurd)))
        extract-opleidingseenheidsleutel (fn [mutation]
                                           (first
                                             (filter (fn [v] (and (vector? v) (= (first v) :duo:opleidingseenheidSleutel)))
                                                     (-> mutation :rio-sexp first))))
        set-joint-program-in-consumers (fn [entity unit-code]
                                         (update-in entity
                                                    [:consumers 1]
                                                    merge
                                                    (cond-> {:jointProgram true}
                                                            unit-code
                                                            (assoc :educationUnitCode unit-code))))]

    (testing "fake joint program"
      ;; after loading program, set jointProgram to true
      (let [ooapi-loader #(-> (ooapi-loader %)
                              (set-joint-program-in-consumers nil))
            {:keys [result mutation]} (upserter ooapi-loader)]
        (is (nil? (:errors result)))
        (is (= [:duo:opleidingseenheidSleutel "1234O1234"]
               (extract-opleidingseenheidsleutel mutation)))
        (is (goedgekeurd? result))))

    (testing "normal joint program"
      ;; after loading program, set jointProgram to true
      (let [ooapi-loader #(-> (ooapi-loader %)
                              (set-joint-program-in-consumers "1234O4323"))
            {:keys [result mutation]} (upserter ooapi-loader)]
        (is (nil? (:errors result)))
        (is (= [:duo:opleidingseenheidSleutel "1234O4323"]
               (extract-opleidingseenheidsleutel mutation)))
        (is (goedgekeurd? result))))

    (testing "joint-program-without-eduspec"
      (let [ooapi-loader #(-> (ooapi-loader %)
                              (set-joint-program-in-consumers "1234O4323")
                              (dissoc :educationSpecification))
            {:keys [result mutation]} (upserter ooapi-loader)]
        (is (nil? (:errors result)))
        (is (= [:duo:opleidingseenheidSleutel "1234O4323"]
               (extract-opleidingseenheidsleutel mutation)))
        (is (goedgekeurd? result))))

    (testing "joint-program-invalid-code"
      (let [ooapi-loader #(-> (ooapi-loader %)
                              (set-joint-program-in-consumers "ZAZA"))]
        (is (thrown? ExceptionInfo
                     (upserter ooapi-loader)))))))

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
