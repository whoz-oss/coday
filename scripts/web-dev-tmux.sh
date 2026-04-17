#!/usr/bin/env bash
# Start the full Coday dev stack in dedicated tmux sessions.
#
# Three processes are started:
#   1. AgentOS (Spring Boot)  — session: agentos-<dir>-<port>
#   2. Express server (Node)  — session: coday-dev-<dir>-<port>
#   3. Angular client         — window in the Express session
#
# Port allocation: scan upward from each START_*_PORT until a free port is
# found. Ports are passed between processes via environment variables so that
# Express knows where to proxy AgentOS requests.
#
# Each worktree gets its own sessions named after its directory, so multiple
# worktrees on the same machine can run simultaneously without conflict.
# Each AgentOS instance gets its own data directory (derived from its port)
# so that embedded Neo4j databases do not collide.
#
# Usage:
#   pnpm run web:dev:tmux          # start the full stack
#   bash scripts/web-dev-tmux.sh --help   # show this help

set -euo pipefail

# ---------------------------------------------------------------------------
# Help
# ---------------------------------------------------------------------------
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'EOF'
web-dev-tmux.sh — Start the full Coday dev stack

USAGE
  bash scripts/web-dev-tmux.sh [--help]

PROCESSES STARTED
  AgentOS   Spring Boot backend. Gradle task deployPlugins runs first to build
            and copy plugin JARs into agentos/plugins/. Ready signal in logs:
            "Started AgentOSApplication".
            Session name: agentos-<dir>-<AGENTOS_PORT>

  Server    Express/Node proxy server. Proxies /api/agentos/* to AgentOS.
            Ready signal in logs: "Server is running on http://localhost:<port>"
            Session name: coday-dev-<dir>-<SERVER_PORT>  (window: server)

  Client    Angular dev server with HMR.
            Ready signal in logs: "Local: http://localhost:<port>"
            Session name: same as Server  (window: client)

PORT ALLOCATION
  AGENTOS_PORT  scans upward from 8123
  SERVER_PORT   scans upward from 4100
  CLIENT_PORT   scans upward from 5100

OUTPUT
  The script prints the allocated ports in a machine-readable block:
    AGENTOS_PORT=<n>
    SERVER_PORT=<n>
    CLIENT_PORT=<n>
  This block can be parsed by Daemonay or other scripts.

EXAMPLES
  pnpm run web:dev:tmux          # start the full stack
  tmux attach -t agentos-<dir>-<port>     # attach to AgentOS logs
  tmux attach -t coday-dev-<dir>-<port>   # attach to server/client logs
  tmux kill-session -t <session>          # stop a process
EOF
  exit 0
fi

# ---------------------------------------------------------------------------
# Port discovery
# ---------------------------------------------------------------------------
START_AGENTOS_PORT=8123
START_SERVER_PORT=4100
START_CLIENT_PORT=5100

# Unset TMUX so we can create a new top-level session even when called from
# inside an existing tmux session (e.g. via the Coday preview panel).
unset TMUX

DIR_NAME="$(basename "$(pwd)")"

find_free_port() {
  local port=$1
  while lsof -ti :"${port}" >/dev/null 2>&1; do
    port=$(( port + 1 ))
  done
  echo "${port}"
}

AGENTOS_PORT=$(find_free_port "${START_AGENTOS_PORT}")
SERVER_PORT=$(find_free_port "${START_SERVER_PORT}")
CLIENT_PORT=$(find_free_port "${START_CLIENT_PORT}")

SESSION_AGENTOS="agentos-${DIR_NAME}-${AGENTOS_PORT}"
SESSION_WEB="coday-dev-${DIR_NAME}-${SERVER_PORT}"

# ---------------------------------------------------------------------------
# Summary (human + machine readable)
# ---------------------------------------------------------------------------
echo "Working directory : $(pwd)"
echo "AGENTOS_PORT=${AGENTOS_PORT}"
echo "SERVER_PORT=${SERVER_PORT}"
echo "CLIENT_PORT=${CLIENT_PORT}"
echo
echo "AgentOS session   : ${SESSION_AGENTOS}"
echo "Web session       : ${SESSION_WEB}"

# ---------------------------------------------------------------------------
# Kill any existing sessions for this worktree
# ---------------------------------------------------------------------------
for SESSION in "${SESSION_AGENTOS}" "${SESSION_WEB}"; do
  if tmux has-session -t "${SESSION}" 2>/dev/null; then
    echo "Killing existing session '${SESSION}'..."
    tmux kill-session -t "${SESSION}"
  fi
done

# ---------------------------------------------------------------------------
# Window 0: AgentOS (Spring Boot)
# deployPlugins builds plugins and copies JARs to agentos/plugins/ before start.
# Working dir is set to agentos/ by the bootRun Gradle task configuration.
# ---------------------------------------------------------------------------
tmux new-session -d -s "${SESSION_AGENTOS}" -n agentos \
  "cd agentos && ./gradlew deployPlugins bootRun --args='--server.port=${AGENTOS_PORT}'"

# ---------------------------------------------------------------------------
# Window 0: Express server
# AGENTOS_PORT — tells the server where to proxy /api/agentos/* requests
# PORT         — the port the Express server binds to
# ANGULAR_CLIENT_PORT — tells the server where to proxy Angular HMR requests
# ---------------------------------------------------------------------------
tmux new-session -d -s "${SESSION_WEB}" -n server \
  "AGENTOS_PORT=${AGENTOS_PORT} PORT=${SERVER_PORT} ANGULAR_CLIENT_PORT=${CLIENT_PORT} BUILD_ENV=development pnpm nx run server:serve"

# Window 1: Angular dev server
tmux new-window -t "${SESSION_WEB}" -n client \
  "pnpm nx run client:serve --port=${CLIENT_PORT}"

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo
echo "Started. Open: http://localhost:${SERVER_PORT}"
echo "Attach with : tmux attach -t ${SESSION_WEB}"
echo "Stop with   : tmux kill-session -t ${SESSION_AGENTOS} && tmux kill-session -t ${SESSION_WEB}"
