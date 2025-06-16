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

(ns nl.surf.eduhub-rio-mapper.interaction-test
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.cli-commands :as cli-commands]
    [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
    [nl.surf.eduhub-rio-mapper.commands.processing :as processing]
    [nl.surf.eduhub-rio-mapper.job :as job]
    [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
    [nl.surf.eduhub-rio-mapper.test-helper :as helper]
    [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]))

(defn- load-relations [getter client-info code]
  {:pre [code]}
  (getter {::rio/type           "opleidingsrelatiesBijOpleidingseenheid"
           :institution-oin     (:institution-oin client-info)
           ::rio/opleidingscode code}))

(def name-of-ootype
  {:eduspec "education-specification"
   :course  "course"
   :program "program"})

(defn- make-runner [handlers client-info http-logging-enabled]
  (fn run [ootype id action]
    (if (= ootype :relation)
      (load-relations (:getter handlers) client-info @id)
      (job/run! handlers
                (merge client-info
                       {::ooapi/id   id
                        ::ooapi/type (name-of-ootype ootype)
                        :action      action})
                http-logging-enabled))))

(deftest interaction-test-individual
  (let [vcr               (helper/make-vcr :playback)
        eduspec-parent-id "fddec347-8ca1-c991-8d39-9a85d09c0004"
        eduspec-child-id  "afb435cc-5352-f55f-a548-41c9dfd60002"
        program-id        "49ca7998-74b1-f44a-1ec1-000000000002"
        config            (helper/make-test-config)
        handlers          (processing/make-handlers config)
        client-info       (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        logging-runner    (make-runner handlers
                                       client-info
                                       true)
        goedgekeurd?      #(= "true" (-> % vals first :requestGoedgekeurd))
        code              (atom nil)] ; During the tests we'll learn which opleidingscode we should use.
    ;; Test resolve giving an error
    (let [[idx action ootype id] [20 "upsert" :eduspec  eduspec-parent-id]]
      (testing (str "Command " idx " " action " " id)
        (binding [http-utils/*vcr* (vcr "test/fixtures/interaction" idx (str action "-" (name ootype)))]
          (let [result        (logging-runner ootype id action)]
            (is (= {:phase :resolving, :retryable? false} (select-keys (:errors result) [:phase :retryable?])))))))

    ;; Test filter out AFGELEID_VAN relaties
    (let [[idx action] [40 "get"]]
      (testing (str "Command " idx " relation 1234O4321")
        (binding [http-utils/*vcr* (vcr "test/fixtures/interaction" idx (str action "-relation"))]
          (let [result        (load-relations (:getter handlers) client-info "1234O4321")]
            (is (empty? result))))))

    (testing "Test upsert program"
      (binding [http-utils/*vcr* (vcr "test/fixtures/interaction" 6 "upsert-program")]
        (let [result (logging-runner :program program-id "upsert")
              upsert (get-in result [:http-messages 5 :req])]
          (is (= 7 (count (:http-messages result))))
          (is (str/includes? (:body upsert) "<duo:aangebodenOpleidingCode>49ca7998-74b1-f44a-1ec1-000000000002</duo:aangebodenOpleidingCode>")))))

    ;; Test with http message logging enabled
    (let [[idx action ootype id pred?] [1 "upsert" :eduspec  eduspec-parent-id goedgekeurd?]]
      (testing (str "Command " idx " " action " " id)
        (binding [http-utils/*vcr* (vcr "test/fixtures/interaction" idx (str action "-relation" (name ootype)))]
          (let [result        (logging-runner ootype id action)
                http-messages (:http-messages result)
                oplcode       (-> result :aanleveren_opleidingseenheid_response :opleidingseenheidcode)]
            (when oplcode (swap! code #(if (nil? %) oplcode %)))
            (when (= 1 idx)
              (is (= "https://vt-webservice.duo.nl:6977/RIO/services/raadplegen4.0" (some-> http-messages (nth 1 nil) :req :url)))
              (is (= 200 (some-> http-messages (nth 1 nil) :res :status))))
            (is (pred? result) (str action "-" (name ootype) idx))))))

    ;; Test with http message logging enabled
    (let [[idx action] [1 "opleidingseenhedenVanOrganisatie"]]
      (testing (str "Command " idx " " action)
        (binding [http-utils/*vcr* (vcr "test/fixtures/interaction/cli" idx (str action "-eduspec"))]
          (let [args ["rio-mapper-dev.jomco.nl" action "100B490" "18"]
                result (-> (cli-commands/process-command "get" args {:handlers (processing/make-handlers config)
                                                            :config   config})
                           json/read-str)]
            (is (= "1009O6891"
                   (get-in result ["opvragen_opleidingseenhedenVanOrganisatie_response" 5 "particuliereOpleiding" "opleidingseenheidcode"])))))))

    (let [[idx action ootype id] [30 "upsert" :eduspec  eduspec-child-id]]
      (testing (str "Command " idx " " action " " id)
        (binding [http-utils/*vcr* (vcr "test/fixtures/interaction" idx (str action "-" (name ootype)))]
          (let [result        (logging-runner ootype id action)
                http-messages (:http-messages result)]
            (is (= [nil
                    "opvragen_rioIdentificatiecode"
                    "opvragen_opleidingsrelatiesBijOpleidingseenheid"
                    "aanleveren_opleidingseenheid"
                    "opvragen_rioIdentificatiecode"
                    "opvragen_opleidingsrelatiesBijOpleidingseenheid"
                    nil
                    "opvragen_rioIdentificatiecode"
                    "verwijderen_opleidingsrelatie"
                    "aanleveren_opleidingsrelatie"]
                   (mapv #(when-let [soap-action (get-in http-messages [% :req :headers "SOAPAction"])]
                            (last (clojure.string/split soap-action #"/")))
                         (range 0 (count http-messages)))))))))

    (testing "Delete program using code not id"
      (binding [http-utils/*vcr* (vcr "test/fixtures/interaction" 7 "delete-program")]
        (let [result  (logging-runner :program program-id "delete")
              body    (get-in result [:http-messages 1 :req :body])
              ao-code "49ca7998-74b1-f44a-1ec1-888800000002"]
          ;; ao-code is returned by the resolve action - it should be used in the "verwijder" action,
          ;; not id.
          (is (str/includes? body ao-code)))))))

(deftest interaction-test
  (let [vcr               (helper/make-vcr :playback)
        eduspec-parent-id "fddec347-8ca1-c991-8d39-9a85d09c0004"
        eduspec-child-id  "afb435cc-5352-f55f-a548-41c9dfd60002"
        program-id        "49ca7998-74b1-f44a-1ec1-000000000002"
        config            (helper/make-test-config)
        handlers          (processing/make-handlers config)
        client-info       (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        runner            (make-runner handlers
                                       client-info
                                       false)
        goedgekeurd?      #(= "true" (-> % vals first :requestGoedgekeurd))
        code              (atom nil) ; During the tests we'll learn which opleidingscode we should use.
        commands          [[1 "upsert" :eduspec  eduspec-parent-id goedgekeurd?]
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
        (binding [http-utils/*vcr* (vcr "test/fixtures/interaction" idx (str action "-" (name ootype)))]
          (let [result  (runner ootype id action)
                http-messages (:http-messages result)
                oplcode (-> result :aanleveren_opleidingseenheid_response :opleidingseenheidcode)]
            (when oplcode (swap! code #(if (nil? %) oplcode %)))
            (is (nil? http-messages))
            (is (pred? result) (str action "-" (name ootype) idx))))))))

(deftest opleidingseenheid-finder-test
  (let [vcr    (helper/make-vcr :playback)
        config (helper/make-test-config)
        client-info (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config (:rio-config config)
        handlers (processing/make-handlers {:rio-config rio-config
                                            :gateway-root-url (:gateway-root-url config)
                                            :gateway-credentials (:gateway-credentials config)})]
    (binding [http-utils/*vcr* (vcr "test/fixtures/opleenh-finder" 1 "finder")]
      (let [result (rio.loader/find-opleidingseenheid "1010O3664" (:getter handlers) (:institution-oin client-info))]
        (is (some? result))))))

(deftest aangeboden-finder-test
  (let [vcr    (helper/make-vcr :playback)
        config (helper/make-test-config)
        client-info (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config (:rio-config config)
        handlers (processing/make-handlers {:rio-config rio-config
                                            :gateway-root-url (:gateway-root-url config)
                                            :gateway-credentials (:gateway-credentials config)})
        getter (:getter handlers)]
    (testing "found aangeboden opleiding"
      (binding [http-utils/*vcr* (vcr "test/fixtures/aangeboden-finder-test" 1 "finder")]
        (let [result (rio.loader/find-aangebodenopleiding "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672" getter (:institution-oin client-info))]
          (is (some? result)))))
    (testing "did not find aangeboden opleiding"
      (binding [http-utils/*vcr* (vcr "test/fixtures/aangeboden-finder-test" 2 "finder")]
        (let [result (rio.loader/find-aangebodenopleiding "bbbbbbbb-3f4e-49c2-a1f7-e24ae82b0673" getter (:institution-oin client-info))]
          (is (nil? result)))))))
