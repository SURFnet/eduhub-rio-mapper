;; Extracted from ring/ring-json. That project is unmaintained, and has dependencies with CVEs.
;; Copyright © 2021 James Reeves
;; Distributed under the MIT License, the same as Ring.
(ns nl.surf.eduhub-rio-mapper.endpoints.ring-middleware-json
  "Ring middleware for parsing JSON requests and generating JSON responses."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [ring.core.protocols :as ring-protocols]
            [ring.util.response :refer [content-type]]))

(defrecord JsonStreamingResponseBody [body options]
  ring-protocols/StreamableResponseBody
  (write-body-to-stream [_ _ output-stream]
    (json/generate-stream body (io/writer output-stream) options)))

(defn json-response
  "Converts responses with a map or a vector for a body into a JSON response.
  See: wrap-json-response."
  [response options]
  (if (coll? (:body response))
    (let [generator (if (:stream? options)
                      ->JsonStreamingResponseBody
                      json/generate-string)
          options (dissoc options :stream?)
          json-resp (update response :body generator options)]
      (if (contains? (:headers response) "Content-Type")
        json-resp
        (content-type json-resp "application/json; charset=utf-8")))
    response))

(defn wrap-json-response
  "Middleware that converts responses with a map or a vector for a body into a JSON response.

  Accepts the following options:

  :key-fn            - function that will be applied to each key
  :pretty            - true if the JSON should be pretty-printed
  :escape-non-ascii  - true if non-ASCII characters should be escaped with \\u
  :stream?           - true to create JSON body as stream rather than string"
  {:arglists '([handler] [handler options])}
  [handler & [{:as options}]]
  (fn
    ([request]
     (json-response (handler request) options))
    ([request respond raise]
     (handler request (fn [response] (respond (json-response response options))) raise))))
