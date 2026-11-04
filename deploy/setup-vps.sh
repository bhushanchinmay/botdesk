#!/usr/bin/env bash
# One-time VPS setup. Run on the VPS as a sudo user (Ubuntu 22.04/24.04, x86 or ARM):
#   sudo bash setup-vps.sh
set -euo pipefail

apt-get update -y
apt-get install -y --no-install-recommends openjdk-21-jdk-headless rsync git

id botdesk &>/dev/null || useradd --system --home /opt/botdesk --shell /usr/sbin/nologin botdesk
mkdir -p /opt/botdesk/data/repos   # clone attached projects here: the service can only write under data/
chown -R botdesk:botdesk /opt/botdesk

if [[ ! -f /opt/botdesk/.env ]]; then
  cat > /opt/botdesk/.env <<'EOF'
# API keys. Only the ones your botdesk.json roles use are required.
OPENROUTER_API_KEY=
EXPERIENTIAL_API_KEY=
COMMANDCODE_API_KEY=
# GitHub fine-grained token (only for projects with githubRepo set)
GITHUB_TOKEN=
# Slack (only if slack.enabled is true)
SLACK_APP_TOKEN=
SLACK_BOT_TOKEN=
EOF
fi
chown botdesk:botdesk /opt/botdesk/.env
chmod 600 /opt/botdesk/.env

echo "Done. Next: fill in /opt/botdesk/.env (sudo nano /opt/botdesk/.env), then run deploy.sh from your Mac."
