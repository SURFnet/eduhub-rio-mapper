#!/usr/bin/env bash

set -ex

CLOJURE_VERSION="1.11.3.1463"

if [ ! -x "bin/clojure" ]; then
    curl -O "https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh"
    bash "linux-install-${CLOJURE_VERSION}.sh" -p "$(pwd)"
fi
