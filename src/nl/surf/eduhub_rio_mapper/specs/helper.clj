(ns nl.surf.eduhub-rio-mapper.specs.helper
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.specs.education-specification :as es]
            [nl.surf.eduhub-rio-mapper.utils.ooapi :as ooapi-utils]))


(defn check-education-specification [eduspec]
  (cond
    (nil? eduspec)
    "The `educationSpecification` attribute within a `timelineOverrides` item should be an object, but it was null."

    (not (map? eduspec))
    "The `educationSpecification` attribute within a `timelineOverrides` item should be an object."

    (nil? (:name eduspec))
    "The `educationSpecification` attribute within a `timelineOverrides` item should have an attribute `name`."


    ))

(defn check-eduspec-timeline-overrides [to]
  (cond
    (nil? to)
    "The `timelineOverrides` attribute should be an array, but it was null."

    (not (sequential? to))
    "The `timelineOverrides` attribute should be an array."

    (not (every? #(contains? % :educationSpecification) to))
    (str "Each item in the `timelineOverrides` attribute should contain an object with an `educationSpecification` attribute.")

    (not (every? :validFrom to))
    "Each item in the `timelineOverrides` attribute should contain an object with a `validFrom` attribute."

    :else
    (some #(check-education-specification (:educationSpecification %)) to)))

(defn check-eduspec-consumers [consumers]
  (cond
    (not (sequential? consumers))
    "The `consumers` attribute should be an array."

    (empty? consumers)
    "The `consumers` attribute should be an array with at least one item."

    (not (every? #(contains? % :profile) consumers))
    "Each item in the `consumers` attribute should contain an object with an `profile` attribute."

    (not= 1 (count (filter #(= "rio" (:profile %)) consumers)))
    "Top level `consumers` attribute, if present, must contain exactly one item with `profile` \"rio\"."))

(defn check-top-level-education-specification [entity]
  (let [missing (filter #(not (contains? entity %)) [:educationSpecificationId :educationSpecificationType :primaryCode :validFrom :name :consumers])]
    (or
      (when-not (empty? missing)
        (str "Top level EducationSpecification object is missing these required fields: " (str/join ", " (map name missing))))

      (when-not (ooapi-utils/valid-type-and-subtype? entity)
        "Invalid combination of educationSpecificationType and educationSpecificationSubType fields")

      (when-not (ooapi-utils/not-equal-to-parent? entity)
        "Fields educationSpecificationId and parent are not allowed to be equal")

      (when-not (ooapi-utils/level-sector-map-to-rio? entity)
        "Invalid combination of level and sector fields")

      (when (contains? entity :timelineOverrides)
        (check-eduspec-timeline-overrides (:timelineOverrides entity)))

      (when (contains? entity :consumers)
        (check-eduspec-consumers (:consumers entity)))
      )))

(defn check-spec [entity spec-name human-name]
  (cond
    (nil? entity)
    (str "Top level object is `null`. Expected an " human-name " object.")

    (not (map? entity))
    (str "Top level object is not a JSON object. Expected an " human-name " object.")

    (= spec-name ::es/EducationSpecificationTopLevel)
    (check-top-level-education-specification entity)))
