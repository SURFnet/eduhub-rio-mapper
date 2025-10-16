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

(ns nl.surf.eduhub-rio-mapper.rio.helper
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io PushbackReader]))

(def specifications (edn/read (PushbackReader. (io/reader (io/resource "ooapi-mappings.edn")))))
(def xsd-beheren (edn/read (PushbackReader. (io/reader (io/resource "beheren-schema.edn")))))
(def xsd-types (edn/read (PushbackReader. (io/reader (io/resource "beheren-types.edn")))))

(defn ooapi-mapping
  "Look up the matching rio key for given ooapi key (or keys) of rio type `name` (ooapi-mappings.edn)."
  [name key]
  {:pre [(string? name)]}
  (when key
    (if (coll? key)
      (mapv #(get-in specifications [:mappings name %]) key)
      (get-in specifications [:mappings name key]))))

;; Helpers

(defn level-sector-mapping
  "Map level and sector to RIO `niveau`.

  Returns nil on invalid level+sector mapping."
  [level sector]
  (case level
    "undefined" "ONBEPAALD"
    "nt2-1" "NT2-I"
    "nt2-2" "NT2-II"
    (case sector
      "secondary vocational education"
      (case level
        "secondary vocational education" "MBO"
        "secondary vocational education 1" "MBO-1"
        "secondary vocational education 2" "MBO-2"
        "secondary vocational education 3" "MBO-3"
        "secondary vocational education 4" "MBO-4"
        nil)

      "higher professional education"
      (case level
        "associate degree" "HBO-AD"
        "bachelor" "HBO-BA"
        "master" "HBO-MA"
        "doctoral" "HBO-PM"
        "undivided" "HBO-O"
        nil)

      "university education"
      (case level
        "bachelor" "WO-BA"
        "master" "WO-MA"
        "doctoral" "WO-PM"
        "undivided" "WO-O"
        nil)
      nil)))

(def type-mapping
  {:date       :duo:kenmerkwaardeDatum
   :string     :duo:kenmerkwaardeTekst
   :enum       :duo:kenmerkwaardeEnumeratiewaarde
   :enum-array :duo:kenmerkwaardeEnumeratiewaarde
   :number     :duo:kenmerkwaardeGetal
   :boolean    :duo:kenmerkwaardeBoolean})

(defn narrow-isced
  "When given an ISCED-F detailed field, return the narrow version."
  [s]
  (when s
    (if (< (count s) 4)
      s
      ;; Last digit is the detailed info, we can remove it
      ;; See also
      ;;
      ;; ISCED FIELDS OF EDUCATION AND TRAINING 2013 (ISCED-F 2013)
      ;; Appendix I. ISCED fields of education and training
      ;;
      ;; http://uis.unesco.org/sites/default/files/documents/isced-fields-of-education-and-training-2013-en.pdf
      (subs s 0 3))))

(defn- kenmerken [name type value]
  (when value
    (if (= type :enum-array)
      (mapv (fn [v]
              [:duo:kenmerken
               [:duo:kenmerknaam name]
               [(type-mapping type) v]])
            (if (coll? value) value [value]))
      [[:duo:kenmerken
       [:duo:kenmerknaam name]
       [(type-mapping type) value]]])))

;;; XML generation

(defn- name->type [nm]
  {:pre [(string? nm)]}
  (str (str/upper-case (subs nm 0 1)) (subs nm 1)))

(defn- duoize [naam]
  (keyword (str "duo:" (if (keyword? naam) (name naam) naam))))

(def attr-name->kenmerk-type-mapping
  {"buitenlandsePartner" :string
   "deelnemersplaatsen" :number
   "categorie" :enum
   "deficientie" :enum
   "eigenNaamKort" :string
   "eigenAangebodenOpleidingSleutel" :string
   "eigenOpleidingseenheidSleutel" :string
   "eisenWerkzaamheden" :enum
   "laatsteInstroomdatum" :date
   "internationaleNaamDuits" :string
   "opleidingsvorm" :enum
   "propedeutischeFase" :enum
   "samenwerkendeOnderwijsaanbiedercode" :string
   "soort" :enum
   "studiekeuzecheck" :enum
   "versneldTraject" :enum
   "voertaal" :enum-array
   "vorm" :enum
   "website" :string})

(defn- attr-name->kenmerk-type [attr-name]
  (if-let [type (attr-name->kenmerk-type-mapping attr-name)]
    type
    (do
      ;; FIXME: This should be an error?!
      (log/warnf "Missing type for kenmerk (%s), assuming it's :enum" attr-name)
      :enum)))

(defn truncate [s n]
  {:pre [(and (integer? n) (pos? n))]}
  (if (string? s)
    (subs s 0 (min (count s) n))
    s))

(defn- render-name-value [attr-name attr-value type]
  (let [type-data (xsd-types type)
        max-len   (-> type-data :restrictions :maxLength)
        ;; Both Teksttype and VrijeTekstType seem to be used for free-form text, the kind of text
        ;; that may be truncated. Types like WaardenlijstType-v01 and IdentificatiecodeType also have
        ;; max-length restrictions, but for those, we prefer to fail rather than silently truncate.
        value     (if (and max-len (#{"Teksttype" "VrijeTekstType"} (:base type-data)))
                    (truncate attr-value max-len)
                    attr-value)]
    [(duoize attr-name) value]))

(defn- process-attribute [attr-name attr-value kenmerk type]
  (condp apply [attr-value]
    vector?
    (->> attr-value
         (mapcat #(process-attribute attr-name % kenmerk type))
         vec)

    map?
    [(into [(duoize attr-name)]
           (mapv (fn [[key value]] [(duoize key) value]) attr-value))]

    (if kenmerk
      (kenmerken attr-name (attr-name->kenmerk-type attr-name) attr-value)
      [(render-name-value attr-name attr-value type)])))

(defn wrapper-periodes-cohorten [rio-obj]
  (fn [key]
    (rio-obj (if (keyword? key)
               key
               (case key
                 ("aangebodenHOOpleidingsonderdeelPeriode" "aangebodenHOOpleidingPeriode" "aangebodenParticuliereOpleidingPeriode"
                   "hoOnderwijseenheidPeriode" "hoOpleidingPeriode" "particuliereOpleidingPeriode" "hoOnderwijseenhedenclusterPeriode")
                 :periodes
                 ("aangebodenHOOpleidingsonderdeelCohort" "aangebodenHOOpleidingCohort" "aangebodenParticuliereOpleidingCohort")
                 :cohorten)))))

(defn- cohort? [element] (= (:type element) "AangebodenOpleidingCohort"))

(declare ->xml)

(defn- process-attributes [{:keys [kenmerk name type]} rio-obj]
  {:pre [(or (fn? rio-obj)
             (map? rio-obj))]}
  (when-let [attr-value (rio-obj (keyword name))]
    (process-attribute name attr-value kenmerk type)))

(defn- process-children [child-type rio-obj]
  (mapv (fn [child]
          {:pre [(or (fn? child)
                     (map? child))]}
          (->xml child child-type))
        (rio-obj child-type)))

(defn ->xml [rio-obj object-name]
  {:pre [(string? object-name)
         (or (fn? rio-obj)
             (map? rio-obj))]}
  (let [process #(if (:ref %)
                   (process-children (if (cohort? %) (str object-name "Cohort")
                                                     (str object-name "Periode"))
                                     rio-obj)
                   (process-attributes % rio-obj))]
    (into [(duoize object-name)]
          (->> (xsd-beheren (name->type object-name))
               ; choice contains a list, and mapcat flattens the list;
               ; otherwise, (usually, choice is a rare attribute), it is a no op, eg (mapcat #(vector %))
               (mapcat #(get % :choice [%]))
               (mapcat process)
               vec))))

(defn blocking-retry
  "Calls f and retries if it returns nil or false.

  Sleeps between each invocation as specified in retry-delays-seconds.
  Returns return value of f when successful.
  Returns nil when as many retries as delays have taken place. "
  [f {:keys [rio-retry-attempts-seconds] :as _rio-config} action]
  (loop [retry-delays-seconds rio-retry-attempts-seconds]
    (or
     (f)
     (when-not (empty? retry-delays-seconds)
       (let [[head & tail] retry-delays-seconds]
         (log/warn (format "%s failed - sleeping for %s seconds." action head))
         (Thread/sleep (long (* 1000 head)))
         (recur tail))))))
