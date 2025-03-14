(ns nl.surf.eduhub-rio-mapper.specs.helper
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.specs.course :as crs]
            [nl.surf.eduhub-rio-mapper.specs.education-specification :as es]
            [nl.surf.eduhub-rio-mapper.specs.program :as prg]
            [nl.surf.eduhub-rio-mapper.utils.ooapi :as ooapi-utils]))

(defn- check-eduspec-required-attributes [eduspec]
  (when
    (nil? (:name eduspec))
    "The `educationSpecification` attribute within a `timelineOverrides` item should have an attribute `name`."))

(defn- check-program-required-attributes [eduspec]
  (when
    (nil? (:name eduspec))
    "The `program` attribute within a `timelineOverrides` item should have an attribute `name`."))

(defn- check-course-required-attributes [course]
  (when
    (nil? (:name course))
    "The `course` attribute within a `timelineOverrides` item should have an attribute `name`."))

;; attributes

(defn- check-generic-attribute [entity attr attributes entity-name]
  (when-not (= :consumers attr)
    (when-let [spec (some #(when (= (name attr) (name %))
                             %)
                          attributes)]
      (when-not (s/valid? spec (attr entity))
        (str "The `" (name attr) "` attribute of the " entity-name " does not conform to the required format.")))))

(defn- check-eduspec-attribute [entity attr]
  (check-generic-attribute entity attr (into es/education-specification-req-attrs es/education-specification-opt-attrs) "education specification"))

(defn- check-program-attribute [entity attr]
  (check-generic-attribute entity attr (into prg/program-req-attrs prg/program-opt-attrs) "program"))

(defn- check-course-attribute [entity attr]
  (check-generic-attribute entity attr (into crs/course-req-attrs crs/course-opt-attrs) "course"))

;; timelineOverrides

(defn- check-generic-timeline-override [to entity-name]
  (cond
    (nil? to)
    "The `timelineOverrides` attribute should be an array, but it was null."

    (not (sequential? to))
    "The `timelineOverrides` attribute should be an array."

    (not (every? #(contains? % entity-name) to))
    (str "Each item in the `timelineOverrides` attribute should contain an object with an `" (name entity-name) "` attribute.")

    (not (every? :validFrom to))
    "Each item in the `timelineOverrides` attribute should contain an object with a `validFrom` attribute."

    (some #(nil? (entity-name %)) to)
    (str "The `" (name entity-name) "` attribute within a `timelineOverrides` item should be an object, but it was null.")

    (not (every? #(map? (entity-name %)) to))
    (str "The `" (name entity-name) "` attribute within a `timelineOverrides` item should be an object.")))

(defn- check-course-timeline-overrides [to]
  (or
    (check-generic-timeline-override to :course)
    (some #(check-course-required-attributes (:course %)) to)))

(defn- check-eduspec-timeline-overrides [to]
  (or
    (check-generic-timeline-override to :educationSpecification)
    (some #(check-eduspec-required-attributes (:educationSpecification %)) to)))

(defn- check-program-timeline-overrides [to]
  (or
    (check-generic-timeline-override to :program)
    (some #(check-program-required-attributes (:program %)) to)))

;; consumers

(defn- check-generic-consumers [consumers]
  (cond
    (not (sequential? consumers))
    "The `consumers` attribute should be an array."

    (empty? consumers)
    "The `consumers` attribute should be an array with at least one item."

    (not (every? #(contains? % :consumerKey) consumers))
    "Each item in the `consumers` attribute should contain an object with an `consumerKey` attribute."

    (not= 1 (count (filter #(= "rio" (:consumerKey %)) consumers)))
    "Top level `consumers` attribute, if present, must contain exactly one item with `consumerKey` \"rio\"."))

(defn- check-program-rio-consumer [rio-consumer]
  (or
    (when (contains? rio-consumer :jointProgram)
      (cond
        (not (boolean? (:jointProgram rio-consumer)))
        "The `jointProgram` attribute in the rio consumer must be a boolean"

        (not (contains? rio-consumer :educationUnitCode))
        "If the `jointProgram` attribute is true, `educationUnitCode` is required."

        (not (string? (:educationUnitCode rio-consumer)))
        "The `educationUnitCode` attribute must be a string."

        (not (s/valid? ::prg/educationUnitCode (:educationUnitCode rio-consumer)))
        "The format of the value of the `educationUnitCode` attribute is invalid."))

    (let [attributes (into prg/program-consumer-req-attrs prg/program-consumer-opt-attrs)]
      (some #(check-generic-attribute rio-consumer % attributes "program's rio consumer") (keys rio-consumer)))))

(defn check-eduspec-rio-consumer [rio-consumer]
  (let [attributes (into es/eduspec-consumer-req-attrs es/eduspec-consumer-opt-attrs)]
    (some #(check-generic-attribute rio-consumer % attributes "education specification's rio consumer") (keys rio-consumer))))

(defn- check-eduspec-consumers [consumers]
  (or
    (check-generic-consumers consumers)
    (check-eduspec-rio-consumer (ooapi-utils/extract-rio-consumer consumers))))

(defn check-course-rio-consumer [rio-consumer]
  (let [attributes (into crs/course-consumer-req-attrs crs/course-consumer-opt-attrs)]
    (some #(check-generic-attribute rio-consumer % attributes "course's rio consumer") (keys rio-consumer))))

(defn- check-course-consumers [consumers]
  (or
    (check-generic-consumers consumers)
    (check-course-rio-consumer (ooapi-utils/extract-rio-consumer consumers))))

(defn- check-program-consumers [consumers]
  (or
    (check-generic-consumers consumers)
    (check-program-rio-consumer (ooapi-utils/extract-rio-consumer consumers))))

;; top level checks

(defn- check-top-level-eduspec [entity]
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

      (some #(check-eduspec-attribute entity %) (keys entity))

      (when (contains? entity :timelineOverrides)
        (check-eduspec-timeline-overrides (:timelineOverrides entity)))

      (when (contains? entity :consumers)
        (check-eduspec-consumers (:consumers entity))))))


(defn- check-top-level-program [entity]
  (let [missing (filter #(not (contains? entity %)) [:programId :consumers :name :validFrom])]
    (or
      (when-not (empty? missing)
        (str "Top level Program object is missing these required fields: " (str/join ", " (map name missing))))

      (some #(check-program-attribute entity %) (keys entity))

      (when (contains? entity :timelineOverrides)
        (check-program-timeline-overrides (:timelineOverrides entity)))

      (when (contains? entity :consumers)
        (check-program-consumers (:consumers entity))))))

(defn- check-top-level-course [entity]
  (let [missing (filter #(not (contains? entity %)) [:consumers :courseId :duration :educationSpecification :name :validFrom])]
    (or
      (when-not (empty? missing)
        (str "Top level EducationSpecification object is missing these required fields: " (str/join ", " (map name missing))))

      (some #(check-course-attribute entity %) (keys entity))

      (when (contains? entity :timelineOverrides)
        (check-course-timeline-overrides (:timelineOverrides entity)))

      (when (contains? entity :consumers)
        (check-course-consumers (:consumers entity))))))

(defn check-spec [entity spec-name human-name]
  (cond
    (nil? entity)
    (str "Top level object is `null`. Expected an " human-name " object.")

    (not (map? entity))
    (str "Top level object is not a JSON object. Expected an " human-name " object.")

    (= spec-name ::es/EducationSpecificationTopLevel)
    (check-top-level-eduspec entity)

    (= spec-name ::prg/program)
    (check-top-level-program entity)

    (= spec-name ::crs/course)
    (check-top-level-course entity)))
