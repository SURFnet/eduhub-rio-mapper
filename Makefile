.PHONY: all jar lint proof-specs test test-redis test-e2e test-all watson antq clean

MAIN_CLASS=nl.surf.eduhub-rio-mapper.main
JAR_FILE=target/eduhub-rio-mapper.jar

DEPS_EDN=deps.edn
DEPS_PATHS=src resources # should be the same as defined in deps.edn
CLASSES_DIR=target/classes

all: lint proof-specs test watson clean jar

jar: target/eduhub-rio-mapper.jar

lint:
	clojure -M:lint

proof-specs:
	clojure -M:proof-specs

test:
	clojure -M:test --skip-meta :redis --skip-meta :e2e

test-redis:
	clojure -M:test --focus-meta :redis

test-e2e:
	clojure -M:test --focus-meta :e2e

test-all:
	clojure -M:test

watson:
	clojure -M:watson

antq:
	clojure -M:antq

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
