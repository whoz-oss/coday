#!/usr/bin/env bash
# Build all AgentOS plugins and deploy their JARs to agentos/plugins/.
#
# Must be run from the worktree root (where agentos/ directory lives).
#
# Usage:
#   bash scripts/deploy-plugins.sh
#   bash scripts/deploy-plugins.sh --help

set -euo pipefail

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'EOF'
deploy-plugins.sh — Build and deploy AgentOS plugin JARs

USAGE
  bash scripts/deploy-plugins.sh

DESCRIPTION
  Runs the Gradle `deployPlugins` task from the agentos/ composite build root.
  This builds all plugin included builds (agentos-plugins-filesystem,
  agentos-datetime-plugin) and copies their JARs into agentos/plugins/.

  Must be called before starting AgentOS if you want plugins to be loaded.
  Run from the worktree root.

OUTPUT
  Prints each deployed JAR on success:
    Deployed: <name>.jar → plugins/
  Exits with code 1 on failure.
EOF
  exit 0
fi

AGENTOS_DIR="$(cd "$(dirname "$0")/../agentos" && pwd)"

echo "Deploying plugins from ${AGENTOS_DIR}..."
cd "${AGENTOS_DIR}"
./gradlew deployPlugins
echo "Done."
