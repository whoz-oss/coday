#!/bin/bash
# ---------------------------------------------------------------------------
# reset-dev-config.sh
# ---------------------------------------------------------------------------
# Resets the CodayTwin project config so you can test the setup flow without
# reinstalling the packaged app.
#
# Usage:
#   bash apps/desktop-twin/macos/scripts/reset-dev-config.sh [--all]
#
# Without --all: only removes project.yaml (forces template re-creation).
# With    --all: also removes preferences.json (forces full first-launch setup).
# ---------------------------------------------------------------------------

set -e

CODAY_PROJECT_DIR="$HOME/.coday/projects/CodayTwin"
PROJECT_YAML="$CODAY_PROJECT_DIR/project.yaml"

# Electron stores userData in ~/Library/Application Support/CodayTwin (packaged)
# or ~/Library/Application Support/Electron (dev / serve target).
ELECTRON_DEV_PREFS="$HOME/Library/Application Support/Electron/preferences.json"
ELECTRON_APP_PREFS="$HOME/Library/Application Support/CodayTwin/preferences.json"

echo "=== CodayTwin dev config reset ==="

# --- project.yaml ---
if [ -f "$PROJECT_YAML" ]; then
  echo "Removing: $PROJECT_YAML"
  rm "$PROJECT_YAML"
else
  echo "Already absent: $PROJECT_YAML"
fi

# --- preferences.json (only with --all) ---
if [ "$1" = "--all" ]; then
  for PREFS in "$ELECTRON_DEV_PREFS" "$ELECTRON_APP_PREFS"; do
    if [ -f "$PREFS" ]; then
      echo "Removing: $PREFS"
      rm "$PREFS"
    else
      echo "Already absent: $PREFS"
    fi
  done
  echo "Full reset done — next launch will show the setup wizard."
else
  echo "Partial reset done — project.yaml will be recreated from template on next launch."
  echo "Tip: pass --all to also clear preferences (triggers full setup wizard)."
fi

echo ""
echo "Now run:  nx run desktop-twin:serve"
