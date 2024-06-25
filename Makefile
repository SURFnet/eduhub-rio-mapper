.PHONY: lint test check clean

lint:
	clojure -M:lint

test:
	clojure -M:test

check: lint test

clean:
	rm -rf classes target

opentelemetry-javaagent.jar:
	curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar -o $@

.PHONY: test lint check
