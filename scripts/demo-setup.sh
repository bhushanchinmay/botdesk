#!/usr/bin/env bash
# Creates a demo git repo from examples/shop-cart and attaches it. Safe to run again.
set -euo pipefail
cd "$(dirname "$0")/.."

DEMO="demo/shop-cart"
if [[ ! -d "$DEMO/.git" ]]; then
  mkdir -p demo && cp -r examples/shop-cart "$DEMO"
  git -C "$DEMO" init -q -b main
  git -C "$DEMO" add -A
  git -C "$DEMO" -c user.name="demo" -c user.email="demo@localhost" commit -q -m "Initial shop-cart"
fi
./scripts/attach.sh "$DEMO" shop-cart
echo
echo "Demo ready. Try a task: project shop-cart, kind bugfix,"
echo "title: 'SAVE10 takes 19% off instead of 10%'."
