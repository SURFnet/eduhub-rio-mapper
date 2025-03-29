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
  (:require [clojure.string]
            [steffan-westcott.clj-otel.api.metrics.instrument :as instrument]))

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

(defn- update-gauge [gauge data]
  (doseq [[k v] data]
    (instrument/set! gauge {:value v :attributes {"queue-name" k}})))

(defn make-jobs-counter [schac-home-to-name queue-counter institution-schac-homes]
  (let [counter (instrument/instrument {:name            "rio_mapper_http_requests_total"
                                        :instrument-type :counter})

        gauge   (instrument/instrument {:name            "rio_mapper_active_and_queued_job_count"
                                        :instrument-type :gauge})]
    (fn [job status]
      (update-gauge gauge (count-queues queue-counter institution-schac-homes))
      (let [schac-home (:institution-schac-home job)
            attributes {:status           (name status)
                        :schac-home       schac-home
                        :institution-name (schac-home-to-name schac-home)}]
        (instrument/add! counter {:value 1 :attributes attributes})))))

;; Wraps the set-status-fn in order to increment the job count if it is a final status
;; (done,error,timeout). These, and only these, have a third argument with the result.
(defn wrap-increment-count [jobs-counter set-status-fn]
  (fn
    ([job status]
     (set-status-fn job status))
    ([job status result]
     (jobs-counter job status)
     (set-status-fn job status result))))
