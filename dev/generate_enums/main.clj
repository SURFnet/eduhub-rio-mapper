(ns generate-enums.main
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as string]
            [clojure.java.io :as io]))

(defn generate-enum
  "Generates a single enum definition as a string."
  [dir-path file-name yaml-path enum-name docstring]
  (let [values (-> (io/file dir-path file-name)
                   (.getPath)
                   (slurp)
                   (yaml/parse-string)
                   (get-in yaml-path))]
    (str "(def "
         enum-name
         "\n  \""
         docstring
         "\"\n  #{"
         (string/join " " (map #(str "\"" % "\"") values))
         "})\n\n")))

(defn -main
  "This turns the enumerables in the yaml files on open-education-api/specification into clojure sets"
  [specification-dir]
  (let [enum-path (io/file specification-dir "v5" "enumerations")
        schema-path (io/file specification-dir "v5" "schemas")
        consumers-path (io/file specification-dir "v5" "consumers/RIO/V1")
        enum-clojure-file (str
                            ";; DO NOT EDIT - CHANGES WILL BE OVERWRITTEN\n"
                            ";; This file is generated automatically via make generate-enums $PATH\n\n"
                            "(ns nl.surf.eduhub-rio-mapper.ooapi.enums)\n\n"
                            (generate-enum enum-path "codeType.yaml" [:x-ooapi-extensible-enum] "codeTypes"
                                           "Enum used in EducationSpecification for primaryCode and otherCodes.")
                            (generate-enum enum-path "modesOfDelivery.yaml" [:items :enum] "modesOfDelivery"
                                           "Enum used in Offerings for modeOfDelivery.")
                            (generate-enum enum-path "modeOfStudy.yaml" [:enum] "modeOfStudy"
                                           "Enum used in Programs for modeOfStudy.")
                            (generate-enum enum-path "programType.yaml" [:enum] "programType"
                                           "Enum used in Programs for programType.")
                            (generate-enum consumers-path "Program.yaml" [:properties :acceleratedRoute :enum] "acceleratedRoute"
                                           "Enum used in Programs for acceleratedRoute.")
                            (generate-enum consumers-path "Program.yaml" [:properties :consentParticipationSTAP :enum] "consentParticipationSTAP"
                                           "Enum used in Programs for consentParticipationSTAP.")
                            (generate-enum consumers-path "Program.yaml" [:properties :deficiency :enum] "deficiency"
                                           "Enum used in Programs for deficiency.")
                            (generate-enum consumers-path "Program.yaml" [:properties :propaedeuticPhase :enum] "propaedeuticPhase"
                                           "Enum used in Programs for propaedeuticPhase.")
                            (generate-enum consumers-path "Program.yaml" [:properties :requirementsActivities :enum] "requirementsActivities"
                                           "Enum used in Programs for requirementsActivities.")
                            (generate-enum consumers-path "Program.yaml" [:properties :studyChoiceCheck :enum] "studyChoiceCheck"
                                           "Enum used in Programs for studyChoiceCheck.")
                            (generate-enum enum-path "educationSpecificationType.yaml" [:enum] "educationSpecificationTypes"
                                           "Enum used in EducationSpecification for educationSpecificationType.")
                            (generate-enum enum-path "formalDocument.yaml" [:enum] "formalDocumentTypes"
                                           "Enum used in EducationSpecification for formalDocument.")
                            (generate-enum enum-path "level.yaml" [:enum] "levels"
                                           "Enum used in EducationSpecification for level.")
                            (generate-enum enum-path "sector.yaml" [:enum] "sectors"
                                           "Enum used in EducationSpecification for sector.")
                            (generate-enum schema-path "StudyLoadDescriptor.yaml" [:properties :studyLoadUnit :enum] "studyLoadUnits"
                                           "Enum used in EducationSpecification for studyLoad."))]
    (spit "src/nl/surf/eduhub_rio_mapper/ooapi/enums.clj" enum-clojure-file)))
