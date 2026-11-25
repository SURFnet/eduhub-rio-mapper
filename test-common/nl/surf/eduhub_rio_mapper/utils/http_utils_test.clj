;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2026 SURFnet B.V.
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

(ns nl.surf.eduhub-rio-mapper.utils.http-utils-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils])
  (:import (java.net ConnectException)
           (java.security.cert CertificateExpiredException)))

(def url "https://duo.example.com/RIO/services/raadplegen4.0")

(defn- send-failing-request
  "Send a request through `send-http-request` that fails with `ex`.
  Returns the thrown exception and the recorded http-messages."
  [ex]
  (binding [http-utils/*vcr*          (fn [_handler _request] (throw ex))
            http-utils/*http-messages* (atom [])]
    (let [thrown (try
                   (http-utils/send-http-request {:url url :method :post :content-type :xml})
                   nil
                   (catch Exception e e))]
      [thrown @http-utils/*http-messages*])))

(deftest expired-certificate-test
  (let [[ex messages] (send-failing-request
                         ;; This is what clj-http throws for a server with an expired
                         ;; certificate; see `expired-certificate-real-server-test`.
                         (CertificateExpiredException. "NotAfter: Tue Jan 20 14:40:04 GMT 2026"))]
    (testing "message names the URL and the problem"
      (is (= (str "Could not connect to " url ": TLS handshake failed - certificate expired "
                  "(NotAfter: Tue Jan 20 14:40:04 GMT 2026)")
             (ex-message ex))))
    (testing "certificate errors are not retried"
      (is (false? (:retryable? (ex-data ex)))))
    (testing "failed request is recorded in http-messages"
      (is (= 1 (count messages)))
      (is (= url (-> messages first :req :url)))
      (is (= "java.security.cert.CertificateExpiredException" (-> messages first :error :class))))))

(deftest connection-refused-test
  (let [[ex messages] (send-failing-request (ConnectException. "Connection refused"))]
    (is (str/starts-with? (ex-message ex) (str "Could not connect to " url ": java.net.ConnectException")))
    (is (not (contains? (ex-data ex) :retryable?))
        "other connection errors stay retryable")
    (is (= "Connection refused" (-> messages first :error :message)))))

(deftest expired-certificate-real-server-test
  (testing "real TLS handshake against a server with an expired certificate"
    (binding [http-utils/*http-messages* (atom [])]
      (let [url "https://expired.badssl.com/"
            ex  (try
                  (http-utils/send-http-request {:url url :method :get :content-type :json})
                  nil
                  (catch Exception e e))]
        (is (some? ex) "request should fail")
        (is (str/starts-with? (ex-message ex)
                              (str "Could not connect to " url ": TLS handshake failed - certificate expired (NotAfter: ")))
        (is (false? (:retryable? (ex-data ex))))
        (is (= url (-> @http-utils/*http-messages* first :req :url)))))))
