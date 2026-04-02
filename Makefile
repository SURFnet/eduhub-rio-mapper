.PHONY: all jar lint proof-specs test test-redis test-e2e test-v5 record-v5 record-v6 test-v6 test-common create-common-test watson antq clean


all: lint proof-specs test watson clean jar

jar: target/eduhub-rio-mapper.jar

test-all-units:
	clojure -M:test-v5
	clojure -M:test-v6
	clojure -M:test-common
	clojure -M:test-v5-redis
	clojure -M:test-v6-redis

lint:
	clojure -M:lint

proof-specs:
	clojure -M:proof-specs

test: test-v5 test-v6

test-v5: test-common
	clojure -M:test-v5 v5

test-v5-redis:
	clojure -M:test-v5 v5-redis

test-v5-e2e:
	clojure -M:test-v5 v5-e2e --no-capture-output

playback-v5:
	clojure -M:test-v5 v5-vcr

record-v5:
	rm -rf test-v5/fixtures/vcr/*
	OOAPI_VERSION=v5 VCR_RECORD=true clojure -M:test-v5 v5-vcr

test-v6: test-common
	clojure -M:test-v6 v6

test-v6-redis:
	clojure -M:test-v6 v6-redis

test-v6-e2e:
	clojure -M:test-v6 v6-e2e --no-capture-output

playback-v6:
	clojure -M:test-v6 v6-vcr

record-v6:
	rm -rf test-v6/fixtures/vcr/*
	OOAPI_VERSION=v6 VCR_RECORD=true clojure -M:test-v6 v6-vcr

test-common:
	clojure -M:test-common  common

watson:
	clojure -M:watson

antq:
	clojure -M:antq

generate-enums:
	clojure -M:dev -m generate-enums.main

beheren-edn:
	clojure -M:dev -m xsd-to-edn.main schema

types-edn:
	clojure -M:dev -m xsd-to-edn.main types

clean:
	rm -rf target
	mkdir -p target/classes

target/eduhub-rio-mapper-v5.jar: clean $(shell find src-v5 src-common resources -type f)
	clojure -M:v5 -e "(binding [*compile-path* \"target/classes\"] (compile 'nl.surf.eduhub-rio-mapper.v5.main))"
	clojure -M:uberjar --aliases v5:package --main-class nl.surf.eduhub_rio_mapper.v5.main --target $@

target/eduhub-rio-mapper-v6.jar: clean $(shell find src-v6 src-common resources -type f)
	clojure -M:v6 -e "(binding [*compile-path* \"target/classes\"] (compile 'nl.surf.eduhub-rio-mapper.v6.main))"
	clojure -M:uberjar --aliases v6:package --main-class nl.surf.eduhub_rio_mapper.v6.main --target $@
