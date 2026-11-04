#!/usr/bin/env bash
# Compile app + tests, then run the tests.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -d out $(find src tests -name '*.java')
java -cp out shop.CartTest
