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

(ns nl.surf.eduhub-rio-mapper.commands.processing
  (:require
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [nl.jomco.http-status-codes :as http-status]
    [nl.surf.eduhub-rio-mapper.commands.dry-run :as dry-run]
    [nl.surf.eduhub-rio-mapper.commands.link :as link]
    [nl.surf.eduhub-rio-mapper.ooapi.base :as ooapi-base]
    [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
    [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
    [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
    [nl.surf.eduhub-rio-mapper.rio.relation-handler :as relation-handler]
    [nl.surf.eduhub-rio-mapper.rio.updated-handler :as updated-handler]
    [nl.surf.eduhub-rio-mapper.specs.mutation :as mutation]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
    [nl.surf.eduhub-rio-mapper.utils.logging :as logging]
    [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils]))

(defn- extract-eduspec-from-result [result]
  (let [entity (:ooapi result)]
    (when (= "aanleveren_opleidingseenheid" (:action result))
      entity)))

(defn blocking-retry
  "Calls f and retries if it returns nil.

  Sleeps between each invocation as specified in retry-delays-seconds.
  Returns return value of f when successful.
  Returns nil when as many retries as delays have taken place. "
  [f retry-delays-seconds action]
  (loop [retry-delays-seconds retry-delays-seconds]
    (or
      (f)
      (when-not (empty? retry-delays-seconds)
        (let [[head & tail] retry-delays-seconds]
          (log/warn (format "%s failed - sleeping for %s seconds." action head))
          (Thread/sleep (long (* 1000 head)))
          (recur tail))))))

(defn- make-updater-load-ooapi-phase [{:keys [ooapi-loader]}]
  (let [validating-loader (ooapi.loader/validating-loader ooapi-loader)]
    (fn load-ooapi-phase [{::ooapi/keys [type id] :as request}]
      (logging/with-mdc
        {:ooapi-type type :ooapi-id id}
        (ooapi.loader/load-entities validating-loader request)))))

(defn- make-updater-resolve-phase [{:keys [resolver]}]
  (fn resolve-phase [{:keys [institution-oin action]
                      ::ooapi/keys [type id entity]
                      ::rio/keys [opleidingscode] :as request}]
    {:pre [institution-oin]}
    (let [resolve-eduspec (= type "education-specification")
          edu-id          (if (= type "education-specification")
                            id
                            (ooapi-base/education-specification-id entity))
          oe-code         (or opleidingscode
                              (resolver "education-specification" edu-id institution-oin))
          ao-code         (when-not resolve-eduspec (resolver type id institution-oin))]
      ;; Inserting a course or program while the education
      ;; specification has not been added to RIO will throw an error.
      ;; Also throw an error when trying to delete an education specification
      ;; that cannot be resolved.
      (when (or (and (nil? oe-code) (not resolve-eduspec) (= "upsert" action))
                (and (nil? oe-code) resolve-eduspec (= "delete" action)))
        (throw (ex-info (str "No 'opleidingseenheid' found in RIO with eigensleutel: " edu-id)
                        {:code       oe-code
                         :type       type
                         :action     action
                         :retryable? false})))
      (cond-> request
              oe-code (assoc ::rio/opleidingscode oe-code)
              ao-code (assoc ::rio/aangeboden-opleiding-code ao-code)))))

(defn- make-updater-soap-phase []
  (fn soap-phase [{:keys [institution-oin] :as job}]
    {:pre [institution-oin (job :institution-schac-home)]}
    (let [result  (updated-handler/update-mutation job)
          eduspec (extract-eduspec-from-result result)]
      {:job job :result result :eduspec eduspec})))

(defn- make-deleter-prune-relations-phase [handlers]
  (fn [{::ooapi/keys [type] ::rio/keys [opleidingscode] :keys [institution-oin] :as request}]
    (when (and opleidingscode (= type "education-specification"))
      (relation-handler/delete-relations opleidingscode type institution-oin handlers))
    request))

(defn- make-deleter-soap-phase []
  (fn soap-phase [{:keys [institution-oin] :as job}]
    {:pre [institution-oin (job :institution-schac-home)]}
    (let [result  (updated-handler/deletion-mutation job)
          eduspec (extract-eduspec-from-result result)]
      {:job job :result result :eduspec eduspec})))

(defn- make-updater-mutate-rio-phase [{:keys [rio-config]}]
  (fn mutate-rio-phase [{:keys [job result eduspec]}]
    {:pre [(s/valid? ::mutation/mutation-response result)]}
    (logging/with-mdc {:soap-action (:action result) :ooapi-id (::ooapi/id job)}
      {:job job :eduspec eduspec :mutate-result (mutator/mutate! result rio-config)})))

(defn- make-updater-confirm-rio-phase [{:keys [resolver]} rio-config]
  (fn confirm-rio-phase [{{::ooapi/keys [id type]
                           :keys        [institution-oin]
                           :as          job} :job
                          mutate-result      :mutate-result
                          eduspec            :eduspec}]
    (if-let [rio-code (blocking-retry #(resolver type id institution-oin)
                                   (:rio-retry-attempts-seconds rio-config)
                                   "Ensure upsert is processed by RIO")]
      (if (= type "education-specification")
        {:job           job
         :eduspec       (assoc eduspec ::rio/opleidingscode rio-code)
         :mutate-result mutate-result}
        {:job           (assoc job ::rio/aangeboden-opleiding-code rio-code)
         :eduspec       eduspec
         :mutate-result mutate-result})
      (throw (ex-info (str "Processing this job takes longer than expected. Our developers have been informed and will contact DUO. Please try again in a few hours."
                           ": " type " " id) {:rio-queue-status :down})))))

(defn- make-updater-sync-relations-phase [handlers]
  (fn sync-relations-phase [{:keys [job eduspec] :as request}]
    (when eduspec
      (relation-handler/after-upsert eduspec job handlers))
    request))

(defn- wrap-phase [[phase f]]
  (fn [req]
    (try
      (f req)
      (catch Exception ex
        (throw (ex-info (ex-message ex)
                        (assoc (ex-data ex) :phase phase)
                        ex))))))

(defn- make-insert [handlers rio-config]
  (let [fs [[:preparing      (make-updater-soap-phase)]
            [:upserting      (make-updater-mutate-rio-phase handlers)]
            [:confirming     (make-updater-confirm-rio-phase handlers rio-config)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)
             (::ooapi/entity request)]}
      (as-> request $
            (reduce (fn [req f] (f req)) $ wrapped-fs)
            (:mutate-result $)))))

(defn- make-update [handlers rio-config]
  (let [fs [[:fetching-ooapi (make-updater-load-ooapi-phase handlers)]
            [:resolving      (make-updater-resolve-phase handlers)]
            [:preparing      (make-updater-soap-phase)]
            [:upserting      (make-updater-mutate-rio-phase handlers)]
            [:confirming     (make-updater-confirm-rio-phase handlers rio-config)]
            [:associating    (make-updater-sync-relations-phase handlers)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)]}
      (as-> request $
            (reduce (fn [req f] (f req)) $ wrapped-fs)
            (merge (:mutate-result $) (select-keys (:job $) [::rio/aangeboden-opleiding-code]))))))

(defn- make-deleter [{:keys [rio-config] :as handlers}]
  {:pre [rio-config]}
  (let [fs [[:resolving (make-updater-resolve-phase handlers)]
            [:deleting  (make-deleter-prune-relations-phase handlers)]
            [:preparing (make-deleter-soap-phase)]
            [:deleting  (make-updater-mutate-rio-phase handlers)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)]}
      (as-> request $
            (reduce (fn [req f] (f req)) $ wrapped-fs)
            (:mutate-result $)))))

(defn- dry-run-status [rio-summary ooapi-summary]
  {:status (if ooapi-summary (if rio-summary "found" "not-found") "error")})

(defn- eduspec-dry-run-handler [ooapi-entity {::ooapi/keys [id] :keys [institution-oin]} {:keys [resolver getter]}]
  (let [rio-code      (resolver "education-specification" id institution-oin)
        rio-summary   (some-> rio-code
                              (rio.loader/find-opleidingseenheid getter institution-oin)
                              (dry-run/summarize-opleidingseenheid))
        ooapi-summary (dry-run/summarize-eduspec ooapi-entity)
        diff   (dry-run/generate-diff-ooapi-rio :rio-summary rio-summary :ooapi-summary ooapi-summary)
        output (if (nil? ooapi-summary) diff (assoc diff :opleidingseenheidcode rio-code))]
    (merge output (dry-run-status rio-summary ooapi-summary))))

(defn- course-program-dry-run-handler [ooapi-entity {::ooapi/keys [id] :keys [institution-oin] :as request} {:keys [getter ooapi-loader]}]
  (let [rio-obj     (rio.loader/find-aangebodenopleiding id getter institution-oin)
        rio-summary (dry-run/summarize-aangebodenopleiding-xml rio-obj)
        offering-summary (->> (ooapi.loader/load-offerings ooapi-loader request)
                              (map dry-run/summarize-offering)
                              (sort-by :cohortcode)
                              vec)
        ooapi-summary (dry-run/summarize-course-program (assoc ooapi-entity :offerings offering-summary))
        rio-code (when rio-obj (xml-utils/find-content-in-xmlseq (xml-seq rio-obj) :aangebodenOpleidingCode))
        diff   (dry-run/generate-diff-ooapi-rio :rio-summary rio-summary :ooapi-summary ooapi-summary)
        output (if (nil? ooapi-summary) diff (assoc diff :aangebodenOpleidingCode rio-code))]
    (merge output (dry-run-status rio-summary ooapi-summary))))

(defn- not-found? [obj]
  (= http-status/not-found (:status obj)))

(defn- safe-loader [f]
  (try
    (f)
    (catch Exception ex
      (if (not-found? (ex-data ex))
        (ex-data ex)
        (throw ex)))))

(defn- make-dry-runner [{:keys [rio-config ooapi-loader] :as handlers}]
  {:pre [rio-config]}
  (fn [{::ooapi/keys [type] :as request}]
    {:pre [(:institution-oin request)]}
    (let [ooapi-entity (safe-loader (fn [] (ooapi-loader request)))
          value (if (not-found? ooapi-entity)
                  {:status "error"}
                  (let [handler (case type "education-specification" eduspec-dry-run-handler
                                           ("course" "program") course-program-dry-run-handler)]
                    (handler ooapi-entity request handlers)))]
      {:dry-run value})))

(defn make-handlers
  [{:keys [rio-config
           gateway-root-url
           gateway-credentials]}]
  {:pre [(:recipient-oin rio-config)]}
  (let [resolver     (rio.loader/make-resolver rio-config)
        getter       (rio.loader/make-getter rio-config)
        ooapi-loader (ooapi.loader/make-ooapi-http-loader gateway-root-url
                                                          gateway-credentials
                                                          rio-config)
        handlers     {:ooapi-loader ooapi-loader
                      :rio-config   rio-config
                      :getter       getter
                      :resolver     resolver}
        update!      (make-update handlers rio-config)
        delete!      (make-deleter handlers)
        insert!      (make-insert handlers rio-config)
        dry-run!     (make-dry-runner handlers)
        link!        (link/make-linker rio-config getter)]
    (assoc handlers :update! update!, :delete! delete!, :insert! insert!, :dry-run! dry-run!, :link! link!)))
