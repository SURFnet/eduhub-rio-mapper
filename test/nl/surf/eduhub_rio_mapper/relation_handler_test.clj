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

(ns nl.surf.eduhub-rio-mapper.relation-handler-test
  (:require
    [clj-http.client :as client]
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
    [nl.surf.eduhub-rio-mapper.rio.relation-handler :as rh]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
    [nl.surf.eduhub-rio-mapper.utils.keystore :as keystore]
    [nl.surf.eduhub-rio-mapper.utils.soap :as soap]))

(def education-specification (-> "fixtures/ooapi/education-specification.json"
                                 io/resource
                                 slurp
                                 (json/read-str :key-fn keyword)))

(deftest relation-differences
  (testing "parent no existing relations"
    (let [actual-relations #{}
          {:keys [missing superfluous]} (rh/relation-differences
                                         (assoc education-specification ::rio/opleidingscode "234O432")
                                          :parent
                                          [(assoc education-specification ::rio/opleidingscode "654O456")]
                                          actual-relations)]
      (is (= missing #{{:opleidingseenheidcodes #{"234O432" "654O456"}, :valid-from "2019-08-24", :valid-to "2019-08-24"}}))
      (is (= superfluous #{}))))

  (testing "parent with existing relations"
    (let [actual-relations #{{:opleidingseenheidcodes #{"234O432" "654O456"}, :valid-from "2019-08-24", :valid-to "2019-08-24"}}
          {:keys [missing superfluous]} (rh/relation-differences
                                         (assoc education-specification ::rio/opleidingscode "234O432")
                                          :parent
                                          [(assoc education-specification ::rio/opleidingscode "654O456")]
                                          actual-relations)]
      (is (= missing #{}))
      (is (= superfluous #{}))))

  (testing "parent with inverted relation"
    (let [actual-relations #{{:opleidingseenheidcodes #{"234O432" "654O456"}, :valid-from "2019-08-24", :valid-to "2019-08-24"}}
          {:keys [missing superfluous]} (rh/relation-differences
                                          (assoc education-specification ::rio/opleidingscode "654O456")
                                          :parent
                                          [(assoc education-specification ::rio/opleidingscode "234O432")]
                                          actual-relations)]
      (is (= missing #{}))
      (is (= superfluous #{}))))

  (testing "parent with existing relations different start date"
    (let [actual-relations #{{:opleidingseenheidcodes #{"234O432" "654O456"}, :valid-from "2011-08-24", :valid-to "2019-08-24"}}
          {:keys [missing superfluous]} (rh/relation-differences
                                          (assoc education-specification ::rio/opleidingscode "234O432")
                                          :parent
                                          [(assoc education-specification ::rio/opleidingscode "654O456")]
                                          actual-relations)]
      (is (= missing #{(assoc (first actual-relations) :valid-from "2019-08-24")}))
      (is (= superfluous actual-relations)))))

(defn- child [id parent-id valid-from & {:keys [] :as opts}]
  (merge opts
         {:educationSpecificationId id, :parent parent-id, :validFrom valid-from,
          :educationSpecificationType "program", :consumers [{:consumerKey "rio"
                                                              :educationSpecificationSubType "variant"}]}))

(defn- parent [id children-ids valid-from & {:keys [] :as opts}]
  (merge opts
         {:educationSpecificationId id, :children children-ids, :validFrom valid-from,
          :educationSpecificationType "program"}))

(defn mutate-relation
  [eduspec job handlers]
  (-> (rh/relation-mutations eduspec job handlers)
      (rh/mutate-relations! job handlers)))

(deftest test-mutate-relation
  (let [job      {:institution-schac-home "a" :institution-oin "b"}
        loader   {1 (child 1 2 "2022-01-01")
                  2 (parent 2 [1] "2022-01-01")
                  3 (parent 3 [4 5] "2022-01-01")
                  4 (child 4 3 "2022-01-01")
                  5 (child 5 3 "2022-01-01")}
        handlers {:resolver     (fn [_type id _oin]
                                  (case id
                                    1 "1234O1234"
                                    2 "2234O1234"
                                    3 "3234O1234"
                                    4 "4234O1234"
                                    5 "5234O1234"))
                  :ooapi-loader (fn [{::ooapi/keys [id]}] (loader id))
                  ;; actual relations
                  :getter       (fn [{::rio/keys [opleidingscode]}]
                                  (case opleidingscode
                                    "1234O1234" []
                                    "2234O1234" []
                                    "3234O1234" []
                                    "4234O1234" []
                                    "5234O1234" []))
                  :rio-config   {:recipient-oin "1"
                                 :read-url      "http://example.com"
                                 :update-url    "http://example.com"
                                 :credentials   (keystore/credentials
                                                 "test/keystore.jks"
                                                 "xxxxxx"
                                                 "test-surf")}}]
    (binding [client/request (constantly {:status 200 :body (slurp "test/fixtures/rio/create-relation.xml")})]
      (testing "child with one parent"
        (let [{:keys [missing superfluous]} (mutate-relation (loader 1) job handlers)]
          (is (empty? superfluous))
          (is (= missing #{{:valid-from "2022-01-01", :valid-to nil, :opleidingseenheidcodes #{"2234O1234" "1234O1234"}}}))))

      (testing "parent with one child"
        (let [{:keys [missing superfluous]} (mutate-relation (loader 2) job handlers)]
          (is (empty? superfluous))
          (is (= missing #{{:valid-from "2022-01-01", :valid-to nil, :opleidingseenheidcodes #{"2234O1234" "1234O1234"}}}))))

      (testing "parent with two children"
        (let [{:keys [missing superfluous]} (mutate-relation (loader 3) job handlers)]
          (is (empty? superfluous))
          (is (= missing #{{:valid-from "2022-01-01", :valid-to nil, :opleidingseenheidcodes #{"3234O1234" "4234O1234"}}
                           {:valid-from "2022-01-01", :valid-to nil, :opleidingseenheidcodes #{"3234O1234" "5234O1234"}}})))))))

(deftest test-relation-mutation
  (testing "Valid call to delete relation"
    (let [sender-oin "4783648273648372"
          recipient-oin "5783648273648372"
          credentials (keystore/credentials "test/keystore.jks" "xxxxxx" "test-surf")
          actual (rh/relation-mutation
                   :delete
                   sender-oin
                   {:opleidingseenheidcodes #{"1234O1234" "2234O2234"}
                    :valid-from             "2022-10-10"})
          xml-or-error (soap/prepare-soap-call (:action actual)
                                               (:rio-sexp actual)
                                               (mutator/make-datamap sender-oin recipient-oin)
                                               credentials)]
      ;; If string, passed xsd validation, otherwise error-map
      (is (string? xml-or-error)))))
