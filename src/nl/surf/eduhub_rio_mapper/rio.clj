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

(ns nl.surf.eduhub-rio-mapper.rio
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec]])
  (:import [java.io PushbackReader]))

(s/def ::OpleidingsEenheidID-v01 (re-spec #"\d{4}O\d{4}"))
(s/def ::opleidingscode ::OpleidingsEenheidID-v01)

(def specifications (edn/read (PushbackReader. (io/reader (io/resource "ooapi-mappings.edn")))))
(def xsd-beheren (edn/read (PushbackReader. (io/reader (io/resource "beheren-schema.edn")))))

(defn ooapi-mapping? [name]
  (boolean (get-in specifications [:mappings name])))

(defn ooapi-mapping [name key]
  {:pre [(string? name)]}
  (when key (get-in specifications [:mappings name key])))

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
  {:date    :duo:kenmerkwaardeDatum
   :string  :duo:kenmerkwaardeTekst
   :enum    :duo:kenmerkwaardeEnumeratiewaarde
   :number  :duo:kenmerkwaardeGetal
   :boolean :duo:kenmerkwaardeBoolean})

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

(defn kenmerken [name type value]
  (when value
     [:duo:kenmerken
       [:duo:kenmerknaam name]
       [(type-mapping type) value]]))

;;; XML generation

(defn name->type [nm]
  {:pre [(string? nm)]}
  (str (str/upper-case (subs nm 0 1)) (subs nm 1)))

(defn duoize [naam]
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
   "internationaleNaamDuits" :string
   "opleidingsvorm" :enum
   "propedeutischeFase" :enum
   "samenwerkendeOnderwijsaanbiedercode" :string
   "soort" :enum
   "studiekeuzecheck" :enum
   "toestemmingDeelnameSTAP" :enum
   "versneldTraject" :enum
   "voertaal" :enum
   "vorm" :enum
   "website" :string})

(defn attr-name->kenmerk-type [attr-name]
  (if-let [type (attr-name->kenmerk-type-mapping attr-name)]
    type
    (do
      ;; FIXME: This should be an error?!
      (log/warnf "Missing type for kenmerk (%s), assuming it's :enum" attr-name)
      :enum)))

(defn process-attribute [attr-name attr-value kenmerk]
  (condp apply [attr-value]
    vector?
    (vec (mapcat (fn [x] (process-attribute attr-name x kenmerk)) attr-value))

    map?
    [(into [(duoize attr-name)]
           (mapv (fn [[key value]] [(duoize key) value]) attr-value))]

    [(if kenmerk
       (kenmerken attr-name (attr-name->kenmerk-type attr-name) attr-value)
       [(duoize attr-name) attr-value])]))

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

(defn- process-attributes [{:keys [kenmerk name]} rio-obj]
  (when-let [attr-value (rio-obj (keyword name))]
    (process-attribute name attr-value kenmerk)))

(defn- process-children [child-type rio-obj]
  (->> (rio-obj child-type)
       (mapv (fn [child] (->xml child child-type)))))

(defn ->xml [rio-obj object-name]
  {:pre [(string? object-name)]}
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
