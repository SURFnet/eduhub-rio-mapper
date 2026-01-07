(ns nl.surf.eduhub-rio-mapper.vcr-helper
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote-entities]
   [nl.surf.eduhub-rio-mapper.rio.helper :as rio.helper]
   [nl.surf.eduhub-rio-mapper.utils.version :as version])
  (:import
   [java.io PushbackReader]))

(def vcr-mode (if (= "true" (System/getenv "VCR_RECORD"))
                :record
                :playback))

(def vcr-mapping
  (when (= vcr-mode :playback)
    (let [fixtures-dir (str "test-" version/OOAPI-VERSION "/fixtures")]
      (rio.helper/edn-read-file (str fixtures-dir "/vcr/mapping.edn")))))

(defn- entity-id
  "Get UUID of entity from remote-entities session."
  [name]
  (get remote-entities/*session* name))

(defn entity-name-to-id [n]
  (let [id (if (= vcr-mode :record)
             (str (entity-id n))
             (get vcr-mapping n))]
    (when (nil? id)
      (throw (ex-info "missing entity name" {:name n})))
    id))


(defn- ls [dir-name]
  (map #(.getName %) (.listFiles (io/file dir-name))))

(defn only-one-if-any [list]
  (assert (< (count list) 2) (prn-str list))
  (first list))

(defn- numbered-file [basedir nr]
  {:post [(some? %)]}
  (let [filename (->> basedir
                      (ls)
                      (filter #(.startsWith % (str nr "-")))
                      (only-one-if-any))]
    (when-not filename (throw (ex-info (format "No recorded request found for dir %s nr %d" basedir nr) {})))
    (str basedir "/" filename)))

(defn req-name [request]
  (let [action (get-in request [:headers "SOAPAction"])]
    (if action
      (last (str/split action #"/"))
      (-> request :url
          (subs (count "https://gateway.test.surfeduhub.nl/"))
          (str/replace \/ \-)
          (str/split #"\?")
          first))))

(defn- make-playbacker [root idx _]
  (let [count-atom (atom 0)
        dir        (numbered-file root idx)]
    (fn [_ actual-request]
      (let [i                (swap! count-atom inc)
            fname            (numbered-file dir i)
            recording        (with-open [r (io/reader fname)] (edn/read (PushbackReader. r)))
            recorded-request (:request recording)]
        (doseq [property-path [[:url] [:method] [:headers "SOAPAction"]]]
          (let [expected (get-in recorded-request property-path)
                actual   (get-in actual-request property-path)]
            (is (= expected actual)
                (str "Unexpected property " (last property-path)))))
        (:response recording)))))

(defn- make-recorder [root idx desc]
  (let [mycounter (atom 0)]
    (fn [handler request]
      (let [response  (handler request)
            counter   (swap! mycounter inc)
            file-name (str root "/" idx "-" desc "/" counter "-" (req-name request) ".edn")
            headers   (select-keys (:headers request) ["SOAPAction" "X-Route"])]
        (io/make-parents file-name)
        (with-open [w (io/writer file-name)]
          (pprint {:request  (assoc (select-keys request [:method :url :body])
                                    :headers headers)
                   :response (select-keys response [:status :body])}
                  w))
        response))))

(defn make-vcr []
  (case vcr-mode
    :playback make-playbacker
    :record   make-recorder))
