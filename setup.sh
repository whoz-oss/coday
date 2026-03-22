#!/usr/bin/env bash
# setup.sh — project setup script
# Checks required binaries are available, then installs dependencies.
# Works both in the main clone and in git worktrees.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Dependency checks ---

missing=()

check_binary() {
  local name="$1"
  if ! command -v "$name" &>/dev/null; then
    missing+=("$name")
  else
    echo "  ✓ $name"
  fi
}

echo "Checking required binaries..."
check_binary node
check_binary pnpm
check_binary java

if [ ${#missing[@]} -gt 0 ]; then
  echo ""
  echo "✗ Missing required binaries: ${missing[*]}"
  echo "  Please install them and ensure they are on your PATH before running setup."
  exit 1
fi

# --- Install dependencies ---

echo ""
GIT_ENTRY="$PROJECT_ROOT/.git"
# Note: in a git worktree, .git is a file (pointer) rather than a directory.
# We run pnpm install --frozen-lockfile in both cases.
# TODO: once pnpm enableGlobalVirtualStore supports ESM (or this project drops ESM),
# worktree installs could be made near-instant by symlinking to a shared global virtual store.
if [ -f "$GIT_ENTRY" ]; then
  echo "Installing dependencies (git worktree, frozen lockfile)..."
else
  echo "Installing dependencies (frozen lockfile)..."
fi

cd "$PROJECT_ROOT"
pnpm install --frozen-lockfile

echo ""
echo "Setup complete."
