#!/usr/bin/env bash
# Run botdesk on your Mac. Models still run in the cloud through your API keys;
# only the small orchestrator + dashboard runs locally (~100 MB RAM).
#
# API keys are read in this order, per key:
#   1. an environment variable that is already set
#   2. the macOS Keychain (service "botdesk", account = key name)   <- recommended
#   3. a .env file in the project folder (never commit it)
#
# Save a key in the Keychain once:
#   security add-generic-password -U -s botdesk -a OPENROUTER_API_KEY -w
#   (it prompts for the key, so it never lands in your shell history)
set -euo pipefail
cd "$(dirname "$0")/.."

KEYS=(OPENROUTER_API_KEY EXPERIENTIAL_API_KEY COMMANDCODE_API_KEY GITHUB_TOKEN SLACK_APP_TOKEN SLACK_BOT_TOKEN)

if [[ -f .env ]]; then
  set -a; source .env; set +a
fi

for name in "${KEYS[@]}"; do
  if [[ -z "${!name:-}" ]] && command -v security >/dev/null; then
    value="$(security find-generic-password -s botdesk -a "$name" -w 2>/dev/null || true)"
    [[ -n "$value" ]] && export "$name=$value"
  fi
done

# Rebuild when any source file is newer than the jar.
if [[ ! -f botdesk.jar ]] || [[ -n "$(find src -newer botdesk.jar -type f | head -1)" ]]; then
  ./build.sh
fi

# Pass --check to only test keys and models: ./scripts/run-local.sh --check
exec java -Xmx128m -XX:+UseSerialGC -jar botdesk.jar botdesk.json "$@"
