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
    [nl.surf.eduhub-rio-mapper.v5.rio.loader :as rio.loader]
    [nl.surf.eduhub-rio-mapper.v5.test-helper :as helper]))

(def vcr-mode :playback)

(when (= :record vcr-mode)
  (use-fixtures :once remote-entities-fixture))

(defn- entity-id
  "Get UUID of entity from remote-entities session."
  [name]
  (get remote-entities/*session* name))

(defn- load-relations [getter client-info code]
  {:pre [(string? code)]}
  (getter {::rio/type           "opleidingsrelatiesBijOpleidingseenheid"
           :institution-oin     (:institution-oin client-info)
           ::rio/opleidingscode code}))

(def name-of-ootype
  {:eduspec "education-specification"
   :course  "course"
   :program "program"})

(defn- make-runner [handlers client-info http-logging-enabled]
  (fn run [ootype id action]
    {:pre [id (not= "" id)]}
    ;; id is atom for relations, UUID otherwise

    (if (= ootype :relation)
      (load-relations (:getter handlers) client-info @id)
      (job/run! handlers
                (merge client-info
                       {::ooapi/id   (str id)
                        ::ooapi/type (name-of-ootype ootype)
                        :action      action})
                http-logging-enabled))))

(deftest interaction-test
  (let [vcr                  (helper/make-vcr vcr-mode)
        config               (if (= vcr-mode :record)
                               (config/make-config env)
                               (assoc-in (helper/make-test-config) [:rio-config :rio-retry-attempts-seconds] [1 1 1 1]))
        client-info          (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config           (:rio-config config)
        handlers             (processing/make-handlers {:rio-config rio-config
                                                        :gateway-root-url (:gateway-root-url config)
                                                        :gateway-credentials (:gateway-credentials config)})
        ;; TODO After running record, these 3 ids need to be updated. Should be done automatically.
        eduspec-parent-id    (if (= :record vcr-mode)
                               (entity-id "education-specifications/interaction-eduspec-parent")
                               "9f87f277-0548-4a5d-ad8b-cdf6a2e4bfcb")

        eduspec-child-id     (if (= :record vcr-mode)
                               (entity-id "education-specifications/interaction-eduspec-child")
                               "169a91de-5d6b-4b1e-9f9f-517d85f4fab1")

        program-id           (if (= :record vcr-mode)
                               (entity-id "education-specifications/interaction-eduspec-child")
                               "41582431-5707-47b9-9dfe-88f36404c9fa")

        runner               (make-runner handlers
                                          client-info
                                          false)
        goedgekeurd?         #(= "true" (-> % vals first :requestGoedgekeurd))
        code                 (atom nil) ; During the tests we'll learn which opleidingscode we should use.

        commands            [[1 "upsert" :eduspec  eduspec-parent-id goedgekeurd?]
                             [2 "upsert" :eduspec  eduspec-child-id  goedgekeurd?]
                             [3 "get"    :relation code              identity]
                             [4 "delete" :eduspec  eduspec-child-id  goedgekeurd?]
                             [5 "get"    :relation code              nil?]
                             [6 "upsert" :program  program-id        goedgekeurd?]
                             [7 "delete" :program  program-id        goedgekeurd?]
                             [8 "delete" :eduspec  eduspec-parent-id goedgekeurd?]
                             [9 "upsert" :program  program-id        #(= (-> % :errors :message)
                                                                         (str "No 'opleidingseenheid' found in RIO with eigensleutel: " eduspec-parent-id))]]]
    (doseq [[idx action ootype id pred?] commands]
      (testing (str "Command " idx " " action " " id)
        (binding [http-utils/*vcr* (vcr "test-v5/fixtures/interaction" idx (str action "-" (name ootype)))]
          (let [result  (runner ootype id action)
                http-messages (:http-messages result)
                oplcode (-> result :aanleveren_opleidingseenheid_response :opleidingseenheidcode)]
            (when oplcode (swap! code #(if (nil? %) oplcode %))) ;; code ||= oplcode
            (is (nil? http-messages))
            (is (pred? result) (str action "-" (name ootype) " " idx))))))))

;; This just does a lookup of an existing RIO opleidingseenheid
(deftest opleidingseenheid-finder-test
  (let [vcr                 (helper/make-vcr vcr-mode)
        config              (if (= vcr-mode :record)
                              (config/make-config env)
                              (helper/make-test-config))
        client-info         (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config           (:rio-config config)
        handlers             (processing/make-handlers {:rio-config rio-config
                                                        :gateway-root-url (:gateway-root-url config)
                                                        :gateway-credentials (:gateway-credentials config)})]

    (binding [http-utils/*vcr* (vcr "test-v5/fixtures/opleenh-finder" 1 "finder")]
      (let [result (rio.loader/find-opleidingseenheid "1010O3664" (:getter handlers) (:institution-oin client-info))]
        (is (some? result))))))

(deftest aangeboden-finder-test
  (let [vcr                  (helper/make-vcr vcr-mode)
        config               (if (= vcr-mode :record)
                               (config/make-config env)
                               (helper/make-test-config))
        client-info          (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config           (:rio-config config)
        handlers             (processing/make-handlers {:rio-config rio-config
                                                        :gateway-root-url (:gateway-root-url config)
                                                        :gateway-credentials (:gateway-credentials config)})
        getter (:getter handlers)]
    (testing "found aangeboden opleiding"
      (binding [http-utils/*vcr* (vcr "test-v5/fixtures/aangeboden-finder-test" 1 "finder")]
        (let [result (rio.loader/find-aangebodenopleiding "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672" getter (:institution-oin client-info))]
          (is (some? result)))))
    (testing "did not find aangeboden opleiding"
      (binding [http-utils/*vcr* (vcr "test-v5/fixtures/aangeboden-finder-test" 2 "finder")]
        (let [result (rio.loader/find-aangebodenopleiding "bbbbbbbb-3f4e-49c2-a1f7-e24ae82b0673" getter (:institution-oin client-info))]
          (is (nil? result)))))))
