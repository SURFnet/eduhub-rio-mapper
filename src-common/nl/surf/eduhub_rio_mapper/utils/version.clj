(ns nl.surf.eduhub-rio-mapper.utils.version)

(def OOAPI-VERSION
  (if-let [v (System/getenv "OOAPI_VERSION")]
    v
    (do
      (with-out-str *err*
        (println "OOAPI_VERSION is undefined, defaulting to v5"))
      "v5")))
