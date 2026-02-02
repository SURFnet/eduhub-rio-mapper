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

;;
;; INTERACTION TESTS - RECORD MODE
;;
;; These tests use remote-entities-fixture to load OOAPI entities from ObjectStore
;; via the gateway, similar to e2e tests.
;;
;; PLAYBACK MODE (default):
;; - Uses pre-recorded VCR cassettes from test-common/fixtures/interaction/
;; - No network calls are made
;; - Tests run quickly and don't require external services
;;
;; RECORD MODE:
;; - Makes real HTTP calls to gateway and ObjectStore
;; - Records new VCR cassettes for later playback
;; - Requires environment variables (same as e2e tests):
;;
;;   # Gateway credentials
;;   GATEWAY_ROOT_URL=https://gateway.test.surfeduhub.nl/
;;   GATEWAY_USER=<username>
;;   GATEWAY_PASSWORD=<password>
;;
;;   # ObjectStore credentials (for uploading fixtures)
;;   OS_AUTH_URL=<openstack-keystone-url>
;;   OS_USERNAME=<username>
;;   OS_PASSWORD=<password>
;;   OS_PROJECT_NAME=<project>
;;   OS_CONTAINER_NAME=rio-mapper-test-suite
;;
;; To run in RECORD mode:
;; 1. Set environment variables (see above)
;; 2. Modify helper/make-vcr call in tests from :playback to :record
;; 3. Run the tests - new VCR cassettes will be recorded
;; 4. Restore :playback mode for normal test runs
;;
;; The remote entities are defined in:
;;   test-common/fixtures/remote-entities/interaction/
;;
;;

(ns nl.surf.eduhub-rio-mapper.v5.interaction-test
  (:require
   [clojure.test :refer :all]
   [environ.core :refer [env]]
   [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
   [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote-entities :refer [remote-entities-fixture]]
   [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
   [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
   [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
   [nl.surf.eduhub-rio-mapper.v5.commands.processing :as processing]
   [nl.surf.eduhub-rio-mapper.v5.config :as config]
   [nl.surf.eduhub-rio-mapper.v5.job :as job]
   [nl.surf.eduhub-rio-mapper.v5.ooapi.loader :as ooapi.loader]
   [nl.surf.eduhub-rio-mapper.v5.rio.loader :as rio.loader]
   [nl.surf.eduhub-rio-mapper.v5.test-helper :as helper]
   [nl.surf.eduhub-rio-mapper.vcr-helper :as vcr.helper])
  (:import [clojure.lang ExceptionInfo]
           [java.net URI]))

(when (= :record vcr.helper/vcr-mode)
  (use-fixtures :once remote-entities-fixture))

(defn- load-relations [getter client-info code]
  {:pre [(string? code)]}
  (getter {::rio/type           "opleidingsrelatiesBijOpleidingseenheid"
           :institution-oin     (:institution-oin client-info)
           ::rio/opleidingscode code}))

(def name-of-ootype
  {:eduspec "education-specification"
   :course  "course"
   :program "program"})

(defn- make-runner [handlers client-info config http-logging-enabled]
  (fn run [ootype id action]
    {:pre [id (not= "" id)]}
    ;; id is atom for relations, UUID otherwise

    (if (= ootype :relation)
      (load-relations (:getter handlers) client-info @id)
      (job/run! handlers
                (merge client-info
                       {::ooapi/id   (str id)
                        ::ooapi/type (name-of-ootype ootype)
                        ::ooapi/root-url (:gateway-root-url config)
                        :gateway-credentials (:gateway-credentials config)
                        :action      action})
                http-logging-enabled))))

(defn- entity-name-to-id [name]
  (vcr.helper/entity-name-to-id name :v5))

(deftest ^:vcr interaction-test
  (let [vcr                  (vcr.helper/make-vcr)
        config               (if (= vcr.helper/vcr-mode :record)
                               (config/make-config env)
                               (assoc-in (helper/make-test-config) [:rio-config :rio-retry-attempts-seconds] [1 1 1 1]))
        client-info          (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config           (:rio-config config)
        handlers             (processing/make-handlers {:rio-config rio-config
                                                        :gateway-root-url (:gateway-root-url config)
                                                        :gateway-credentials (:gateway-credentials config)})
        eduspec-parent-id    (entity-name-to-id "education-specifications/interaction-eduspec-parent")
        eduspec-child-id     (entity-name-to-id "education-specifications/interaction-eduspec-child")
        program-id           (entity-name-to-id "programs/interaction-program-some")

        runner               (make-runner handlers
                                          client-info
                                          config
                                          false)
        goedgekeurd?         #(= "true" (-> % vals first :requestGoedgekeurd))
        code                 (atom nil) ; During the tests we'll learn which opleidingscode we should use.

        commands            [[1 "upsert" :eduspec  eduspec-parent-id goedgekeurd?]
                             [2 "upsert" :eduspec  eduspec-child-id  goedgekeurd?]
                             ;; TODO upsert shouldn't be final until relation updates have been observed
                             ;; but now, RIO needs 5 seconds for the changes to be visible, therefore sleep in record mode
                             [nil "sleep" nil nil nil]
                             [3 "get"    :relation code              identity]
                             [4 "delete" :eduspec  eduspec-child-id  goedgekeurd?]
                             [nil "sleep" nil nil nil]
                             [5 "get"    :relation code              nil?]
                             [6 "upsert" :program  program-id        goedgekeurd?]
                             [7 "delete" :program  program-id        goedgekeurd?]
                             [8 "delete" :eduspec  eduspec-parent-id goedgekeurd?]
                             [9 "upsert" :program  program-id        #(= (-> % :errors :message)
                                                                         (str "No 'opleidingseenheid' found in RIO with eigensleutel: " eduspec-parent-id))]]]
    (doseq [[idx action ootype id pred?] commands]
      (testing (str "Command " idx " " action " " id)
        (if (= "sleep" action)
          (when (= vcr.helper/vcr-mode :record)
            (Thread/sleep 5000))
          (binding [http-utils/*vcr* (vcr "test-v5/fixtures/vcr/interaction" idx (str action "-" (name ootype)))]
            (let [result  (runner ootype id action)
                        http-messages (:http-messages result)
                        oplcode (-> result :aanleveren_opleidingseenheid_response :opleidingseenheidcode)]
                    (println "oplcode" oplcode)
                    (is (or oplcode
                            (not= "upsert" action)
                            (not= :eduspec ootype))
                        (str "Expected oplcode in " (prn-str result)))
                    (when oplcode (swap! code #(if (nil? %) oplcode %))) ;; code ||= oplcode
                    (is (nil? http-messages))
                    (println (str "PRED RESULT " idx " is " (pred? result)))
                    (is (pred? result) (str action "-" (name ootype) " " idx)))))))))

;; This just does a lookup of an existing RIO opleidingseenheid
(deftest ^:vcr opleidingseenheid-finder-test
  (let [vcr                 (vcr.helper/make-vcr)
        config              (if (= vcr.helper/vcr-mode :record)
                              (config/make-config env)
                              (helper/make-test-config))
        client-info         (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config           (:rio-config config)
        handlers             (processing/make-handlers {:rio-config rio-config
                                                        :gateway-root-url (:gateway-root-url config)
                                                        :gateway-credentials (:gateway-credentials config)})]

    (binding [http-utils/*vcr* (vcr "test-v5/fixtures/vcr/opleenh-finder" 1 "finder")]
      (let [result (rio.loader/find-opleidingseenheid "1010O3664" (:getter handlers) (:institution-oin client-info))]
        (is (some? result))))))

(deftest ^:vcr aangeboden-finder-test
  (let [vcr                  (vcr.helper/make-vcr)
        config               (if (= vcr.helper/vcr-mode :record)
                               (config/make-config env)
                               (helper/make-test-config))
        client-info          (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config           (:rio-config config)
        handlers             (processing/make-handlers {:rio-config rio-config
                                                        :gateway-root-url (:gateway-root-url config)
                                                        :gateway-credentials (:gateway-credentials config)})
        getter (:getter handlers)]
    (testing "found aangeboden opleiding"
      (binding [http-utils/*vcr* (vcr "test-v5/fixtures/vcr/aangeboden-finder-test" 1 "finder")]
        (let [result (rio.loader/find-aangebodenopleiding "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672" getter (:institution-oin client-info))]
          (is (some? result)))))
    (testing "did not find aangeboden opleiding"
      (binding [http-utils/*vcr* (vcr "test-v5/fixtures/vcr/aangeboden-finder-test" 2 "finder")]
        (let [result (rio.loader/find-aangebodenopleiding "bbbbbbbb-3f4e-49c2-a1f7-e24ae82b0673" getter (:institution-oin client-info))]
          (is (nil? result)))))))

(deftest ^:vcr test-ooapi-loader
  (let [vcr          (vcr.helper/make-vcr)
        config       (if (= vcr.helper/vcr-mode :record)
                       (config/make-config env)
                       (helper/make-test-config))
        ooapi-loader (ooapi.loader/validating-loader
                      (ooapi.loader/make-ooapi-http-loader (:gateway-root-url config)
                                                           (:gateway-credentials config)
                                                           config))
        client-info  (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")]
    (testing "offerings"
      (let [request {::ooapi/root-url (URI. "https://rio-mapper-dev.jomco.nl/")
                     ::ooapi/type     "program-offerings"
                     ::ooapi/id       (entity-name-to-id "programs/interaction-program-some")
                     :gateway-credentials (:gateway-credentials config)}]
        (binding [http-utils/*vcr* (vcr "test-v5/fixtures/vcr/ooapi-loader" 1 "offering")]
          (let [items (:items (ooapi-loader (merge client-info request {:page-size 2})))]
            (is (= 3 (count items)))))))

    ;; We test only one error here, to make sure that the validating loader includes the error from the spec-helper.
    (testing "invalid-eduspec"
      (let [request {::ooapi/root-url (URI. "https://rio-mapper-dev.jomco.nl/")
                     ::ooapi/type     "education-specification"
                     ::ooapi/id       (entity-name-to-id "education-specifications/bad-eduspec")
                     :gateway-credentials (:gateway-credentials config)}]
        (binding [http-utils/*vcr* (vcr "test-v5/fixtures/vcr/ooapi-loader" 2 "eduspec")]
          (let [ex (is (thrown? ExceptionInfo (-> (merge client-info request) ooapi-loader)))]
            (is (= "Top level EducationSpecification object is missing these required fields: educationSpecificationId"
                   (:origin (ex-data ex))))))))))
