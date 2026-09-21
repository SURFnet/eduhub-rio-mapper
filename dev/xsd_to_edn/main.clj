(ns xsd-to-edn.main
  (:require [clojure.data.xml :as clj-xml]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

;; The only entries we want in the end results (that is, no abstract types or MBO related types).
(def interesting-types ["HoOnderwijseenheid" "ParticuliereOpleidingPeriode" "AangebodenParticuliereOpleidingPeriode"
                        "ParticuliereOpleiding" "AangebodenHOOpleidingCohort" "HoOnderwijseenheidPeriode"
                        "HoOnderwijseenhedencluster" "AangebodenHOOpleidingsonderdeelCohort"
                        "AangebodenHOOpleidingPeriode" "AangebodenParticuliereOpleiding" "AangebodenHOOpleidingsonderdeel"
                        "HoOnderwijseenhedenclusterPeriode" "AangebodenHOOpleidingsonderdeelPeriode" "HoOpleiding"
                        "AangebodenParticuliereOpleidingCohort" "HoOpleidingPeriode" "AangebodenHOOpleiding"])

;; Recursively parse xml as produced by clj-xml, and do some preprocessing
(defn parse [{:keys [tag attrs content]}]
  (case (name tag)
    "element"
    ; Other attributes are not needed. Just the attrs, there are never children.
    (if (= "Kenmerk" (:type attrs))
      ;; When we encounter Kenmerk, that's the position that all the individual kenmerken elements must appear in.
      {:kenmerklijst true}
      (if (and (= "0" (:maxOccurs attrs))
               (some? (:ref attrs)))
        nil                                                 ; These have refs like kenmerkwaardenbereik_*, not interesting
        (select-keys attrs [:name :type :ref])))

    "choice" {:choice (mapv parse content)}

    ;; Non-element tags, ignore string content
    (let [kids (vec (keep parse (filter #(not (string? %)) content)))]
      ;; Nil if both attributes and children are empty
      (when (not (and (empty? attrs) (empty? kids)))
        ; Tuple with [name-without-ns,attributes,element-children]
        [(name tag) attrs (not-empty kids)]))))

;; complexContent elements have a predictable format, simplify them
(defn complex-content [cc]
  (when (and (= (count cc) 1)
             (= "complexContent" (-> cc first first)))
    (-> cc first last first rest)))

;; Entities with a base (superclass) should get the properties of the base, unless the base hasn't been resolved yet,
;; in which case we'll retry until the base has been resolved.
;; Returns closure over entities
(defn resolve-base [entities]
  (fn [[k [a cs] :as all]]
    (if (nil? (:base a))
      all
      (let [[{:keys [base]} super-children] (entities (:base a))]
        (if (nil? base) [k [(dissoc a :base) (into super-children (vec cs))]] ;; Merge base with self. TODO is order correct?
                        all)))))

;; Like map {}.to_h in ruby. Easier to read than reduce/assoc
(defn map-hash [func coll] (into {} (map func coll)))

;; Resolve all bases, preserving base-before-extension order.
(defn map-and-resolve-base [coll]
  (loop [entities coll]
    (let [unresolved (filter (fn [[_ [attrs]]] (:base attrs)) entities)]
      (if (empty? unresolved)
        entities
        (do
          (doseq [[type-name [{:keys [base]}]] unresolved]
            (when-not (contains? entities base)
              (throw (ex-info "Unknown XSD base type" {:type type-name :base base}))))
          (let [resolved (map-hash (resolve-base entities) entities)]
            (when (= entities resolved)
              (throw (ex-info "Cyclic XSD inheritance" {:types (mapv first unresolved)})))
            (recur resolved)))))))

;; Puts list of properties with 'kenmerken' type into properties list at 'kenmerkenlijst' position.
(defn merge-kenmerken [props props-kenmerk]
  (reduce (fn [acc pr]
            (if (:kenmerklijst pr)
              (into acc (map #(assoc % :kenmerk true) props-kenmerk))
              (conj acc pr))) [] props))

;; If ref present, lookup type of ref, and add to attributes.
(defn resolve-refs [attrs name-to-type]
  (if (:ref attrs) (assoc attrs :type (name-to-type (:ref attrs)))
                   attrs))

(defn process-xsd []
  (let [d (clj-xml/parse-str (str/replace-first (slurp "resources/DUO_RIO_Beheren_OnderwijsOrganisatie_V4.xsd") #"^\uFEFF" ""))
        name-to-type (reduce
                       (fn [h {:keys [attrs]}] (assoc h (:name attrs) (:type attrs)))
                       {}
                       (filter #(and (not (string? %))
                                     (= "element" (name (:tag %))))
                               (:content d)))

        ;; Parse document, take children, select simpleType elements
        st (map rest (filter #(= "simpleType" (first %))
                             (last (parse d))))

        ;; Parse document, take children, select complexType elements
        ct (map rest (filter #(= "complexType" (first %))
                             (last (parse d))))

        ;; Index by name, remove requests and responses
        entities (into {}
                       (filter (fn [[k _v]]
                                 (not (or (str/ends-with? k "_request")
                                          (str/ends-with? k "_response"))))
                               (zipmap (map #(:name (first %)) ct)
                                       (map (fn [[a c]] [(dissoc a :name) c]) ct))))


        result (->> entities
                    ;; Handle elements of type complexContent
                    (map-hash (fn [[k [a children :as all]]] [k (if-let [[attr cc] (complex-content children)] [(merge a attr) cc] all)]))
                    ;; Unwrap sequence elements
                    (map-hash (fn [[k [a cs] :as all]]
                                (if (and (= 1 (count cs))
                                         (= "sequence" (first (first cs)))
                                         (empty? (first (rest (first cs)))))
                                  [k [a (last (first cs))]]
                                  all)))
                    ;; Resolve all inheritance before merging kenmerken.
                    (map-and-resolve-base)
                    ;; Remove empty children
                    (map-hash (fn [[k v]] [k (vec (filter seq (last v)))]))
                    ;; Add type to attributes for refs
                    (map-hash (fn [[k v]] [k (mapv #(resolve-refs % name-to-type) v)])))

        with-kenmerken (map-hash (fn [[k v]]
                                   [k (merge-kenmerken v (result (str "Kenmerkwaardenbereik_" k)))])
                                 result)]
    {:interesting-types interesting-types
     :with-kenmerken with-kenmerken
     :simple-types st}))

;; A constraint looks like:
;; ["maxLength" {:value "60"} nil]
(defn parse-constraint [[name {value :value}]]
  {(keyword name) (cond-> value (#{"maxLength" "minLength"} name) Integer/parseInt)})

;; A simple-type looks like:
;;({:name "AangebodenOpleidingExterneIdentificatie-v01"}
;;  [["restriction"
;;    {:base "IdentificatiecodeType"}
;;    [["maxLength" {:value "60"} nil]]]])
(defn- simple-type-reducer [h [tag [[_name attrs constraints]]]]
  (let [restrictions (reduce merge {} (map parse-constraint constraints))]
    (assoc h
      (-> tag :name)
      (merge (select-keys attrs [:base])
             (when-not (empty? restrictions) {:restrictions restrictions})))))

(defn -main [kind & _args]
  (let [{:keys [:interesting-types :with-kenmerken :simple-types]} (process-xsd)]
    (pprint/pprint
      (case kind
        "schema" (select-keys with-kenmerken interesting-types) ; see resources/beheren-schema.edn
        "types"  (reduce simple-type-reducer {} simple-types))))) ; see resources/beheren-types.edn
