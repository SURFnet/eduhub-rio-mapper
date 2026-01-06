.PHONY: all jar lint proof-specs test test-redis test-e2e test-v6 test-common create-common-test watson antq clean

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

test-v5: test-common
	clojure -M:test-v5

test-v5-redis:
	clojure -M:test-v5-redis

test-v5-e2e:
	clojure -M:test  --focus :v5-e2e --no-capture-output

test-v6: test-common
	clojure -M:test-v6

test-v6-redis:
	clojure -M:test-v6-redis

test-v6-e2e:
	clojure -M:test  --focus :v6-e2e --no-capture-output

test-common:
	clojure -M:test-common  --focus :common

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
