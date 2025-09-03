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

(ns nl.surf.eduhub-rio-mapper.commands.link
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.rio.helper :as rio-helper]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]))

(defn- strip-duo [kw]
  (-> kw
      name
      (str/replace #"^duo:" "")))

(defn- duo-keyword [x]
  (keyword (str "duo:" (name x))))

(defn- rio-obj-name-in-set? [rio-obj name-set]
  (let [rio-obj-name (-> rio-obj first strip-duo keyword)]
    (some? (name-set rio-obj-name))))

(defn aangeboden-opleiding? [rio-obj]
  (rio-obj-name-in-set? rio-obj rio.loader/aangeboden-opleiding-namen))

(defn opleidingseenheid? [rio-obj]
  (rio-obj-name-in-set? rio-obj rio.loader/opleidingseenheid-namen))

(defn- xmlclj->duo-hiccup [x]
  {:pre [x (:tag x)]}
  (into
    [(duo-keyword (:tag x))]
    (mapv #(if (:tag %) (xmlclj->duo-hiccup %) %)
          (:content x))))

(defn- sleutel-finder [sleutel-name]
  (fn [element]
    (when (and (sequential? element)
               (= [:duo:kenmerken [:duo:kenmerknaam sleutel-name]]
                  (vec (take 2 element))))
      (-> element last last))))

(defn- sleutel-changer [id finder]
  (fn [element]
    (if (finder element)
      ;; If id is nil, leave out element by returning nil, otherwise change `sleutel` value`
      (when id (assoc-in element [2 1] id))
      ;; If element is not a `sleutel`, return element unchanged
      element)))

(defn- attribute-adapter [rio-obj k]
  (let [value
        (some #(and (sequential? %)
                    (or
                      (and (= (duo-keyword k) (first %))
                           (last %))
                      (and (= :duo:kenmerken (first %))
                           (= (name k) (get-in % [1 1]))
                           (get-in % [2 1]))))
              rio-obj)]
    (or value
        (when (and (= k :eigenAangebodenOpleidingSleutel)
                   (aangeboden-opleiding? rio-obj))
          "")
        (when (and (= k :eigenOpleidingseenheidSleutel)
                   (opleidingseenheid? rio-obj))
          ""))))

(declare link-item-adapter)

(defn- child-adapter [rio-obj k]
  (->> rio-obj
       (filter #(and (sequential? %)
                     (= (duo-keyword k) (first %))
                     %))
       (map #(partial link-item-adapter %))))

;; Turns <prijs><soort>s</soort><bedrag>123</bedrag></prijs> into {:soort "s", bedrag 123}
(defn- nested-adapter [rio-obj k]
  (keep #(when (and (sequential? %)
                    (= (duo-keyword k) (first %)))
           (zipmap (map (comp keyword strip-duo first) (rest %))
                   (map last (rest %))))
        rio-obj))

;; These attributes have nested elements, e.g.:
;; <prijs>
;;   <bedrag>99.50</bedrag>
;;   <soort>collegegeld</soort>
;; </prijs
(def ^:private attributes-with-children #{:vastInstroommoment :prijs :flexibeleInstroom})

(defn- link-item-adapter [rio-obj k]
  (if (string? k)
    (child-adapter rio-obj k)         ; If k is a string, it refers to a nested type: Periode or Cohort.
    (if (attributes-with-children k)  ; These attributes are the only ones with child elements.
      (vec (nested-adapter rio-obj k))
      ; The common case is handling attributes.
      (attribute-adapter rio-obj k))))

(defn- linker [rio-obj]
  (rio-helper/->xml (partial link-item-adapter rio-obj)
                    (-> rio-obj first strip-duo)))

(defn rio-obj-raadplegen->beheren [rio-obj finder]
  (let [rio-obj (xmlclj->duo-hiccup rio-obj)
        ;; There is a mismatch between raadplegen and beheren for aangeboden-opleidingen.
        ;; raadplegen returns opleidingseenheidcode, but beheren requires opleidingseenheidSleutel.
        rio-obj (map #(if (and (sequential? %)
                               (aangeboden-opleiding? rio-obj)
                               (= :duo:opleidingseenheidcode (first %)))
                        (assoc % 0 :duo:opleidingseenheidSleutel)
                        %)
                     rio-obj)
        rio-new  (->> rio-obj
                      ;; verwijder opleidingserkenningSleutel, aangezien er niet een opleidingseenheidcode EN een
                      ;; opleidingserkenningSleutel aangeboden mogen worden.
                      (filter (fn [n] (not (and (vector? n)
                                                (#{:duo:opleidingserkenningSleutel} (first n))))))
                      (linker))]
    [rio-new (some finder rio-obj)]))

(defn- execute-link [{::ooapi/keys [id type] :keys [institution-oin] :as _request} rio-loader-fn rio-config]
  {:pre [(#{"education-specification" "course" "program"} type)]}
  (let [eduspec? (= type "education-specification")
        sleutelnaam-kw (if eduspec? :eigenOpleidingseenheidSleutel :eigenAangebodenOpleidingSleutel)
        finder (sleutel-finder (name sleutelnaam-kw))
        [rio-new old-id] (rio-obj-raadplegen->beheren (rio-loader-fn) finder)
        mutation {:action     (if eduspec? "aanleveren_opleidingseenheid" "aanleveren_aangebodenOpleiding")
                  :rio-sexp   [(vec (keep (sleutel-changer id finder) rio-new))]
                  :sender-oin institution-oin}
        success? (mutator/mutate! mutation rio-config)
        predicate (fn [] (let [rio-obj (rio-loader-fn)]
                           (= id (last (rio-obj-raadplegen->beheren rio-obj finder)))))]

    ;; Ensure RIO has processed the update
    (when success?
      (rio-helper/blocking-retry predicate
                                 rio-config
                                 "Ensure link is processed by rio"))

    {:rio-sexp (:rio-sexp mutation)
     :success  success?
     :link     {sleutelnaam-kw (cond-> {:diff (not= old-id id)}
                                 (not= old-id id)
                                 (assoc :old-id old-id
                                        :new-id id))}}))

(defn- load-rio-obj-for-link [getter request]
  (let [rio-obj (rio.loader/rio-finder getter request)]
    (or rio-obj
        (throw (ex-info "404 Not Found" {:phase :resolving})))))

(defn make-linker [rio-config getter]
  {:pre [rio-config]}
  (fn [request]
    {:pre [(:institution-oin request)]}
    (execute-link request #(load-rio-obj-for-link getter request) rio-config)))
