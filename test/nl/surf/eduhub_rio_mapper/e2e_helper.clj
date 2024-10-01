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

(ns nl.surf.eduhub-rio-mapper.e2e-helper
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :as test]
            [clojure.xml :as xml]
            [environ.core :refer [env]]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.config :as config]
            [nl.surf.eduhub-rio-mapper.endpoints.status :as status]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote-entities]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio-loader]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils])
  (:import [java.io ByteArrayInputStream StringWriter]
           [java.net ConnectException]
           [java.util Base64]
           [javax.xml.xpath XPathFactory]
           [org.w3c.dom Node]))

(def ^:private last-seen-testing-contexts (atom nil))

(defn- print-testing-contexts
  "Print eye catching testing context (when it's not already printed)."
  []
  (when (seq test/*testing-contexts*)
    (when-not (= @last-seen-testing-contexts test/*testing-contexts*)
      (reset! last-seen-testing-contexts test/*testing-contexts*)
      (println)
      (println "╔═══════════════════")
      (println (str/replace (first test/*testing-contexts*) #"(?m)^\s*" "║ ")))))

(def ^:private last-boxed-print (atom nil))

(defmacro print-boxed
  "Print pretty box around output of evaluating `form`."
  [title & form]
  `(let [sw# (StringWriter.)
         r#  (binding [*out* sw#] ~@form)
         s#  (str sw#)]
     (if (= @last-boxed-print s#)
       (do
         (print ".")
         (flush))
       (do
         (print-testing-contexts)
         (println)
         (print "╭─────" ~title "\n│ ")
         (println (str/replace (str/trim s#) #"\n" "\n│ "))
         (println "╰─────")
         (reset! last-boxed-print s#)))
     r#))

(defn- print-soap-body
  "Print the body of a SOAP request or response."
  [s]
  ;; Use clojure.xml/parse because it is more lenient than
  ;; clojure.data.xml/parse which trips over missing namespaces.
  (let [xml (xml/parse (ByteArrayInputStream. (.getBytes s)))]
    (xml-utils/debug-print-xml (-> xml :content second :content first)
                               :initial-indent "  ")))

(defn- print-json
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

(defn- print-api-message
  "Print boxed API request and response."
  [{{:keys [method url]}  :req
    {:keys [status body]} :res}]
  (print-boxed "API"
    (println (str/upper-case (name method)) url status)
    (when body
      (print-json body))))

(defn- print-rio-message
  "Print boxed RIO request and response."
  [{{:keys                [method url]
     req-body             :body
     {action :SOAPAction} :headers} :req
    {res-body :body
     :keys    [status]}             :res}]
  (print-boxed "RIO"
    (println (str/upper-case (name method)) url status)
    (println "- action:" action)
    (println "- request:\n")
    (print-soap-body req-body)
    (println)
    (when (= http-status/ok status)
      (println "- response:\n")
      (print-soap-body res-body)
      (println))))

(defn- print-ooapi-message
  "Print boxed OOAPI request and response."
  [{{:keys [method url]}  :req
    {:keys [status body]} :res}]
  (print-boxed "OOAPI"
    (println (str/upper-case method) url status)
    (println)
    (when (= http-status/ok status)
      (print-json-str body))))

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

(defn- print-http-messages
  "Print HTTP message as returned by API status."
  [http-messages]
  (when-let [msg (first http-messages)]
    ;; need to keywordize-keys because http-message may be translated
    ;; from from JSON (in which case they are all keywords) or come
    ;; strait from http-utils (which is a mixed bag)
    (let [{:keys [req] :as msg} (keywordize-keys msg)]
      (if (-> req :headers :SOAPAction)
        (print-rio-message msg)
        (print-ooapi-message msg)))
    (recur (next http-messages))))



;; Defer running make-config so running some (other!) tests is still
;; possible when environment incomplete.
(def config (delay (config/make-config
                    (assoc env
                           "WORKER_API_PORT" "8081"
                           "JOB_MAX_RETRIES" "1"
                           "JOB_RETRY_WAIT_MS" "1000"
                           "RIO_RETRY_ATTEMPTS_SECONDS" "5,5"))))

(def base-url
  (delay (str "http://"
              (-> @config :api-config :host)
              ":"
              (-> @config :api-config :port))))

(defn- encode-base64
  "Base64 bytes of given string."
  [s]
  (str/join (map char (.encode (Base64/getEncoder) ^bytes (.getBytes s)))))

(def ^:private bearer-token
  ;; A conext bearer token expires in 3600 seconds (1 hour), should be
  ;; plenty of time to run all e2e tests..
  (delay
    (let [{:keys [client-id
                  client-secret
                  token-endpoint]} env]
      (-> {:method       :post
           :url          token-endpoint
           :content-type :x-www-form-urlencoded
           :query-params {"grant_type" "client_credentials"
                          "audience"   client-id}

           :headers {"Authorization" (str "Basic "
                                          (encode-base64 (str client-id
                                                              ":"
                                                              client-secret)))}
           :as      :json}
          (http/request)
          (get-in [:body :access_token])))))

(defn- api-path
  "Returns path for given API action."
  [action args]
  (case action
    :status
    (let [[token] args]
      (str "/status/" token))

    :upsert
    (let [[type ooapi-id] args]
      (str "/job/upsert/" (name type) "/" ooapi-id))

    :dry-run/upsert
    (let [[type ooapi-id] args]
      (str "/job/dry-run/upsert/" (name type) "/" ooapi-id))

    :delete
    (let [[type ooapi-id] args]
      (str "/job/delete/" (name type) "/" ooapi-id))

    :link
    (let [[rio-id type ooapi-id] args]
      (str "/job/link/" rio-id "/" (name type) "/" ooapi-id))

    :unlink
    (let [[rio-id type] args]
      (str "/job/unlink/" rio-id "/" (name type)))))

(defn ooapi-id
  "Get OOAPI UUID of automatically uploaded fixture."
  [type id]
  (let [name (str (name type) "/" id)]
    (get remote-entities/*session* name)))

(defn- interpret-post-job-args
  "Automatically find OOAPI ID from session.

  When the last 2 arguments are a keyword and a string, the keyword is
  interpreted as an OOAPI type and the string as an ID known by
  `remote-entities/*session*`.  In that case the last argument is
  replaced by the UUID from the session using the `ooapi` function."
  [args]
  (let [[type id] (take-last 2 args)]
    (concat (drop-last args)
            [(if (and (keyword? type) (string? id))
               (let [uuid (ooapi-id type id)]
                 (assert uuid (str "Expect a UUID for " id))
                 uuid)
               id)])))

(defn- call-api
  "Make API call, print results and http-message, and return response."
  [method action args]
  (let [args          (if (= method :post)
                        (interpret-post-job-args args)
                        args)
        url           (str @base-url (api-path action args))
        req           {:method           method
                       :url              url
                       :headers          {"Authorization" (str "Bearer "
                                                               @bearer-token)}
                       :query-params     {:http-messages "true"}
                       :as               :json
                       :throw-exceptions false}
        res           (http/request req)
        http-messages (-> res :body :http-messages)
        res           (if (map? (:body res)) ;; expect JSON response
                        ;; but can be something
                        ;; else on error
                        (update res :body dissoc :http-messages)
                        res)]
    (print-api-message {:req req, :res res})
    (when (seq http-messages)
      (print-boxed "Job HTTP messages"
        (print-http-messages http-messages)))
    res))

(defn- api-status-final?
  "Determine if polling can be stopped from API status call response."
  [res]
  (status/final-status? (-> res :body :status keyword)))

(def job-status-poll-sleep-msecs 500)
(def job-status-poll-total-msecs 60000)

(defn post-job
  "Post a job through the API.
  Return the HTTP response of the call and includes a \"delay\" to access
  the job result at `:result-delay`."
  [action & args]
  (let [{:keys [status] {:keys [token]} :body :as res}
        (call-api :post action args)]
    (assoc res :result-delay
           (delay
             (if (= http-status/ok status)
               (loop [tries-left (/ job-status-poll-total-msecs
                                    job-status-poll-sleep-msecs)]
                 (let [{:keys [status body] :as res}
                       (call-api :get :status [token])]
                   (cond
                     (zero? tries-left)
                     (do
                       (println "\n\n⚠ too many retries on status\n")
                       ::time-out)

                     (not= http-status/ok status)
                     (do
                       (println "\n\n⚠ get status failed\n")
                       ::get-status-failed)

                     (api-status-final? res)
                     body

                     :else
                     (do
                       (Thread/sleep job-status-poll-sleep-msecs)
                       (recur (dec tries-left))))))
               (println "failed to post job"))))))

(defn job-result
  "Use `get-in` to access job response from `post-job`."
  [job & ks]
  (get-in @(:result-delay job) ks))

(defn job-result-status
  "Short cut to `post-job` job response status."
  [job]
  (job-result job :status))

(defn job-result-attributes
  "Short cut `get-in` to `post-job` job response attributes."
  [job & ks]
  (apply job-result job :attributes ks))

(defn job-result-opleidingseenheidcode
  "Short cut to `post-job` job response attributes opleidingseenheidcode."
  [job]
  (job-result-attributes job :opleidingseenheidcode))

(defmethod test/assert-expr 'job-result-opleidingseenheidcode [msg form]
  `(let [job# ~(second form)
         attrs# (job-result-attributes job#)]
     (test/do-report {:type (if (job-result-opleidingseenheidcode job#) :pass :fail)
                      :message (or ~msg "Expect job result attributes to include opleidingseenheidcode."),
                      :expected '~form, :actual attrs#})))

(defn job-result-aangebodenopleidingcode
  "Short cut to `post-job` job response attributes aangebodenopleidingcode."
  [job]
  (or
    (job-result-attributes job :aangebodenopleidingcode)
    (throw (ex-info "error job-result-aangebodenopleidingcode" job))))

(defmethod test/assert-expr 'job-result-aangebodenopleidingcode [msg form]
  `(let [job# ~(second form)
         attrs# (job-result-attributes job#)]
     (test/do-report {:type (if (job-result-aangebodenopleidingcode job#) :pass :fail)
                      :message (or ~msg "Expect job result attributes to include aangebodenopleidingcode."),
                      :expected '~form, :actual attrs#})))

(defn job-has-diffs?
  "Returns `true` if \"diff\" is detected in given attributes."
  [job]
  (->> job
       (job-result-attributes)
       (map #(:diff (val %)))
       (filter (partial = true))
       seq))

(defmethod test/assert-expr 'job-has-diffs? [msg form]
  `(let [job# ~(second form)
         attrs# (job-result-attributes job#)]
    (test/do-report {:type (if (job-has-diffs? job#) :pass :fail)
                     :message (or ~msg "Expect job result attributes to have diffs"),
                     :expected '~form, :actual attrs#})))

(defn job-without-diffs?
  "Complement of `job-has-diffs?`."
  [job]
  (not (job-has-diffs? job)))

(defmethod test/assert-expr 'job-without-diffs? [msg form]
  `(let [job# ~(second form)
         attrs# (job-result-attributes job#)]
    (test/do-report {:type (if (job-without-diffs? job#) :pass :fail)
                     :message (or ~msg "Expect job result attributes to not have diffs"),
                     :expected '~form, :actual attrs#})))

(defn job-done?
  "Final job status is 'done'."
  [job]
  (= "done" (job-result-status job)))

(defmethod test/assert-expr 'job-done? [msg form]
  `(let [job# ~(second form)
         status# (job-result-status job#)]
    (test/do-report {:type (if (= "done" status#) :pass :fail)
                     :message (or ~msg "Expect final job status to equal 'done'"),
                     :expected "done", :actual status#})))

(defn job-error?
  "Final job status is 'error'."
  [job]
  (= "error" (job-result-status job)))

(defmethod test/assert-expr 'job-error? [msg form]
  `(let [job# ~(second form)
         status# (job-result-status job#)]
    (test/do-report {:type (if (= "error" status#) :pass :fail)
                     :message (or ~msg "Expect final job status to equal 'error'"),
                     :expected "error", :actual status#})))

(defn job-dry-run-found?
  "Final job status attributes status is 'found'."
  [job]
  (= "found" (:status (job-result-attributes job))))

(defmethod test/assert-expr 'job-dry-run-found? [msg form]
  `(let [job# ~(second form)
         status# (:status (job-result-attributes job#))]
    (test/do-report {:type (if (= "found" status#) :pass :fail)
                     :message (or ~msg "Expect final job status attributes status to equal 'found'"),
                     :expected "found", :actual status#})))

(defn job-dry-run-not-found?
  "Final job status attributes status is 'not-found'."
  [job]
  (= "not-found" (:status (job-result-attributes job))))

(defmethod test/assert-expr 'job-dry-run-not-found? [msg form]
  `(let [job# ~(second form)
         status# (:status (job-result-attributes job#))]
    (test/do-report {:type (if (= "not-found" status#) :pass :fail)
                     :message (or ~msg "Expect final job status attributes status to equal 'not-found'"),
                     :expected "not-found", :actual status#})))



(def ^:private rio-getter (delay (rio-loader/make-getter (:rio-config @config))))
(def ^:private client-info (delay (clients-info/client-info (:clients @config)
                                                            (:client-id env))))

(defn- rio-get [req]
  (let [messages-atom (atom [])
        result (binding [http-utils/*http-messages* messages-atom]
                 (@rio-getter req))]
    (print-http-messages @messages-atom)
    result))

(defn rio-relations
  "Call RIO `opvragen_opleidingsrelatiesBijOpleidingseenheid`."
  [code]
  (print-boxed "rio-relations"
    (rio-get {::rio/type           rio-loader/opleidingsrelaties-bij-opleidingseenheid-type
              ::rio/opleidingscode code
              :institution-oin            (:institution-oin @client-info)})))

(defn rio-with-relation?
  "Fetch relations of `rio-child` and test if it includes `rio-parent`.

  Note: RIO may take some time to register relations so we retry for
  10 seconds."
  [rio-parent rio-child]
  (loop [tries 20]
    (let [relations (rio-relations rio-child)
          result    (some #(contains? (:opleidingseenheidcodes %) rio-parent)
                          relations)]
      (if result
        result
        (if (pos? tries)
          (do
            (Thread/sleep 500)
            (recur (dec tries)))
          result)))))

(defn rio-opleidingseenheid
  "Call RIO `opvragen_opleidingseenheid`."
  [code]
  {:pre [code]}
  (print-boxed "rio-opleidingseenheid"
    (-> {::rio/type           rio-loader/opleidingseenheid-type
         ::rio/opleidingscode code
         :institution-oin            (:institution-oin @client-info)
         :response-type              :literal}
        rio-get
        xml-utils/str->dom)))

(defn rio-aangebodenopleiding
  "Call RIO `opvragen_aangebodenOpleiding`."
  [id]
  (print-boxed "rio-aangebodenopleiding"
    (-> {::rio/type                      rio-loader/aangeboden-opleiding-type
         ::rio/aangeboden-opleiding-code id
         :institution-oin                (:institution-oin @client-info)
         :response-type                  :literal}
        rio-get
        xml-utils/str->dom)))

(defn get-in-xml
  "Get text node from `path` starting at `node`."
  [node path]
  {:pre [(instance? Node node)]}
  (let [xpath (str "//"
                   (->> path
                        (map #(str "*[local-name()='" % "']"))
                        (str/join "/")))]
    (.evaluate (.newXPath (XPathFactory/newInstance))
               xpath
               node)))



;; Using atoms to keep process to make interactive development easier.
(defonce ^:private serve-api-process-atom (atom nil))
(defonce ^:private worker-process-atom (atom nil))

(defn start-services
  "Start the serve-api and worker services."
  []
  (let [config (config/make-config env)]

    (when (= (:api-config config) (:worker-api-config config))
      (println "The api and the worker must run on separate ports.")
      (System/exit 1)))
  (doseq [cmd ["serve-api" "worker"]]
    (let [runtime (Runtime/getRuntime)
          cmds    ^"[Ljava.lang.String;" (into-array ["lein" "trampoline" "mapper" cmd])]
      (reset! serve-api-process-atom (.exec runtime cmds)))))

(defn stop-services
  "Stop the serve-api and worker services (if the are started)."
  []
  (when-let [proc @serve-api-process-atom]
    (.destroy proc)
    (reset! serve-api-process-atom nil))
  (when-let [proc @worker-process-atom]
    (.destroy proc)
    (reset! worker-process-atom nil)))

(def wait-for-serve-api-sleep-msec 500)
(def wait-for-serve-api-total-msec 20000)

(defn with-running-mapper
  "Wrapper to use with `use-fixtures` to automatically start mapper services."
  [f]
  (when-not (:mapper-url env) ;; TODO
    (start-services)
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop-services))

    ;; wait for serve-api to be up and running
    (loop [tries-left (/ wait-for-serve-api-total-msec
                         wait-for-serve-api-sleep-msec)]
      (when (neg? tries-left)
        (throw (ex-info "Failed to start serve-api"
                        {:msecs wait-for-serve-api-total-msec})))
      (let [result
            (try
              (http/get (str @base-url "/metrics")
                        {:throw-exceptions false})
              true
              (catch ConnectException _
                false))]
        (when-not result
          (Thread/sleep wait-for-serve-api-sleep-msec)
          (recur (dec tries-left))))))

  ;; run tests
  (f))
