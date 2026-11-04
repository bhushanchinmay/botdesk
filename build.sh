#!/usr/bin/env bash
# Builds botdesk.jar with plain javac. No Maven, no internet needed.
# Usage: ./build.sh          build the jar
#        ./build.sh test     build, then run the test suite
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out && mkdir -p out/classes out/test-classes
javac --release 21 -d out/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. out/classes/
jar --create --file botdesk.jar --main-class io.github.bhushanchinmay.botdesk.Main -C out/classes .
echo "Built botdesk.jar ($(du -h botdesk.jar | cut -f1))"

if [[ "${1:-}" == "test" ]]; then
  javac --release 21 -cp out/classes -d out/test-classes $(find src/test/java -name '*.java')
  java -cp out/classes:out/test-classes io.github.bhushanchinmay.botdesk.TestRunner
fi
