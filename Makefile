.PHONY: all jar lint proof-specs test test-redis test-e2e test-all test-common create-common-test watson antq clean

MAIN_CLASS=nl.surf.eduhub-rio-mapper.v5.main
JAR_FILE=target/eduhub-rio-mapper.jar

DEPS_EDN=deps.edn
DEPS_PATHS=src-v5 src-common resources # should be the same as defined in deps.edn
CLASSES_DIR=target/classes
TEST_OPTS=

all: lint proof-specs test watson clean jar

jar: target/eduhub-rio-mapper.jar

lint:
	clojure -M:lint

proof-specs:
	clojure -M:proof-specs

test:
	clojure -M:test $(TEST_OPTS) --skip-meta :redis --skip-meta :e2e

test-redis:
	clojure -M:test $(TEST_OPTS) --focus-meta :redis

test-e2e:
	clojure -M:test $(TEST_OPTS) --focus-meta :e2e --no-capture-output

test-all:
	clojure -M:test

test-common:
	clojure -M:test-common

create-common-test:
	@echo "(ns nl.surf.eduhub-rio-mapper.dependency-test" > test-common/nl/surf/eduhub_rio_mapper/dependency_test.clj
	@echo "  (:require [clojure.test :refer [deftest is]]" >> test-common/nl/surf/eduhub_rio_mapper/dependency_test.clj
	@for ns in $$(find src-common -name "*.clj" -o -name "*.cljc" | sort | sed 's|src-common/||; s|/|.|g; s|_|-|g; s|\.clj[c]*$$||'); do \
		echo "            [$$ns]" >> test-common/nl/surf/eduhub_rio_mapper/dependency_test.clj; \
	done
	@echo "            ))" >> test-common/nl/surf/eduhub_rio_mapper/dependency_test.clj
	@echo "" >> test-common/nl/surf/eduhub_rio_mapper/dependency_test.clj
	@echo "(deftest ^:common common-has-no-v5-deps" >> test-common/nl/surf/eduhub_rio_mapper/dependency_test.clj
	@echo "  (is true \"Common code successfully loaded without v5 dependencies\"))" >> test-common/nl/surf/eduhub_rio_mapper/dependency_test.clj

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
	rm -rf $(CLASSES_DIR) $(JAR_FILE)

MAIN_CLASS_FILE = $(subst -,_,$(subst .,/,$(MAIN_CLASS)))
MAIN_CLASS_FILE_CLASS = $(join $(CLASSES_DIR)/,$(addsuffix .class,$(MAIN_CLASS_FILE)))
COMPILE_EXPR = $(subst CLASSES_DIR,$(CLASSES_DIR),"(binding [*compile-path* \"CLASSES_DIR\"] (compile 'MAIN_CLASS))")

$(MAIN_CLASS_FILE_CLASS): $(DEPS_EDN) $(shell find $(DEPS_PATHS) -type f)
	rm -rf $(CLASSES_DIR)
	mkdir -p $(CLASSES_DIR)
	clojure -M -e $(subst MAIN_CLASS,$(MAIN_CLASS),$(COMPILE_EXPR))

$(JAR_FILE): $(MAIN_CLASS_FILE_CLASS)
	clojure -M:uberjar --main-class $(MAIN_CLASS) --target $@
