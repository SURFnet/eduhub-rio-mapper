(ns nl.surf.eduhub-rio-mapper.v5.remote-fixture-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
            [nl.surf.eduhub-rio-mapper.utils.soap :as soap]
            [nl.surf.eduhub-rio-mapper.v5.rio.opleidingseenheid :as mapping]
            [nl.surf.eduhub-rio-mapper.v5.specs.education-specification :as es]
            [nl.surf.eduhub-rio-mapper.v5.specs.helper :as sh]))

(defn expanded-fixtures []
  (let [session (remote/make-session)
        objects (into {} (map (fn [{:keys [path body]}] [path (json/read-str body :key-fn keyword)])
                              (remote/remote-objects session)))]
    (into {} (map (fn [[k id]] [k (get objects (str (first (str/split k #"/")) "/" id))]) session))))

(deftest private-fixture-contract
  (let [fixtures (expanded-fixtures)]
    (doseq [name ["parent-program" "child-program" "bonusparent-program" "bonuschild-program"
                 "dorothy" "invalid-data-parent" "orphan-eduspec"]]
      (testing name
        (let [entity (fixtures (str "education-specifications/" name))
              xml (mapping/education-specification->opleidingseenheid entity)]
          (is (nil? (sh/check-spec entity ::es/EducationSpecificationTopLevel "EducationSpecification")))
          (is (= :duo:particuliereOpleiding (first xml)))
          (is (not-any? #{:duo:soort :duo:nlqf :duo:eqf} (flatten xml)))
          (is (not-any? #(contains? entity %) [:parent :children]))
          (is (not-any? #(contains? (first (:consumers entity)) %)
                        [:educationSpecificationSubType]))
          (is (-> (soap/request-body "aanleveren_opleidingseenheid" [xml]
                                    "http://duo.nl/schema/DUO_RIO_Beheren_OnderwijsOrganisatie_V4" "1234" "12345")
                  (soap/guard-valid-sexp mutator/validator))))))))

(deftest reject-unlinked-ordinary-ho-insertion
  (let [entity ((expanded-fixtures) "education-specifications/weekly-parent")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot create a hoOpleiding without an opleidingseenheidcode"
                         (mapping/education-specification->opleidingseenheid entity)))
    (is (= :duo:hoOpleiding
           (first (mapping/education-specification->opleidingseenheid (assoc entity :rioCode "1001O5220")))))))
