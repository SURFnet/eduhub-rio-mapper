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

(ns nl.surf.eduhub-rio-mapper.job-test
  (:require
    [clj-http.client :as client]
    [clojure.data.json :as json]
    [clojure.test :refer :all]
    [nl.jomco.http-status-codes :as http-status]
    [nl.surf.eduhub-rio-mapper.endpoints.status :as status]
    [nl.surf.eduhub-rio-mapper.job :as job]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.test-helper :as helper])
  (:refer-clojure :exclude [run!]))

(def dummy-handlers {:delete! identity, :update! identity, :dry-run! identity, :link! identity})

(def dummy-job {::ooapi/id 0, ::ooapi/type 0, :action "delete", :institution-schac-home 0, :institution-oin 0})

(def config
  {:redis-conn       {:pool {} :spec {:uri (or (System/getenv "REDIS_URI") "redis://localhost")}}
   :redis-key-prefix "eduhub-rio-mapper-test"
   :status-ttl-sec   10
   :worker           {:nap-ms        10
                      :retry-wait-ms 10
                      :queues        ["foo" "bar"]
                      :queue-fn      :queue
                      :retryable-fn  (constantly false)
                      :error-fn      (constantly false)
                      :set-status-fn (fn [_ _ & [_]] (comment "nop"))}})

(deftest run!
  (testing "throwing an exception"
    (let [msg      "boom"
          handlers (assoc dummy-handlers :delete! (fn [_] (throw (ex-info msg {}))))]
      (is (status/retryable? (job/run! handlers dummy-job false))
          "throwing an exception results a retryable error")
      (is (= msg (-> (job/run! handlers dummy-job false) :errors :message))
          "throwing an exception results a retryable error"))))

(deftest ^:redis webhook
  (testing "webhook"
    (let [last-seen-request-atom (atom nil)
          set-status-fn          (status/make-set-status-fn config)
          job                    {::job/callback-url "https://github.com/"
                                  :action            "delete"
                                  ::ooapi/type       "course"
                                  ::ooapi/id         "123123"
                                  :created-at        "2024-08-30T08:41:34.929378Z"
                                  :started-at        "2024-08-30T08:41:34.929378Z"
                                  :token             "12345"}
          mock-webhook           (fn mock-webhook [req]
                                   (reset! last-seen-request-atom req)
                                   {:status http-status/ok
                                    :body   {:active    true
                                             :client_id "institution_client_id"}})]
      (binding [client/request mock-webhook]
        (set-status-fn job :done {:aanleveren_opleidingseenheid_response {:opleidingseenheidcode "123"}})
        (helper/wait-while-predicate nil? last-seen-request-atom 1)
        (let [req @last-seen-request-atom]
          (is (= {:status        "done"
                  :action        "delete"
                  :resource      "course/123123"
                  :attributes    {:opleidingseenheidcode "123"}
                  :created-at    "2024-08-30T08:41:34.929378Z"
                  :started-at    "2024-08-30T08:41:34.929378Z"
                  :token         "12345"}
                 (dissoc (json/read-str (:body req) {:key-fn keyword}) :finished-at)))
          (is (= (:url req) "https://github.com/")))))))
