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

(ns nl.surf.eduhub-rio-mapper.rio-test
  (:require [clojure.data :as data]
            [clojure.data.xml :as xml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [are deftest is testing]]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding :as aangeboden-opl]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
            [nl.surf.eduhub-rio-mapper.rio.opleidingseenheid :as opl-eenh]
            [nl.surf.eduhub-rio-mapper.rio.updated-handler :as updated-handler]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.test-helper :as helper]
            [nl.surf.eduhub-rio-mapper.utils.keystore :as keystore]
            [nl.surf.eduhub-rio-mapper.utils.soap :as soap]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils])
  (:import java.io.PushbackReader))

(deftest canonicalization-and-digestion
  (let [canonicalizer (fn [id] (str "<wsa:Action "
                                    (soap/xmlns [["duo"  rio.loader/schema]
                                                 ["soapenv" soap/soap-envelope]
                                                 ["wsa" soap/ws-addressing]
                                                 ["wsu" soap/wsu-schema]])
                                    " wsu:Id=\""
                                    id
                                    "\">"
                                    rio.loader/contract
                                    "/opvragen_aangebodenOpleidingenVanOrganisatie</wsa:Action>"))

        expected-digest "u95macy7enN9aTCyQKuQqTIsYj/8G9vv8o6EBV1OZjs="]
    (is (= expected-digest (soap/digest-sha256 (canonicalizer "id-629A9B11E252AF76D61657184053301145"))))))

(defn prep-body
  [{:keys [action rio-sexp]}]
  (soap/request-body action rio-sexp "http://duo.nl/schema/DUO_RIO_Beheren_OnderwijsOrganisatie_V4"
                     "1234" "12345"))

(def test-handler
  "Loads ooapi fixtures from file and fakes resolver."
  (-> updated-handler/update-mutation
      (helper/wrap-resolver (fn [ootype _ _] (if (= "education-specification" ootype)
                                                        "1009O1234"
                                                        "12345678-9abc-def0-1234-56789abcdef0")))
      (helper/wrap-load-entities ooapi.loader/ooapi-file-loader)
      (clients-info/wrap-client-info [{:client-id              "rio-mapper-dev.jomco.nl"
                                       :institution-schac-home "demo06.test.surfeduhub.nl"
                                       :institution-oin        "0000000700025BE00000"}])))

(deftest test-and-validate-entities
  (are [updated]
      (is (-> updated
              (test-handler)
              (prep-body)
              (soap/guard-valid-sexp mutator/validator)))

    {::ooapi/id "10010000-0000-0000-0000-000000000000"
     ::ooapi/type "education-specification"
     :client-id "rio-mapper-dev.jomco.nl"}
    {::ooapi/id "10020000-0000-0000-0000-000000000000"
     ::ooapi/type "education-specification"
     :client-id "rio-mapper-dev.jomco.nl"}
    {::ooapi/id "10030000-0000-0000-0000-000000000000"
     ::ooapi/type "education-specification"
     :client-id "rio-mapper-dev.jomco.nl"}
    {::ooapi/id "10040000-0000-0000-0000-000000000000"
     ::ooapi/type "education-specification"
     :client-id "rio-mapper-dev.jomco.nl"}
    {::ooapi/id "20010000-0000-0000-0000-000000000000"
     ::ooapi/type "program"
     :client-id "rio-mapper-dev.jomco.nl"}
    {::ooapi/id "20020000-0000-0000-0000-000000000000"
     ::ooapi/type "program"
     :client-id "rio-mapper-dev.jomco.nl"}
    {::ooapi/id "20030000-0000-0000-0000-000000000000"
     ::ooapi/type "program"
     :client-id "rio-mapper-dev.jomco.nl"}
    {::ooapi/id "20030000-0000-0000-0000-000000000000"
     ::ooapi/type "program"
     :client-id "rio-mapper-dev.jomco.nl"}
    {::ooapi/id "30010000-0000-0000-0000-000000000000"
     ::ooapi/type "course"
     :client-id "rio-mapper-dev.jomco.nl"}))

;; eigenNaamInternationaal is over 225 chars, which is > max-length
;; but no exception, since fields gets truncated.
(deftest test-and-validate-program-4-valid
  (let [request (test-handler {::ooapi/id "29990000-0000-0000-0000-000000000000"
                               ::ooapi/type "program"
                               :client-id "rio-mapper-dev.jomco.nl"})]
    (is (= :duo:aanleveren_aangebodenOpleiding_request
           (first (-> request
                      prep-body
                      (soap/guard-valid-sexp mutator/validator))))
        "guard throws an exception if XML invalid according to XSD")))

(defn collect-paths
  "If leaf-node, add current path (and node if include-leaves is true) to acc.
   Otherwise, call recursively for each child with name of child appended to the path."
  [node acc path include-leaves]
  {:pre [(or (not (map? node))
             (every? keyword? (keys node)))]}
  (cond (map? node)
        (reduce (fn [a k] (collect-paths (k node) a (conj path k) include-leaves)) acc (keys node))

        (coll? node)
        (reduce (fn [a n] (collect-paths (nth node n) a (conj path n) include-leaves)) acc (range (count node)))

        :else
        (conj acc (if include-leaves [path node] path))))

(def volatile-paths [[:Envelope :Body :opvragen_aangebodenOpleidingenVanOrganisatie_request :datumTijdBedrijfsdocument]
                     [:Envelope :Body :opvragen_aangebodenOpleidingenVanOrganisatie_request :identificatiecodeBedrijfsdocument]
                     [:Envelope :Header :Security :Timestamp :Expires]
                     [:Envelope :Header :Security :Timestamp :Created]
                     [:Envelope :Header :Security :Signature :KeyInfo :SecurityTokenReference :ReferenceAttrs :URI]
                     [:Envelope :Header :Security :Signature :SignatureValue]
                     [:Envelope :Header :Security :Signature :SignedInfo 0]
                     [:Envelope :Header :Security :Signature :SignedInfo 1]
                     [:Envelope :Header :Security :Signature :SignedInfo 2]
                     [:Envelope :Header :Security :Signature :SignedInfo 3]
                     [:Envelope :Header :Security :Signature :SignedInfo 4]
                     [:Envelope :Header :Security :Signature :SignedInfo 5 :Reference :DigestValue]
                     [:Envelope :Header :Security :Signature :SignedInfo 6 :Reference :DigestValue]
                     [:Envelope :Header :Security :Signature :SignedInfo 7 :Reference :DigestValue]
                     [:Envelope :Header :Security :BinarySecurityTokenAttrs :wsu/Id]
                     [:Envelope :Header :MessageID]])

;; Differences between two consecutive signings of identical requests should be in timestamps, uuids and digests.
(deftest only-differences-between-signed-requests-are-in-given-paths
  (let [credentials (keystore/credentials "test/keystore.jks" "xxxxxx" "test-surf" "truststore.jks" "xxxxxx")
        rio-sexp [[:duo:onderwijsaanbiedercode "110A133"]
                  [:duo:peildatum "2022-06-22"]
                  [:duo:pagina "0"]]
        volatile-paths-set (set volatile-paths)
        datamap (rio.loader/make-datamap "0000000700025BE00000" "00000001800866472000")
        xml (soap/prepare-soap-call "opvragen_aangebodenOpleidingenVanOrganisatie" rio-sexp datamap credentials)]
    ;; Are the non-volatile parts of the request unchanged?
    (is (= (vec
            (filter
             (fn [[path _]] (not (volatile-paths-set path)))
             (collect-paths (xml-utils/xml-event-tree->edn (xml/parse-str xml)) [] [] true)))
           (edn/read (PushbackReader. (io/reader (io/file "test/fixtures/rio/soap.edn"))))))
    ;; Do the two requests still differ in the same places?
    (let [[differences _ _] (data/diff (xml-utils/xml-event-tree->edn (xml/parse-str xml))
                                       (xml-utils/xml-event-tree->edn (xml/parse-str (soap/prepare-soap-call "opvragen_aangebodenOpleidingenVanOrganisatie" rio-sexp datamap credentials))))]
      (is (= (collect-paths differences [] [] false)
             volatile-paths)))))

(deftest to-rio-obj
  (testing "eduspec"
    (is (= [:duo:hoOpleiding
            [:duo:begindatum "2019-08-24"]
            [:duo:einddatum "2022-08-29"]
            [:duo:kenmerken [:duo:kenmerknaam "eigenOpleidingseenheidSleutel"] [:duo:kenmerkwaardeTekst "10010000-0000-0000-0000-000000000000"]]
            [:duo:kenmerken [:duo:kenmerknaam "soort"] [:duo:kenmerkwaardeEnumeratiewaarde "VARIANT"]]
            [:duo:hoOpleidingPeriode [:duo:begindatum "2019-08-24"] [:duo:naamLang "Bachelor Petrochemische technologie"] [:duo:naamKort "B Petrochem Tech"] [:duo:internationaleNaam "Bachelor Petrochemical technology"] [:duo:omschrijving "program that is a place holder for all courses that are made available for student mobility"] [:duo:studielasteenheid "ECTS_PUNT"]]
            [:duo:hoOpleidingPeriode [:duo:begindatum "2020-08-25"] [:duo:naamLang "OVERRIDE Bachelor Petrochemical technology"] [:duo:internationaleNaam "OVERRIDE Bachelor Petrochemical technology"]]
            [:duo:waardedocumentsoort "DIPLOMA"]
            [:duo:niveau "WO-MA"]
            [:duo:ISCED "073"]]
           (-> {::ooapi/id "10010000-0000-0000-0000-000000000000" ::ooapi/type "education-specification"}
               ooapi.loader/ooapi-file-loader
               opl-eenh/education-specification->opleidingseenheid))))
  (testing "course"
    (is (= [:duo:aangebodenHOOpleidingsonderdeel
            [:duo:aangebodenOpleidingCode "30010000-0000-0000-0000-000000000000"]
            [:duo:onderwijsaanbiedercode "123A321"]
            [:duo:onderwijslocatiecode "334X123"]
            [:duo:begindatum "2019-08-24"]
            [:duo:eersteInstroomDatum "2019-08-24"]
            [:duo:einddatum "2024-08-24"]
            [:duo:opleidingseenheidSleutel "1234O1234"]
            [:duo:aangebodenHOOpleidingsonderdeelPeriode
             [:duo:begindatum "2019-08-24"]
             [:duo:eigenNaamAangebodenOpleiding "Academic and Professional Writing"]
             [:duo:eigenNaamInternationaal "Academic and Professional Writing"]
             [:duo:eigenOmschrijving
              "As with all empirical sciences, to assure valid outcomes, HCI studies heavily rely on research methods and statistics. This holds for the design of user interfaces, personalized recommender systems, and interaction paradigms for the internet of things. This course prepares you to do so by learning you to collect data, design experiments, and analyze the results. By the end of the course, you will have a detailed understanding of how to select and apply quantitative research methods and analysis to address virtually all HCI challenges. Quantitative research and data analysis will be taught in the context of state-of-the-art HCI challenges. Lectures will be alternated with hands-on learning, including work with predefined datasets (e.g., addressing facial features, cognitive load, and emotion). Additionally, students will set up their own research (e.g., using eye tracking). Data processing and analysis will be executed using R."]
             [:duo:kenmerken [:duo:kenmerknaam "samenwerkendeOnderwijsaanbiedercode"] [:duo:kenmerkwaardeTekst "123A123"]]
             [:duo:kenmerken [:duo:kenmerknaam "eigenNaamKort"] [:duo:kenmerkwaardeTekst "INFOMQNM"]]
             [:duo:kenmerken [:duo:kenmerknaam "buitenlandsePartner"] [:duo:kenmerkwaardeTekst "Harvard University"]]
             [:duo:kenmerken [:duo:kenmerknaam "website"] [:duo:kenmerkwaardeTekst "https://osiris.uu.nl/osiris_student_uuprd/OnderwijsCatalogusZoekCursus.do#submitForm?cursuscode=INFOMQNM"]]]
            [:duo:aangebodenHOOpleidingsonderdeelPeriode
             [:duo:begindatum "2021-09-01"]
             [:duo:eigenNaamAangebodenOpleiding "OVERRIDE Academic and Professional Writing"]
             [:duo:eigenNaamInternationaal "OVERRIDE Academic and Professional Writing"]
             [:duo:eigenOmschrijving
              "As with all empirical sciences, to assure valid outcomes, HCI studies heavily rely on research methods and statistics. This holds for the design of user interfaces, personalized recommender systems, and interaction paradigms for the internet of things. This course prepares you to do so by learning you to collect data, design experiments, and analyze the results. By the end of the course, you will have a detailed understanding of how to select and apply quantitative research methods and analysis to address virtually all HCI challenges. Quantitative research and data analysis will be taught in the context of state-of-the-art HCI challenges. Lectures will be alternated with hands-on learning, including work with predefined datasets (e.g., addressing facial features, cognitive load, and emotion). Additionally, students will set up their own research (e.g., using eye tracking). Data processing and analysis will be executed using R."]
             [:duo:kenmerken [:duo:kenmerknaam "samenwerkendeOnderwijsaanbiedercode"] [:duo:kenmerkwaardeTekst "999A123"]]
             [:duo:kenmerken [:duo:kenmerknaam "eigenNaamKort"] [:duo:kenmerkwaardeTekst "INFOMQNM"]]
             [:duo:kenmerken [:duo:kenmerknaam "buitenlandsePartner"] [:duo:kenmerkwaardeTekst "Harvard University"]]
             [:duo:kenmerken [:duo:kenmerknaam "website"] [:duo:kenmerkwaardeTekst "https://osiris.uu.nl/osiris_student_uuprd/OnderwijsCatalogusZoekCursus.do#submitForm?cursuscode=INFOMQNM"]]]
            [:duo:aangebodenHOOpleidingsonderdeelPeriode
             [:duo:begindatum "2022-09-01"]]
            [:duo:kenmerken [:duo:kenmerknaam "eigenAangebodenOpleidingSleutel"] [:duo:kenmerkwaardeTekst "30010000-0000-0000-0000-000000000000"]]
            [:duo:kenmerken [:duo:kenmerknaam "toestemmingDeelnameSTAP"] [:duo:kenmerkwaardeEnumeratiewaarde "TOESTEMMING_VERLEEND"]]
            [:duo:kenmerken [:duo:kenmerknaam "voertaal"] [:duo:kenmerkwaardeEnumeratiewaarde "NLD"]]]
           (-> {::ooapi/id "30010000-0000-0000-0000-000000000000" ::ooapi/type "course"}
               ooapi.loader/ooapi-file-loader
               (aangeboden-opl/->aangeboden-opleiding :course "1234O1234" "course")))))
  (testing "program"
    (is (= [:duo:aangebodenHOOpleiding
            [:duo:aangebodenOpleidingCode "20010000-0000-0000-0000-000000000000"]
            [:duo:onderwijsaanbiedercode "110A133"]
            [:duo:onderwijslocatiecode "123X122"]
            [:duo:begindatum "2019-08-24"]
            [:duo:eersteInstroomDatum "2019-08-24"]
            [:duo:einddatum "2022-08-31"]
            [:duo:opleidingseenheidSleutel "1234O1234"]
            [:duo:aangebodenHOOpleidingPeriode
             [:duo:begindatum "2019-08-24"]
             [:duo:eigenNaamAangebodenOpleiding "Biology"]
             [:duo:eigenNaamInternationaal "Biology"]
             [:duo:eigenOmschrijving "The study of life"]
             [:duo:kenmerken [:duo:kenmerknaam "samenwerkendeOnderwijsaanbiedercode"] [:duo:kenmerkwaardeTekst "122A113"]]
             [:duo:kenmerken [:duo:kenmerknaam "deficientie"] [:duo:kenmerkwaardeEnumeratiewaarde "DEFICIENTIES"]]
             [:duo:kenmerken [:duo:kenmerknaam "versneldTraject"] [:duo:kenmerkwaardeEnumeratiewaarde "VERSNELD_TRAJECT"]]
             [:duo:kenmerken [:duo:kenmerknaam "eigenNaamKort"] [:duo:kenmerkwaardeTekst "BIO"]]
             [:duo:kenmerken [:duo:kenmerknaam "propedeutischeFase"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_PROPEDEUTISCHE_FASE"]]
             [:duo:kenmerken [:duo:kenmerknaam "eisenWerkzaamheden"] [:duo:kenmerkwaardeEnumeratiewaarde "EISEN"]]
             [:duo:kenmerken [:duo:kenmerknaam "buitenlandsePartner"] [:duo:kenmerkwaardeTekst "Harvard University"]]
             [:duo:kenmerken [:duo:kenmerknaam "studiekeuzecheck"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_STUDIEKEUZE_CHECK"]]
             [:duo:kenmerken [:duo:kenmerknaam "website"] [:duo:kenmerkwaardeTekst "https://bijvak.nl"]]]
            [:duo:aangebodenHOOpleidingPeriode
             [:duo:begindatum "2021-09-01"]
             [:duo:eigenNaamAangebodenOpleiding "OVERRIDE Biology"]
             [:duo:eigenOmschrijving "The study of life"]
             [:duo:kenmerken [:duo:kenmerknaam "samenwerkendeOnderwijsaanbiedercode"] [:duo:kenmerkwaardeTekst "122A113"]]
             [:duo:kenmerken [:duo:kenmerknaam "deficientie"] [:duo:kenmerkwaardeEnumeratiewaarde "DEFICIENTIES"]]
             [:duo:kenmerken [:duo:kenmerknaam "versneldTraject"] [:duo:kenmerkwaardeEnumeratiewaarde "VERSNELD_TRAJECT"]]
             [:duo:kenmerken [:duo:kenmerknaam "eigenNaamKort"] [:duo:kenmerkwaardeTekst "BIO"]]
             [:duo:kenmerken [:duo:kenmerknaam "propedeutischeFase"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_PROPEDEUTISCHE_FASE"]]
             [:duo:kenmerken [:duo:kenmerknaam "eisenWerkzaamheden"] [:duo:kenmerkwaardeEnumeratiewaarde "EISEN"]]
             [:duo:kenmerken [:duo:kenmerknaam "buitenlandsePartner"] [:duo:kenmerkwaardeTekst "Harvard University"]]
             [:duo:kenmerken [:duo:kenmerknaam "studiekeuzecheck"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_STUDIEKEUZE_CHECK"]]
             [:duo:kenmerken [:duo:kenmerknaam "website"] [:duo:kenmerkwaardeTekst "https://bijvak.nl"]]]
            [:duo:aangebodenHOOpleidingCohort
             [:duo:cohortcode "1234qwe12"]
             [:duo:cohortstatus "O"]
             [:duo:kenmerken
              [:duo:kenmerknaam "deelnemersplaatsen"]
              [:duo:kenmerkwaardeGetal 200]]
             [:duo:kenmerken
              [:duo:kenmerknaam "opleidingsvorm"]
              [:duo:kenmerkwaardeEnumeratiewaarde "KLASSIKAAL"]]
             [:duo:beginAanmeldperiode "2019-05-01"]
             [:duo:flexibeleInstroom
              [:duo:beginInstroomperiode "2019-08-24"]
              [:duo:eindeInstroomperiode "2019-08-24"]]
             [:duo:eindeAanmeldperiode "2019-08-01"]
             [:duo:toestemmingVereistVoorAanmelding "JA"]
             [:duo:einddatum "2023-06-15"]
             [:duo:prijs
              [:duo:soort "TOTALE_VERPLICHTE_KOSTEN"]
              [:duo:bedrag "340.84"]]]
            [:duo:kenmerken [:duo:kenmerknaam "eigenAangebodenOpleidingSleutel"] [:duo:kenmerkwaardeTekst "20010000-0000-0000-0000-000000000000"]]
            [:duo:kenmerken [:duo:kenmerknaam "toestemmingDeelnameSTAP"] [:duo:kenmerkwaardeEnumeratiewaarde "TOESTEMMING_VERLEEND"]]
            [:duo:kenmerken [:duo:kenmerknaam "vorm"] [:duo:kenmerkwaardeEnumeratiewaarde "VOLTIJD"]]
            [:duo:kenmerken [:duo:kenmerknaam "voertaal"] [:duo:kenmerkwaardeEnumeratiewaarde "NLD"]]]
           (-> (ooapi.loader/load-entities ooapi.loader/ooapi-file-loader
                                           {::ooapi/id "20010000-0000-0000-0000-000000000000" ::ooapi/type "program"})
               ::ooapi/entity
               (aangeboden-opl/->aangeboden-opleiding :program "1234O1234" "program")))))
  (testing "program with nonstandard mode of delivery"
    (is (= [:duo:aangebodenHOOpleiding
            [:duo:aangebodenOpleidingCode "20010000-0000-0000-0000-000000000000"]
            [:duo:onderwijsaanbiedercode "110A133"]
            [:duo:onderwijslocatiecode "123X122"]
            [:duo:begindatum "2019-08-24"]
            [:duo:eersteInstroomDatum "2019-08-24"]
            [:duo:einddatum "2022-08-31"]
            [:duo:opleidingseenheidSleutel "1234O1234"]
            [:duo:aangebodenHOOpleidingPeriode
             [:duo:begindatum "2019-08-24"]
             [:duo:eigenNaamAangebodenOpleiding "Biology"]
             [:duo:eigenNaamInternationaal "Biology"]
             [:duo:eigenOmschrijving "The study of life"]
             [:duo:kenmerken [:duo:kenmerknaam "samenwerkendeOnderwijsaanbiedercode"] [:duo:kenmerkwaardeTekst "122A113"]]
             [:duo:kenmerken [:duo:kenmerknaam "deficientie"] [:duo:kenmerkwaardeEnumeratiewaarde "DEFICIENTIES"]]
             [:duo:kenmerken [:duo:kenmerknaam "versneldTraject"] [:duo:kenmerkwaardeEnumeratiewaarde "VERSNELD_TRAJECT"]]
             [:duo:kenmerken [:duo:kenmerknaam "eigenNaamKort"] [:duo:kenmerkwaardeTekst "BIO"]]
             [:duo:kenmerken [:duo:kenmerknaam "propedeutischeFase"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_PROPEDEUTISCHE_FASE"]]
             [:duo:kenmerken [:duo:kenmerknaam "eisenWerkzaamheden"] [:duo:kenmerkwaardeEnumeratiewaarde "EISEN"]]
             [:duo:kenmerken [:duo:kenmerknaam "buitenlandsePartner"] [:duo:kenmerkwaardeTekst "Harvard University"]]
             [:duo:kenmerken [:duo:kenmerknaam "studiekeuzecheck"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_STUDIEKEUZE_CHECK"]]
             [:duo:kenmerken [:duo:kenmerknaam "website"] [:duo:kenmerkwaardeTekst "https://bijvak.nl"]]]
            [:duo:aangebodenHOOpleidingPeriode
             [:duo:begindatum "2021-09-01"]
             [:duo:eigenNaamAangebodenOpleiding "OVERRIDE Biology"]
             [:duo:eigenOmschrijving "The study of life"]
             [:duo:kenmerken [:duo:kenmerknaam "samenwerkendeOnderwijsaanbiedercode"] [:duo:kenmerkwaardeTekst "122A113"]]
             [:duo:kenmerken [:duo:kenmerknaam "deficientie"] [:duo:kenmerkwaardeEnumeratiewaarde "DEFICIENTIES"]]
             [:duo:kenmerken [:duo:kenmerknaam "versneldTraject"] [:duo:kenmerkwaardeEnumeratiewaarde "VERSNELD_TRAJECT"]]
             [:duo:kenmerken [:duo:kenmerknaam "eigenNaamKort"] [:duo:kenmerkwaardeTekst "BIO"]]
             [:duo:kenmerken [:duo:kenmerknaam "propedeutischeFase"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_PROPEDEUTISCHE_FASE"]]
             [:duo:kenmerken [:duo:kenmerknaam "eisenWerkzaamheden"] [:duo:kenmerkwaardeEnumeratiewaarde "EISEN"]]
             [:duo:kenmerken [:duo:kenmerknaam "buitenlandsePartner"] [:duo:kenmerkwaardeTekst "Harvard University"]]
             [:duo:kenmerken [:duo:kenmerknaam "studiekeuzecheck"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_STUDIEKEUZE_CHECK"]]
             [:duo:kenmerken [:duo:kenmerknaam "website"] [:duo:kenmerkwaardeTekst "https://bijvak.nl"]]]
            [:duo:aangebodenHOOpleidingCohort
             [:duo:cohortcode "1234qwe12"]
             [:duo:cohortstatus "O"]
             [:duo:kenmerken
              [:duo:kenmerknaam "deelnemersplaatsen"]
              [:duo:kenmerkwaardeGetal 200]]
             [:duo:kenmerken
              [:duo:kenmerknaam "opleidingsvorm"]
              [:duo:kenmerkwaardeEnumeratiewaarde "COACHING"]]
             [:duo:beginAanmeldperiode "2019-05-01"]
             [:duo:flexibeleInstroom
              [:duo:beginInstroomperiode "2019-08-24"]
              [:duo:eindeInstroomperiode "2019-08-24"]]
             [:duo:eindeAanmeldperiode "2019-08-01"]
             [:duo:toestemmingVereistVoorAanmelding "JA"]
             [:duo:einddatum "2023-06-15"]
             [:duo:prijs
              [:duo:soort "TOTALE_VERPLICHTE_KOSTEN"]
              [:duo:bedrag "340.84"]]]
            [:duo:kenmerken [:duo:kenmerknaam "eigenAangebodenOpleidingSleutel"] [:duo:kenmerkwaardeTekst "20010000-0000-0000-0000-000000000000"]]
            [:duo:kenmerken [:duo:kenmerknaam "toestemmingDeelnameSTAP"] [:duo:kenmerkwaardeEnumeratiewaarde "TOESTEMMING_VERLEEND"]]
            [:duo:kenmerken [:duo:kenmerknaam "vorm"] [:duo:kenmerkwaardeEnumeratiewaarde "VOLTIJD"]]
            [:duo:kenmerken [:duo:kenmerknaam "voertaal"] [:duo:kenmerkwaardeEnumeratiewaarde "NLD"]]]
           (-> (ooapi.loader/load-entities ooapi.loader/ooapi-file-loader
                                           {::ooapi/id "20010000-0000-0000-0000-000000000000" ::ooapi/type "program"})
               ::ooapi/entity
               (assoc-in [:offerings 0 :consumers 0 :modeOfDelivery] ["coaching"])
               (aangeboden-opl/->aangeboden-opleiding :program "1234O1234" "program")))))
  (testing "program with mode of delivery in consumers"
    (is (= [:duo:aangebodenHOOpleiding
            [:duo:aangebodenOpleidingCode "20010000-0000-0000-0000-000000000000"]
            [:duo:onderwijsaanbiedercode "110A133"]
            [:duo:onderwijslocatiecode "123X122"]
            [:duo:begindatum "2019-08-24"]
            [:duo:eersteInstroomDatum "2019-08-24"]
            [:duo:einddatum "2022-08-31"]
            [:duo:opleidingseenheidSleutel "1234O1234"]
            [:duo:aangebodenHOOpleidingPeriode
             [:duo:begindatum "2019-08-24"]
             [:duo:eigenNaamAangebodenOpleiding "Biology"]
             [:duo:eigenNaamInternationaal "Biology"]
             [:duo:eigenOmschrijving "The study of life"]
             [:duo:kenmerken [:duo:kenmerknaam "samenwerkendeOnderwijsaanbiedercode"] [:duo:kenmerkwaardeTekst "122A113"]]
             [:duo:kenmerken [:duo:kenmerknaam "deficientie"] [:duo:kenmerkwaardeEnumeratiewaarde "DEFICIENTIES"]]
             [:duo:kenmerken [:duo:kenmerknaam "versneldTraject"] [:duo:kenmerkwaardeEnumeratiewaarde "VERSNELD_TRAJECT"]]
             [:duo:kenmerken [:duo:kenmerknaam "eigenNaamKort"] [:duo:kenmerkwaardeTekst "BIO"]]
             [:duo:kenmerken [:duo:kenmerknaam "propedeutischeFase"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_PROPEDEUTISCHE_FASE"]]
             [:duo:kenmerken [:duo:kenmerknaam "eisenWerkzaamheden"] [:duo:kenmerkwaardeEnumeratiewaarde "EISEN"]]
             [:duo:kenmerken [:duo:kenmerknaam "buitenlandsePartner"] [:duo:kenmerkwaardeTekst "Harvard University"]]
             [:duo:kenmerken [:duo:kenmerknaam "studiekeuzecheck"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_STUDIEKEUZE_CHECK"]]
             [:duo:kenmerken [:duo:kenmerknaam "website"] [:duo:kenmerkwaardeTekst "https://bijvak.nl"]]]
            [:duo:aangebodenHOOpleidingPeriode
             [:duo:begindatum "2021-09-01"]
             [:duo:eigenNaamAangebodenOpleiding "OVERRIDE Biology"]
             [:duo:eigenOmschrijving "The study of life"]
             [:duo:kenmerken [:duo:kenmerknaam "samenwerkendeOnderwijsaanbiedercode"] [:duo:kenmerkwaardeTekst "122A113"]]
             [:duo:kenmerken [:duo:kenmerknaam "deficientie"] [:duo:kenmerkwaardeEnumeratiewaarde "DEFICIENTIES"]]
             [:duo:kenmerken [:duo:kenmerknaam "versneldTraject"] [:duo:kenmerkwaardeEnumeratiewaarde "VERSNELD_TRAJECT"]]
             [:duo:kenmerken [:duo:kenmerknaam "eigenNaamKort"] [:duo:kenmerkwaardeTekst "BIO"]]
             [:duo:kenmerken [:duo:kenmerknaam "propedeutischeFase"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_PROPEDEUTISCHE_FASE"]]
             [:duo:kenmerken [:duo:kenmerknaam "eisenWerkzaamheden"] [:duo:kenmerkwaardeEnumeratiewaarde "EISEN"]]
             [:duo:kenmerken [:duo:kenmerknaam "buitenlandsePartner"] [:duo:kenmerkwaardeTekst "Harvard University"]]
             [:duo:kenmerken [:duo:kenmerknaam "studiekeuzecheck"] [:duo:kenmerkwaardeEnumeratiewaarde "GEEN_STUDIEKEUZE_CHECK"]]
             [:duo:kenmerken [:duo:kenmerknaam "website"] [:duo:kenmerkwaardeTekst "https://bijvak.nl"]]]
            [:duo:aangebodenHOOpleidingCohort
             [:duo:cohortcode "1234qwe12"]
             [:duo:cohortstatus "O"]
             [:duo:kenmerken
              [:duo:kenmerknaam "deelnemersplaatsen"]
              [:duo:kenmerkwaardeGetal 200]]
             [:duo:kenmerken
              [:duo:kenmerknaam "opleidingsvorm"]
              [:duo:kenmerkwaardeEnumeratiewaarde "COACHING"]]
             [:duo:beginAanmeldperiode "2019-05-01"]
             [:duo:flexibeleInstroom
              [:duo:beginInstroomperiode "2019-08-24"]
              [:duo:eindeInstroomperiode "2019-08-24"]]
             [:duo:eindeAanmeldperiode "2019-08-01"]
             [:duo:toestemmingVereistVoorAanmelding "JA"]
             [:duo:einddatum "2023-06-15"]
             [:duo:prijs
              [:duo:soort "TOTALE_VERPLICHTE_KOSTEN"]
              [:duo:bedrag "340.84"]]]
            [:duo:kenmerken [:duo:kenmerknaam "eigenAangebodenOpleidingSleutel"] [:duo:kenmerkwaardeTekst "20010000-0000-0000-0000-000000000000"]]
            [:duo:kenmerken [:duo:kenmerknaam "toestemmingDeelnameSTAP"] [:duo:kenmerkwaardeEnumeratiewaarde "TOESTEMMING_VERLEEND"]]
            [:duo:kenmerken [:duo:kenmerknaam "vorm"] [:duo:kenmerkwaardeEnumeratiewaarde "VOLTIJD"]]
            [:duo:kenmerken [:duo:kenmerknaam "voertaal"] [:duo:kenmerkwaardeEnumeratiewaarde "NLD"]]]
           (-> (ooapi.loader/load-entities (ooapi.loader/validating-loader #(let [json (ooapi.loader/ooapi-file-loader %)]
                                                                              (if (#{"program-offerings" "course-offerings"} (::ooapi/type %))
                                                                                (-> json
                                                                                    (update-in [:items 0] dissoc :modeOfDelivery)
                                                                                    (assoc-in [:items 0 :consumers 0 :modeOfDelivery] ["coaching"]))
                                                                                json)))
                                           {::ooapi/id "20010000-0000-0000-0000-000000000000" ::ooapi/type "program"})
               ::ooapi/entity
               (aangeboden-opl/->aangeboden-opleiding :program "1234O1234" "program"))))))
