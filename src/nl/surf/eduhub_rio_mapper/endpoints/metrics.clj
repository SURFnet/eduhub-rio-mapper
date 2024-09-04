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

(ns nl.surf.eduhub-rio-mapper.endpoints.metrics
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.utils.redis :as redis]))

(def jobs-count-by-status-key-name "jobs-count-by-status")

(defn increment-count [{:keys [redis-conn]} job status]
  (let [hash-key (str (:institution-schac-home job) "/" (name status))]
    (redis/hincrby redis-conn jobs-count-by-status-key-name hash-key 1)))

;; Wraps the set-status-fn in order to increment the job count if it is a final status
;; (done,error,timeout). These, and only these, have a third argument with the result.
(defn wrap-increment-count [config set-status-fn]
  (fn
    ([job status]
     (set-status-fn job status))
    ([job status result]
     (increment-count config job status)
     (set-status-fn job status result))))

;; Retrieves the total number of processed jobs by status (started,done,error,time_out)
;; The job count is grouped per schachome. Returns a map with as keys the status (keyword)
;; and as values maps with as keys the schachome and count (integer) as values.
(defn fetch-jobs-by-status-count [{:keys [redis-conn] :as _config}]
  {:post [(every? string? (keys %))
          (every? map? (vals %))]}
  (let [process-pair
        (fn [[k cnt]]
          (let [[schach-home status] (clojure.string/split k #"/")]
            [status schach-home cnt]))
        process-triplet
        (fn [h [status schach-home cnt]]
          (assoc-in h [status schach-home] cnt))]
    (->> (redis/hgetall redis-conn jobs-count-by-status-key-name)
         (partition 2)
         (map process-pair)
         (reduce process-triplet {}))))

(defn prometheus-current-jobs [queue-count schac-home-to-name]
  (map (fn [[k v]] (format "rio_mapper_active_and_queued_job_count{schac_home=\"%s\", institution_name=\"%s\"} %s" k (schac-home-to-name k) v))
       queue-count))

(defn prometheus-jobs-by-status [jobs-count-by-status schac-home-to-name]
  {:pre [(every? string? (keys jobs-count-by-status))
         (every? map? (vals jobs-count-by-status))]}
  (mapcat (fn [status]
            (map (fn [[k v]]
                   (format "rio_mapper_jobs_total{schac_home=\"%s\", institution_name=\"%s\", job_status=\"%s\"} %s" k (schac-home-to-name k) status v))
                 (get jobs-count-by-status status)))
          ["started" "done" "time_out" "error"]))

(defn count-queues [grouped-queue-counter client-schac-homes]
  {:post [(map? %)
          (every? string? (keys %))
          (every? integer? (vals %))]}
  ;; For all keys in client-schac-homes, add value 0 in map if not already in map
  (reduce (fn [m k] (assoc m k (get m k 0)))
          (merge-with +
                      (grouped-queue-counter :queue)
                      (grouped-queue-counter :busy-queue))
          client-schac-homes))
