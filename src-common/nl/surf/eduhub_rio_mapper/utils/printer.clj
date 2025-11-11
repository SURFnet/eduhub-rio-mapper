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

(ns nl.surf.eduhub-rio-mapper.utils.printer
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils])
  (:import [java.io ByteArrayInputStream StringWriter]))

(defmacro print-boxed
  "Print pretty box around output of evaluating `form`."
  [title & form]
  `(let [sw# (StringWriter.)
         r#  (binding [*out* sw#] ~@form)
         s#  (str sw#)]
     (println)
     (print "╭─────" ~title "\n│ ")
     (println (str/replace (str/trim s#) #"\n" "\n│ "))
     (println "╰─────")
     r#))

(defn- print-soap-body
  "Print the body of a SOAP request or response."
  [s]
  ;; Use clojure.xml/parse because it is more lenient than
  ;; clojure.data.xml/parse which trips over missing namespaces.
  (let [xml (xml/parse (ByteArrayInputStream. (.getBytes s)))]
    (xml-utils/debug-print-xml (-> xml :content second :content first)
                               :initial-indent "  ")))

(defn print-json
  "Print indented JSON."
  [v]
  (when v
    (print "  ")
    (println (json/write-str v :indent true :indent-depth 1))))

(defn- print-json-str
  "Parse string as JSON and print it."
  [s]
  (when s
    (print-json (json/read-str s))))

(defn- print-rio-message
  "Print boxed RIO request and response."
  [{{:keys                [method url]
     req-body             :body
     {action :SOAPAction} :headers} :req
    {res-body :body
     :keys    [status]}             :res}]
  (println (str/upper-case (name method)) url status)
  (println "- action:" action)
  (println "- request:\n")
  (print-soap-body req-body)
  (println)
  (when (= http-status/ok status)
    (println "- response:\n")
    (print-soap-body res-body)
    (println)))

(defn- print-ooapi-message
  "Print boxed OOAPI request and response."
  [{{:keys [method url]}  :req
    {:keys [status body]} :res}]
  (println (str/upper-case method) url status)
  (println)
  (when (= http-status/ok status)
    (print-json-str body)))

(defn- keywordize-keys
  "Recursively change map keys to keywords."
  [m]
  (->> m
       (map (fn [[k v]]
              [(if (keyword? k)
                 k
                 (keyword k))
               (if (map? v)
                 (keywordize-keys v)
                 v)]))
       (into {})))

(defn print-http-messages-with-boxed-printer
  "Print HTTP message as returned by API status."
  [http-messages print-boxed-fn]
  (when-let [msg (first http-messages)]
    ;; need to keywordize-keys because http-message may be translated
    ;; from from JSON (in which case they are all keywords) or come
    ;; strait from http-utils (which is a mixed bag)
    (let [{:keys [req] :as msg} (keywordize-keys msg)
          soap? (-> req :headers :SOAPAction)
          title (if soap? "RIO" "OOAPI")
          print-fn (if soap? print-rio-message
                             print-ooapi-message)]
      (print-boxed-fn title print-fn msg))
    (recur (next http-messages) print-boxed-fn)))

(defn print-single-http-message [title print-fn msg]
  (print-boxed title (print-fn msg)))

(defn print-http-messages
  "Print HTTP message as returned by API status."
  [http-messages]
  (print-http-messages-with-boxed-printer http-messages print-single-http-message))
