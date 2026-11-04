#!/usr/bin/env bash
# Attach any git repo to botdesk. Detects the build tool and writes projects/<name>.json.
#   ./scripts/attach.sh /path/to/repo [name] [github-owner/repo]
# Review the generated file: the "checks" commands are the ONLY commands agents may run, and
# "protectedPaths" must cover every file that decides what a check does (build and test config),
# or an agent could make a check pass by changing what it runs.
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="$(cd "${1:?usage: attach.sh /path/to/repo [name] [owner/repo]}" && pwd)"
NAME="${2:-$(basename "$REPO")}"
GITHUB="${3:-}"
git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1 || { echo "Not a git repo: $REPO"; exit 1; }
BASE="$(git -C "$REPO" symbolic-ref --short HEAD 2>/dev/null || echo main)"

# Detect how this project is built and tested.
if   [[ -f "$REPO/pom.xml" ]];          then BUILD="mvn -q -B -DskipTests compile"; TEST="mvn -q -B test"; TESTS='"src/test/"'; PROTECT='"pom.xml", "mvnw", ".mvn/", ".github/"'
elif [[ -f "$REPO/gradlew" ]];          then BUILD="./gradlew -q assemble";        TEST="./gradlew -q test"; TESTS='"src/test/"'; PROTECT='"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties", "gradlew", "gradle/", "buildSrc/", ".github/"'
elif [[ -f "$REPO/package.json" ]];     then BUILD="npm run build --if-present";   TEST="npm test";          TESTS='"test/", "tests/", "__tests__/"'; PROTECT='"package.json", ".npmrc", "jest.config.js", "jest.config.ts", "vitest.config.ts", "vite.config.ts", ".github/"'
elif [[ -f "$REPO/pyproject.toml" || -f "$REPO/requirements.txt" ]]; then
                                             BUILD="python3 -m compileall -q .";   TEST="python3 -m pytest -q"; TESTS='"tests/", "test/"'; PROTECT='"pyproject.toml", "setup.cfg", "pytest.ini", "tox.ini", "conftest.py", ".github/"'
elif [[ -f "$REPO/test.sh" ]];          then BUILD="./build.sh";                   TEST="./test.sh";         TESTS='"tests/"'; PROTECT='"build.sh", "test.sh", ".github/"'
else echo "Could not detect a build tool. Write projects/$NAME.json by hand (see projects/shop-cart.json)."; exit 1
fi

CONTEXT=""
for f in README.md CONTRIBUTING.md docs/ARCHITECTURE.md AGENTS.md CLAUDE.md; do
  [[ -f "$REPO/$f" ]] && CONTEXT+="\"$f\", "
done
CONTEXT="${CONTEXT%, }"

mkdir -p projects
cat > "projects/$NAME.json" <<EOF
{
  "name": "$NAME",
  "repo": "$REPO",
  "baseBranch": "$BASE",
  "checks": { "build": "$BUILD", "test": "$TEST" },
  "context": [$CONTEXT],
  "testPaths": [$TESTS],
  "protectedPaths": [$PROTECT],
  "githubRepo": "$GITHUB",
  "remote": "origin"
}
EOF
echo "Attached $NAME -> projects/$NAME.json"
cat "projects/$NAME.json"
echo "Restart botdesk to load it."
