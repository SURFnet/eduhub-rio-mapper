.PHONY: lint test check clean

lint:
	clojure -M:lint

test:
	clojure -M:test

check: lint test

clean:
	rm -rf classes target

.PHONY: test lint check
