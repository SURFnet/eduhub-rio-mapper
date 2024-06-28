(ns nl.surf.eduhub-rio-mapper.utils.ooapi
  (:require [clojure.string :as string]
            [nl.surf.eduhub-rio-mapper.rio.helper :as rio-helper])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter DateTimeParseException]
           [java.util UUID]))

(def date-format (DateTimeFormatter/ofPattern "uuuu-MM-dd"))

(defn valid-uuid? [uuid]
  (try (UUID/fromString uuid)
       true
       (catch IllegalArgumentException _ false)))

(defn truncate [s n]
  {:pre [(and (integer? n) (pos? n))]}
  (if (string? s)
    (subs s 0 (min (count s) n))
    s))

(defn level-sector-map-to-rio?
  "True if we can map the given level and sector to RIO."
  [{:keys [level sector educationSpecificationType]}]
  (or (= "privateProgram" educationSpecificationType)
      (some? (rio-helper/level-sector-mapping level sector))))

(defn get-localized-value-exclusive
  "Get localized value from LanguageTypedString.

  The provided locales are tried in order. There is no fallback"
  ([attr locales]
  (->> locales
       (keep (fn [locale]
               (some #(when (string/starts-with? (% :language) locale)
                        (% :value))
                     attr)))
       first))
  ([attr locales key value]
   {:pre [(= key :maxlen)]}
     (-> (get-localized-value-exclusive attr locales)
         (truncate value))))

(defn get-localized-value
  "Get localized value from LanguageTypedString.

  The provided locales are tried in order. If none found, fall back to
  English (international).  If still none found take the first."
  ([attr locales] (or
    (get-localized-value-exclusive attr (concat locales ["en"]))
    (-> attr first :value)))
  ([attr locales key value]
   {:pre [(= key :maxlen)]}
   (-> (get-localized-value attr locales)
       (truncate value))))

(defn ooapi-to-periods [{:keys [timelineOverrides] :as ooapi} entity-key]
  (as-> timelineOverrides $
        (map
          #(assoc (entity-key %)
             :validFrom (:validFrom %)
             :validTo (:validTo %))
          $)
        (conj $ ooapi)))

(defn current-period [periods attr-key]
  (let [current-date (.format date-format (LocalDate/now))]
    (->> periods
         (filter #(neg? (compare (attr-key %) current-date)))
         (sort-by attr-key)
         last)))

(defn extract-rio-consumer
  "Find the first consumer with a consumerKey equal to 'rio' or return nil."
  [consumers]
  (some->> consumers
           (filter #(= (:consumerKey %) "rio"))
           first))

(defn valid-date? [date]
  (and (string? date)
       (try (let [d (LocalDate/parse date date-format)]
              ;; XSD schema does not accept "Year zero".
              (not (zero? (.getYear d))))
            (catch DateTimeParseException _ false))))

(defn has-mode-of-delivery? [x]
  (or (:modeOfDelivery x)
      (some #(and (:modeOfDelivery %)
                  (= "rio" (:consumerKey %)))
            (:consumers x))))

(defn has-registration-status? [x]
  (some #(and (#{"open" "closed"} (:registrationStatus %))
              (= "rio" (:consumerKey %)))
        (:consumers x)))

(defn valid-type-and-subtype?
  "EducationSpecification should only have subType if type is 'program'."
  [{:keys [educationSpecificationType consumers]}]
  (let [{:keys [educationSpecificationSubType] :as rio-consumer} (extract-rio-consumer consumers)]
    (or (and (= educationSpecificationType "program")
             (= educationSpecificationSubType "variant"))
        (not (contains? rio-consumer :educationSpecificationSubType)))))

(defn not-equal-to-parent?
  [{:keys [educationSpecificationId parent]}]
  (not= educationSpecificationId parent))
