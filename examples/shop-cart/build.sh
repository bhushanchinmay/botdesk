#!/usr/bin/env bash
# Compile the app. No build tool needed.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -d out $(find src -name '*.java')
echo "build ok"
