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

(ns nl.surf.eduhub-rio-mapper.rio.loader
  "Gets the RIO opleidingscode given an OOAPI entity ID."
  (:require
    [clojure.data.json :as json]
    [clojure.data.xml :as clj-xml]
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.specs.relations :as relations]
    [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
    [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.utils.logging :as logging]
    [nl.surf.eduhub-rio-mapper.utils.soap :as soap]
    [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils]
    [nl.surf.eduhub-rio-mapper.utils.xml-validator :as xml-validator])
  (:import (org.w3c.dom Element)))

(def aangeboden-opleiding-type "aangebodenOpleiding")
(def aangeboden-opleidingen-van-organisatie-type "aangebodenOpleidingenVanOrganisatie")
(def opleidingseenheid-type "opleidingseenheid")
(def opleidingseenheden-van-organisatie-type "opleidingseenhedenVanOrganisatie")
(def opleidingsrelaties-bij-opleidingseenheid-type "opleidingsrelatiesBijOpleidingseenheid")

(def opleidingseenheid-namen
  #{:hoOpleiding :particuliereOpleiding :hoOnderwijseenhedencluster :hoOnderwijseenheid})

(def aangeboden-opleiding-namen
  #{:aangebodenHOOpleidingsonderdeel :aangebodenHOOpleiding :aangebodenParticuliereOpleiding})

;; NOTE: aangeboden opleidingen are referenced by OOAPI UID
(def aangeboden-opleiding-types #{aangeboden-opleiding-type
                                  aangeboden-opleidingen-van-organisatie-type})

(def valid-get-types (into aangeboden-opleiding-types
                           #{opleidingseenheid-type
                             opleidingseenheden-van-organisatie-type
                             opleidingsrelaties-bij-opleidingseenheid-type}))

(def schema "http://duo.nl/schema/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4")
(def contract "http://duo.nl/contract/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4")
(def validator  (xml-validator/create-validation-fn "DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4.xsd"))

;; De externe identificatie komt niet voor in RIO
;; Handled separately because this is an expected outcome, and handling it is part of the normal program flow.
(def missing-entity "A01161")

(defn goedgekeurd? [^Element element]
  {:pre [element]}
  (= "true" (xml-utils/single-xml-unwrapper element "ns2:requestGoedgekeurd")))

(defn log-rio-action-response [msg element]
  (logging/with-mdc
    {:identificatiecodeBedrijfsdocument (xml-utils/single-xml-unwrapper element "ns2:identificatiecodeBedrijfsdocument")}
    (log/debugf (format "RIO %s; SUCCESS: %s" msg (goedgekeurd? element)))))

(defn- handle-resolver-success [element]
  ;; TODO: this is ugly, but we don't know at this stage what entity we tried to resolve.
  (let [code (or (xml-utils/single-xml-unwrapper element "ns2:opleidingseenheidcode")
                 (xml-utils/single-xml-unwrapper element "ns2:aangebodenOpleidingCode"))]
    (log-rio-action-response (str "SUCCESSFUL RESOLVE:" code) element)
    code))

(defn- handle-resolver-error [element]
  (let [foutmelding (-> element
                        xml-utils/element->edn
                        :opvragen_rioIdentificatiecode_response
                        :foutmelding)
        id          (-> foutmelding
                        :sleutelgegeven
                        :sleutelwaarde)
        foutcode    (:foutcode foutmelding)
        error-msg   (if (= missing-entity foutcode)
                      (str "Object with id (" id ") not found in RIO via resolve")
                      (str "Resolve of object " id " failed with error code " foutcode))]
    (log-rio-action-response error-msg element)
    (when-not (= missing-entity foutcode)
      (throw (ex-info error-msg {:retryable? false})))))

(defn- rio-resolver-response [^Element element]
  {:pre [element]}
  (if (goedgekeurd? element)
    (handle-resolver-success element)
    (handle-resolver-error element)))

(defn- rio-relation-getter-response [^Element element]
  {:post [(s/valid? (s/nilable ::relations/relation-vector) %)]}
  (when (goedgekeurd? element)
    (when-let [samenhang (-> element xml-utils/element->edn
                             :opvragen_opleidingsrelatiesBijOpleidingseenheid_response
                             :samenhangOpleidingseenheid)]
      (s/assert ::rio/opleidingscode (:opleidingseenheidcode samenhang))
      (when-let [related-eduspecs (-> samenhang :gerelateerdeOpleidingseenheid)]
        (->> (if (map? related-eduspecs) [related-eduspecs] related-eduspecs)
             ;; Accredited HoOpleidingen have a AFGELEID_VAN relation which is not relevant for the edumapper
             ;; and should be ignored.
             (filter (fn [m] (not= (:opleidingsrelatiesoort m) "AFGELEID_VAN")))
             (mapv (fn [m]
                     (s/assert ::rio/opleidingscode (:opleidingseenheidcode m))
                     {:valid-from             (:opleidingsrelatieBegindatum m)
                      :valid-to               (:opleidingsrelatieEinddatum m)
                      :opleidingseenheidcodes #{(:opleidingseenheidcode samenhang) (:opleidingseenheidcode m)}})))))))

(defn make-datamap
  [sender-oin recipient-oin]
  {:schema        schema
   :contract      contract
   :validator     validator
   :sender-oin    sender-oin
   :recipient-oin recipient-oin
   :to-url        (str "https://duo.nl/RIO/services/raadplegen4.0?oin=" recipient-oin)
   :from-url      (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" sender-oin)})

(defn guard-getter-response
  [{:keys [body]} type tag]
  (when-not (re-find (re-pattern (str "<" tag "[^>]*>")) body)
    (throw (ex-info (str "Unexpected response, it does not contain tag: " tag)
                    {:type type, :body body})))
  body)

(defn- extract-body-element [response tag]
  (-> response
      xml-utils/str->dom
      .getDocumentElement
      (xml-utils/get-in-dom ["SOAP-ENV:Body" tag])))

(defn make-resolver
  "Return a RIO resolver.

  The resolver takes an `id` and an `institution-oin` and returns a
  map with errors, or the corresponding RIO opleidingscode."
  [{:keys [read-url credentials recipient-oin connection-timeout-millis]}]
  {:pre [read-url]}
  (fn resolver
    [type id institution-oin]
    {:pre [institution-oin]}
    (when id
      (logging/with-mdc
        {:soap-action "opvragen_rioIdentificatiecode" :ooapi-id id}
        (let [xml (soap/prepare-soap-call "opvragen_rioIdentificatiecode"
                                          [[(case type
                                              "education-specification" :duo:eigenOpleidingseenheidSleutel
                                              ("course" "program") :duo:eigenAangebodenOpleidingSleutel)
                                            id]]
                                          (make-datamap institution-oin recipient-oin)
                                          credentials)
              request {:url read-url
                       :method :post
                       :body xml
                       :headers {"SOAPAction" (str contract "/opvragen_rioIdentificatiecode")}
                       :connection-timeout connection-timeout-millis
                       :content-type :xml}
              tag "ns2:opvragen_rioIdentificatiecode_response"]
          (-> (http-utils/send-http-request (merge credentials request))
              (guard-getter-response "rioIdentificatiecode" tag)
              (extract-body-element tag)
              rio-resolver-response))))))

(defn- valid-onderwijsbestuurcode? [code]
  {:pre [code]}
  (re-matches #"\d\d\dB\d\d\d" code))

(defn find-named-element [response-body name-set]
  (-> response-body
      clj-xml/parse-str
      xml-seq
      (xml-utils/find-in-xmlseq #(when (name-set (:tag %))
                                   %))))

(defn find-rio-object [rio-code getter institution-oin type]
  {:pre [rio-code]}
  (let [[code-name name-set] (if (= type opleidingseenheid-type)
                               [::rio/opleidingscode opleidingseenheid-namen]
                               [::rio/aangeboden-opleiding-code aangeboden-opleiding-namen])]
    (-> (getter {::rio/type       type
                 code-name        rio-code
                 :institution-oin institution-oin
                 :response-type   :literal})
        (find-named-element name-set))))

(defn find-opleidingseenheid [rio-code getter institution-oin]
  (find-rio-object rio-code getter institution-oin opleidingseenheid-type))

(defn find-aangebodenopleiding [rio-code getter institution-oin]
  (find-rio-object rio-code getter institution-oin aangeboden-opleiding-type))

(defn rio-finder [getter {::ooapi/keys [type] ::rio/keys [opleidingscode aangeboden-opleiding-code] :keys [institution-oin] :as _request}]
  (case type
    "education-specification" (find-rio-object opleidingscode getter institution-oin opleidingseenheid-type)
    ("course" "program") (find-rio-object aangeboden-opleiding-code getter institution-oin aangeboden-opleiding-type)))

(defn- rio-xml-getter-response [^Element element]
  (assert (goedgekeurd? element))                           ; should fail elsewhere with error http code otherwise
  (-> element xml-utils/dom->str))

(defn- rio-json-getter-response [^Element element]
  (assert (goedgekeurd? element))                           ; should fail elsewhere with error http code otherwise
  (-> element xml-utils/element->edn json/write-str))

(defn- generate-rio-sexp-request [{::ooapi/keys [id]
                                   ::rio/keys   [type opleidingscode aangeboden-opleiding-code code]
                                   :keys        [pagina]
                                   :or          {pagina 0}}]
  (condp = type
    ;; Command line only.
    opleidingseenheden-van-organisatie-type
    [[:duo:onderwijsbestuurcode code]
     [:duo:pagina pagina]]

    ;; Command line only.
    aangeboden-opleidingen-van-organisatie-type
    [[:duo:onderwijsaanbiedercode id]
     [:duo:pagina pagina]]

    opleidingsrelaties-bij-opleidingseenheid-type
    [[:duo:opleidingseenheidcode opleidingscode]]

    aangeboden-opleiding-type
    [[:duo:aangebodenOpleidingCode aangeboden-opleiding-code]]

    opleidingseenheid-type
    [[:duo:opleidingseenheidcode opleidingscode]]))

(defn- handle-response-for-type [response-type rio-entity-type resp-obj]
  (cond
    (= :literal response-type)
    resp-obj

    (= :json response-type)
    (rio-json-getter-response resp-obj)

    (= :xml response-type)
    (rio-xml-getter-response resp-obj)

    ;; If response-type is unspecified, use edn for relations and json for everything else
    (= rio-entity-type opleidingsrelaties-bij-opleidingseenheid-type)
    (rio-relation-getter-response resp-obj)

    :else
    (rio-json-getter-response resp-obj)))

;; response-type can be optionally specified. Valid values: :literal, :xml, :json
(defn- rio-get [{::ooapi/keys [id]
                 ::rio/keys   [type opleidingscode aangeboden-opleiding-code code]
                 :keys        [response-type]
                 :as          rio-get-data}
                request-template
                soap-call-fn]
  ;; preconditions
  {:pre [(or (aangeboden-opleiding-types type)
             opleidingscode
             code
             (throw (ex-info "first precondition" rio-get-data)))
         (or (not= type aangeboden-opleidingen-van-organisatie-type)
             id)
         ;; is this precondition correct?
         ;; if the type is aangebodenOpleiding, there must be an aangeboden-opleiding-code
         (or (not= type aangeboden-opleiding-type)
             aangeboden-opleiding-code
             (throw (ex-info "third precondition - aangeboden-opleiding-code required for type aangebodenOpleiding" rio-get-data)))]}

  ;; assertions
  (when-not (valid-get-types type)
    (throw (ex-info (str "Unexpected type: " type)
                    {:id             id
                     :opleidingscode opleidingscode
                     :retryable?     false})))

  (when (and (= type opleidingseenheden-van-organisatie-type)
             (not (valid-onderwijsbestuurcode? code)))
    (throw (ex-info (str "Type 'onderwijsbestuurcode' has ID invalid format: " code)
                    {:type       type
                     :retryable? false})))

  ;; function body
  (logging/with-mdc {:soap-action (str "opvragen_" type)}
    (let [tag              (str "ns2:opvragen_" type "_response")
          response-body    (-> request-template
                               (assoc
                                 :method       :post
                                 :body         (soap-call-fn (generate-rio-sexp-request rio-get-data))
                                 :content-type :xml)
                               http-utils/send-http-request
                               (guard-getter-response type tag))
          body-element     (extract-body-element response-body tag)
          resp-obj         (if (= :literal response-type)
                             response-body
                             body-element)]
      (log-rio-action-response type body-element)
      (handle-response-for-type response-type type resp-obj))))

(defn make-getter
  "Return a function that looks up an 'aangeboden opleiding' by id.

  The getter takes an program or course id and returns a map of
  data with the RIO attributes, or errors."
  [{:keys [read-url credentials recipient-oin]}]
  {:pre [read-url]}
  (fn [{::rio/keys [type]
        :keys      [institution-oin]
        :as        rio-get-data}]
    (let [soap-action          (str "opvragen_" type)
          request-template     (assoc credentials
                                 :url read-url
                                 :headers {"SOAPAction" (str contract "/" soap-action)})
          prepare-soap-call-fn (fn [rio-sexp]
                                 (soap/prepare-soap-call soap-action
                                                         rio-sexp
                                                         (make-datamap institution-oin recipient-oin)
                                                         credentials))]
      (rio-get rio-get-data request-template prepare-soap-call-fn))))
