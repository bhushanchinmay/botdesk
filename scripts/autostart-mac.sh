#!/usr/bin/env bash
# Start botdesk automatically when you log in, and restart it if it crashes.
#   ./scripts/autostart-mac.sh on     install and start
#   ./scripts/autostart-mac.sh off    stop and remove
#   ./scripts/autostart-mac.sh status
set -euo pipefail
cd "$(dirname "$0")/.."
PROJECT="$(pwd)"
LABEL="io.github.bhushanchinmay.botdesk"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"
JAVA_BIN="$(dirname "$(command -v java)")"

case "${1:-}" in
  on)
    mkdir -p "$HOME/Library/LaunchAgents" "$PROJECT/data"
    cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array>
    <string>/bin/bash</string><string>$PROJECT/scripts/run-local.sh</string>
  </array>
  <key>EnvironmentVariables</key><dict>
    <key>PATH</key><string>$JAVA_BIN:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>$PROJECT/data/botdesk.log</string>
  <key>StandardErrorPath</key><string>$PROJECT/data/botdesk.log</string>
</dict></plist>
EOF
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    launchctl bootstrap "$DOMAIN" "$PLIST"
    echo "botdesk will start at login. Dashboard: http://localhost:8080  Log: data/botdesk.log"
    ;;
  off)
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    rm -f "$PLIST"
    echo "botdesk autostart removed."
    ;;
  status)
    launchctl print "$DOMAIN/$LABEL" 2>/dev/null | grep -E "state|pid" || echo "not running"
    ;;
  *) echo "usage: $0 on|off|status"; exit 1 ;;
esac
