#!/usr/bin/env bash
# Run from your Mac. Copies the source to the VPS, builds there, restarts the service.
# Your Mac does no building and runs nothing.
#   ./deploy/deploy.sh ubuntu@203.0.113.10
set -euo pipefail
HOST="${1:?usage: deploy.sh user@vps-ip}"
cd "$(dirname "$0")/.."

rsync -az --delete \
  --exclude data/ --exclude out/ --exclude botdesk.jar --exclude .env --exclude .git/ \
  ./ "$HOST":/tmp/botdesk-src/

ssh "$HOST" 'set -e
  cd /tmp/botdesk-src && ./build.sh test
  sudo rsync -a --exclude data/ --exclude .env ./ /opt/botdesk/
  sudo chown -R botdesk:botdesk /opt/botdesk
  sudo cp /opt/botdesk/deploy/botdesk.service /etc/systemd/system/botdesk.service
  sudo systemctl daemon-reload
  sudo systemctl enable --now botdesk
  sudo systemctl restart botdesk
  sleep 3 && curl -fsS http://127.0.0.1:8080/health && echo " <- botdesk is up"'
