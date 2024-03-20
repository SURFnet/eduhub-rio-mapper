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

(ns nl.surf.eduhub-rio-mapper.cli
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :as trace-context]
            [nl.surf.eduhub-rio-mapper.api :as api]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.keystore :as keystore]
            [nl.surf.eduhub-rio-mapper.logging :as logging]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.processing :as processing]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [nl.surf.eduhub-rio-mapper.worker :as worker]
            [nl.surf.eduhub-rio-mapper.worker-api :as worker-api])
  (:gen-class))

(defn parse-int-list [s & _opts] [(mapv #(Integer/parseInt %) (str/split s #","))])

(def opts-spec
  {:clients-info-path                  ["Clients info config file" :file
                                        :in [:clients-info-config :path]]
   :connection-timeout-millis          ["HTTP connection timeout in milliseconds" :int
                                        :default 10000
                                        :in [:rio-config :connection-timeout]]
   :gateway-user                       ["OOAPI Gateway Username" :str
                                        :in [:gateway-credentials :username]]
   :gateway-password                   ["OOAPI Gateway Password" :str
                                        :default nil
                                        :in [:gateway-credentials :password]]
   :gateway-password-file              ["OOAPI Gateway Password File" :str
                                        :default nil
                                        :in [:gateway-credentials :password-file]]
   :gateway-root-url                   ["OOAPI Gateway Root URL" :http]
   :keystore                           ["Path to keystore" :file]
   :keystore-password                  ["Keystore password" :str
                                        :default nil
                                        :in [:keystore-pass]] ; name compatibility with clj-http
   :keystore-password-file             ["Keystore password file" :str
                                        :default nil
                                        :in [:keystore-pass-file]]
   :keystore-alias                     ["Key alias in keystore" :str]
   :truststore                         ["Path to trust-store" :file
                                        :in [:trust-store]] ; name compatibility with clj-http
   :truststore-password                ["Trust-store password" :str
                                        :default nil
                                        :in [:trust-store-pass]] ; name compatibility with clj-http
   :truststore-password-file           ["Trust-store password file" :str
                                        :default nil
                                        :in [:trust-store-pass-file]]
   :rio-read-url                       ["RIO Services Read URL" :str
                                        :in [:rio-config :read-url]]
   :rio-update-url                     ["RIO Services Update URL" :str
                                        :in [:rio-config :update-url]]
   :rio-recipient-oin                  ["Recipient OIN for RIO SOAP calls" :str
                                        :in [:rio-config :recipient-oin]]
   :surf-conext-introspection-endpoint ["SurfCONEXT introspection endpoint" :http
                                        :in [:auth-config :introspection-endpoint]]
   :surf-conext-client-id              ["SurfCONEXT client id for Mapper service" :str
                                        :in [:auth-config :client-id]]
   :surf-conext-client-secret          ["SurfCONEXT client secret for Mapper service" :str
                                        :default nil
                                        :in [:auth-config :client-secret]]
   :surf-conext-client-secret-file     ["SurfCONEXT client secret for Mapper service file" :str
                                        :default nil
                                        :in [:auth-config :client-secret-file]]
   :api-port                           ["HTTP port for serving web API" :int
                                        :default 8080
                                        :in [:api-config :port]]
   :api-hostname                       ["Hostname for listing web API" :str
                                        :default "localhost"
                                        :in [:api-config :host]]
   :worker-api-port                    ["HTTP port for serving web API" :int
                                        :default 8080
                                        :in [:worker-api-config :port]]
   :worker-api-hostname                ["Hostname for listing web API" :str
                                        :default "localhost"
                                        :in [:worker-api-config :host]]
   :job-retry-wait-ms                  ["Number of ms to wait before retrying job" :int
                                        :default 5000
                                        :in [:worker :retry-wait-ms]]
   :job-max-retries                    ["Max number of retries of a job" :int
                                        :default 3
                                        :in [:worker :max-retries]]
   :redis-uri                          ["URI to redis" :str
                                        :default "redis://localhost"
                                        :in [:redis-conn :spec :uri]]
   :redis-uri-file                     ["URI to redis file" :str
                                        :default nil
                                        :in [:redis-conn :spec :uri-file]]
   :redis-key-prefix                   ["Prefix for redis keys" :str
                                        :default "eduhub-rio-mapper"
                                        :in [:redis-key-prefix]]
   :rio-retry-attempts-seconds         ["Number of seconds to wait for first, second, etc. retry of RIO command, comma separated" :int-list
                                        :default [5,30,120,600]
                                        :parser parse-int-list
                                        :in [:rio-config :rio-retry-attempts-seconds]]
   :status-ttl-sec                     ["Number of seconds hours to keep job status" :int
                                        :default (* 60 60 24 7) ;; one week
                                        :in [:status-ttl-sec]]})
(def commands
  #{"upsert" "delete" "delete-by-code" "get" "show" "resolve" "serve-api" "worker" "help" "dry-run-upsert" "link"})

;; TODO move to some general place
(def final-status? #{:done :error :time-out})

(def callback-retry-sleep-ms 30000)

(defmethod envopts/parse :file
  [s _]
  (let [f (io/file s)]
    (if (.isFile f)
      [f]
      [nil (str "not a file: `" s "`")])))

(def key-value-pairs-with-optional-secret-files
  {:gateway-password [:gateway-credentials :password]
   :keystore-password [:keystore-pass]
   :redis-uri [:redis-conn :spec :uri]
   :surf-conext-client-secret [:auth-config :client-secret]
   :truststore-password [:trust-store-pass]})

(def keys-with-optional-secret-files (vals key-value-pairs-with-optional-secret-files))

(defn dissoc-in
  "Return nested map with path removed"
  [m ks]
  (let [path (butlast ks)
        node (last ks)]
    (if (empty? path)
      (dissoc m node)
      (update-in m path dissoc node))))

(defn- load-secret-from-file [config k]                     ; k [:gateway-credentials :password]
  (let [file-key-node (keyword (str (name (last k)) "-file")) ; :password-file
        root-key-path (pop k)                               ; [:gateway-credentials]
        file-key-path (conj root-key-path file-key-node)    ; [:gateway-credentials :password-file]
        path (get-in config file-key-path)                  ; File path to secret
        old-value (get-in config k)
        config (dissoc-in config file-key-path)] ; Remove -file key from config
    (if (nil? path)
      config
      (if (.exists (io/file path))
        (let [new-value (str/trim (slurp path))]
          (when (and (some? old-value)
                     (or (not= old-value new-value)
                         (not= (char-array old-value) (char-array new-value))))
            (throw (ex-info (str "both classic and file value detected for " file-key-node " but value not equal\nold:"
                                 (prn-str old-value)
                                 "\nnew:"
                                 (prn-str new-value))
                            {:old old-value :new new-value})))
          (do
            (println (str "New secret read from file. Name: " k ", secret: " new-value ", length:" (count new-value)))
            (assoc-in config k new-value))) ; Overwrite config with secret from file
        (throw (ex-info (str "ENV var contains filename that does not exist: " path) {:filename path, :env-path k}))))))

(defn- validate-required-secrets [config]
  (let [missing-env (reduce
                      (fn [m [k v]] (if (get-in config v)
                                      m
                                      (assoc m k "missing")))
                      {}
                      key-value-pairs-with-optional-secret-files)]
    (when (not-empty missing-env)
        (.println *err* "Configuration error")
        (.println *err* (envopts/errs-description missing-env))
        (System/exit 1))
    config))

(defn make-config
  []
  {:post [(some? (-> % :rio-config :credentials :certificate))]}
  (let [[config errs] (envopts/opts env opts-spec)]

    (when errs
      (.println *err* "Configuration error")
      (.println *err* (envopts/errs-description errs))
      (System/exit 1))
    (let [{:keys [clients-info-config
                  keystore
                  keystore-pass
                  keystore-alias
                  trust-store
                  trust-store-pass] :as cfg}
          (reduce load-secret-from-file config keys-with-optional-secret-files)]
      (-> cfg
          (validate-required-secrets)
          (assoc-in [:rio-config :credentials]
                    (keystore/credentials keystore
                                          keystore-pass
                                          keystore-alias
                                          trust-store
                                          trust-store-pass))
          (assoc :clients (clients-info/read-clients-data clients-info-config))))))

(defn parse-getter-args [[type id & [pagina]]]
  {:pre [type id (string? type)]}
  (let [[type response-type] (reverse (str/split type #":" 2))
        response-type (and response-type (keyword response-type))]
    (assert (rio.loader/valid-get-types type))
    (-> (when pagina {:pagina pagina})
        (assoc (if (rio.loader/aangeboden-opleiding-types type)
                 ::ooapi/id
                 ::rio/opleidingscode) id
               :response-type response-type
               ::rio/type type))))

(defn- do-async-callback [config {:keys [token trace-context] :as job}]
  (let [status (status/get config token)
        req    {:url                    (::job/callback-url job)
                :method                 :post
                :content-type           :json
                :institution-schac-home (:institution-schac-home job)
                :institution-name       (:institution-name job)
                :body                   (json/json-str status)
                :connection-timeout     (-> config :rio-config :connection-timeout-millis)
                :throw-exceptions       false}]
    (async/thread
      (trace-context/with-context trace-context
        (logging/with-mdc (assoc trace-context
                            :token                  token
                            :url                    (::job/callback-url job)
                            :institution-schac-home (:institution-schac-home job)
                            :institution-name       (:institution-name job))
          (try
            (loop [retries-left 3]
              (let [status (-> req http-utils/send-http-request :status)]
                (when-not (http-status/success-status? status)
                  (log/debugf "Could not reach webhook %s, %d retries left" (:url req) retries-left)
                  (Thread/sleep callback-retry-sleep-ms)
                  (when (pos? retries-left)
                    (recur (dec retries-left))))))
            (catch Exception ex
              (logging/log-exception ex nil))))))))

(defn make-set-status-fn [config]
  (fn [{::job/keys [callback-url] :keys [token action] ::ooapi/keys [id type] :as job}
       status & [data]]
    (let [opleidingseenheidcode (-> data :aanleveren_opleidingseenheid_response :opleidingseenheidcode)
          aangeb-opleidingcode  (-> data ::rio/aangeboden-opleiding-code)
          value                 (cond-> {:status   status
                                         :token    token
                                         :action   action
                                         :resource (str type "/" id)}

                                        (and (= :done status)
                                             opleidingseenheidcode)
                                        ;; Data is result of run-job-fn, which is result of
                                        ;; job/run!, which is result of update-and-mutate
                                        ;; or delete-and-mutate which is the result of the
                                        ;; mutator/make-mutator, which is the result of
                                        ;; handle-rio-mutate-response, which is the parsed
                                        ;; xml response converted to edn.
                                        (assoc :attributes {:opleidingseenheidcode opleidingseenheidcode})

                                        (and (= (System/getenv "STORE_HTTP_REQUESTS") "true")
                                             (#{:done :error :time-out} status)
                                             (-> data :http-messages))
                                        (assoc :http-messages (-> data :http-messages))

                                        (and (= :done status)
                                             (:aanleveren_aangebodenOpleiding_response data))
                                        (assoc :attributes {:aangebodenopleidingcode aangeb-opleidingcode})

                                        (:dry-run data)
                                        (assoc :attributes (:dry-run data))

                                        (:link data)
                                        (assoc :attributes (:link data))

                                        (#{:error :time-out} status)
                                        (assoc :phase (-> data :errors :phase)
                                               :message (-> data :errors :message)))]

      (logging/with-mdc value
        (case status
          :done (log/infof "Finished job, token %s, type %s, id %s" token type id)
          :error (log/warnf "Failed job, token %s, type %s, id %s" token type id)
          :time-out (log/warnf "Timed out job, token %s, type %s, id %s" token type id)
          ;; Only log final statuses, not statuses such as "in-progress"
          nil))

      (status/set! config token value))

    (when (and callback-url (final-status? status))
      (logging/with-mdc
        {:token                  (:token job)
         :url                    callback-url
         :institution-name       (:institution-name job)
         :institution-schac-home (:institution-schac-home job)}
        (do-async-callback config job)))))

(defn errors?
  "Return true if `x` has errors."
  [x]
  (and (map? x)
       (contains? x :errors)))

(defn retryable?
  "Return true if `x` has errors and can be retried."
  [x]
  (and (errors? x)
       (some-> x :errors :retryable? boolean)))

(defn parse-client-info-args [args clients]
  (let [[client-id & rest-args] args
        client-info (clients-info/client-info clients client-id)]
    (when (nil? client-info)
      (.println *err* (str "No client info found for client id " client-id))
      (System/exit 1))
    [client-info rest-args]))

(defn- make-config-and-handlers []
  (let [{:keys [clients] :as cfg} (make-config)
        handlers (processing/make-handlers cfg)
        config (update cfg :worker merge
                       {:queues        (clients-info/institution-schac-homes clients)
                        :queue-fn      :institution-schac-home
                        :run-job-fn    #(job/run! handlers % (= (System/getenv "STORE_HTTP_REQUESTS") "true"))
                        :set-status-fn (make-set-status-fn cfg)
                        :retryable-fn  retryable?
                        :error-fn      errors?})]
    {:handlers handlers :config config}))

(defn process-command [command args {{:keys [getter resolver ooapi-loader dry-run! link!] :as handlers} :handlers {:keys [clients] :as config} :config}]
  {:pre [getter]}
  (case command
    "serve-api"
    (api/serve-api config)

    "worker"
    ; Before starting the worker, start a http server solely for the health endpoint as a daemon thread
    (let [thread (new Thread ^Runnable #(worker-api/serve-api config))]
      (.setDaemon thread true)
      (.start thread)
      (worker/wait-worker
        (worker/start-worker! config)))

    "get"
    (let [[client-info rest-args] (parse-client-info-args args clients)]
      (getter (assoc (parse-getter-args rest-args)
                :institution-oin (:institution-oin client-info))))

    ("show" "dry-run-upsert")
    (let [[client-info [type id]] (parse-client-info-args args clients)
          request (merge client-info {::ooapi/id id ::ooapi/type type})
          handler (if (= "show" command) ooapi-loader dry-run!)]
      (handler request))

    "link"
    (let [[client-info [code type id]] (parse-client-info-args args clients)
          codename (if (= type "education-specification") ::rio/opleidingscode ::rio/aangeboden-opleiding-code)
          request (merge client-info {::ooapi/id id ::ooapi/type type codename code})]
      (link! request))

    "resolve"
    (let [[client-info [type id]] (parse-client-info-args args clients)]
      (resolver type id (:institution-oin client-info)))

    ("upsert" "delete" "delete-by-code")
    (let [[client-info [type id rest-args]] (parse-client-info-args args clients)
          job (merge (assoc client-info
                       ::ooapi/type type
                       :args rest-args)
                     (if (= "delete-by-code" command)
                       (let [name-id (if (= type "education-specification")
                                       ::rio/opleidingscode
                                       ::rio/aangeboden-opleiding-code)]
                         {:action "delete"
                          name-id id})
                       {:action    command
                        ::ooapi/id id}))]
      (job/run! handlers job (= (System/getenv "STORE_HTTP_REQUESTS") "true")))))

(defn -main
  [command & args]
  (when (not (commands command))
    (.println *err* (str "Invalid command '" command "'."))
    (.println *err* (str "Valid commands are: " (str/join ", " commands)))
    (System/exit 1))

  (when (= command "help")
    (println (str "Available commands: " (str/join ", " commands) "."))
    (println "Configuration settings via environment:\n")
    (println (envopts/specs-description opts-spec))
    (System/exit 0))

  (let [result (process-command command args (make-config-and-handlers))]
    (case command
      ("serve-api" "worker")
      nil

      "get"
      (if (string? result) (println result)
                           (pprint/pprint result))

      ("dry-run-upsert" "show" "link")
      (pprint/pprint result)

      "resolve"
      (println result)

      ("upsert" "delete" "delete-by-code")
      (if (errors? result)
        (binding [*out* *err*]
          (prn result))
        (-> result json/write-str println)))))
