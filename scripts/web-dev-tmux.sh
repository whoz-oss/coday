#!/usr/bin/env bash
# Start the full Coday dev stack in dedicated tmux sessions.
#
# Three processes are started, each in its own tmux session:
#   1. AgentOS (Spring Boot)  — session: agentos_<branch>_<port>
#   2. Express server (Node)  — session: server_<branch>_<port>
#   3. Angular client         — session: client_<branch>_<port>
#
# Port allocation: scan upward from each START_*_PORT until a free port is
# found. Ports are passed between processes via environment variables so that
# Express knows where to proxy AgentOS requests.
#
# Each worktree gets its own sessions (branch slug + port in name) so multiple
# worktrees on the same machine can run simultaneously without conflict.
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
            Session name: agentos_<branch>_<AGENTOS_PORT>

  Server    Express/Node proxy server. Proxies /api/agentos/* to AgentOS.
            Ready signal in logs: "Server is running on http://localhost:<port>"
            Session name: server_<branch>_<SERVER_PORT>

  Client    Angular dev server with HMR.
            Ready signal in logs: "Local: http://localhost:<port>"
            Session name: client_<branch>_<CLIENT_PORT>

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
  pnpm run web:dev:tmux                       # start the full stack
  tmux attach -t agentos_<branch>_<port>      # attach to AgentOS logs
  tmux attach -t server_<branch>_<port>       # attach to server logs
  tmux attach -t client_<branch>_<port>       # attach to client logs
  tmux kill-session -t <session>              # stop a process
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

# Extract last segment of current git branch, truncated to 12 chars
# Falls back to DIR_NAME if not in a git repo or branch is detached
BRANCH_RAW=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
if [[ -n "${BRANCH_RAW}" && "${BRANCH_RAW}" != "HEAD" ]]; then
  BRANCH_SLUG="$(basename "${BRANCH_RAW}" | cut -c1-12)"
else
  BRANCH_SLUG="${DIR_NAME}"
fi

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

SESSION_AGENTOS="agentos_${BRANCH_SLUG}_${AGENTOS_PORT}"
SESSION_SERVER="server_${BRANCH_SLUG}_${SERVER_PORT}"
SESSION_CLIENT="client_${BRANCH_SLUG}_${CLIENT_PORT}"

# ---------------------------------------------------------------------------
# Summary (human + machine readable)
# ---------------------------------------------------------------------------
echo "Working directory : $(pwd)"
echo "AGENTOS_PORT=${AGENTOS_PORT}"
echo "SERVER_PORT=${SERVER_PORT}"
echo "CLIENT_PORT=${CLIENT_PORT}"
echo
echo "AgentOS session   : ${SESSION_AGENTOS}"
echo "Server session    : ${SESSION_SERVER}"
echo "Client session    : ${SESSION_CLIENT}"

# ---------------------------------------------------------------------------
# Kill any existing sessions for this worktree (any port variant)
# A previous run may have used a different port, leaving a stale session that
# still holds the Neo4j store_lock. Match on branch slug prefix to catch all.
# ---------------------------------------------------------------------------
for PREFIX in "agentos_${BRANCH_SLUG}_" "server_${BRANCH_SLUG}_" "client_${BRANCH_SLUG}_"; do
  tmux list-sessions -F '#{session_name}' 2>/dev/null \
    | grep "^${PREFIX}" \
    | while read -r SESSION; do
        echo "Killing existing session '${SESSION}'..."
        tmux kill-session -t "${SESSION}"
      done
done

# ---------------------------------------------------------------------------
# Session: AgentOS (Spring Boot)
# Plugins must be deployed before starting — run deploy-plugins.sh first.
# bootRun is invoked via pnpm nx from the worktree root.
# ---------------------------------------------------------------------------
ROOT_DIR="$(pwd)"
tmux new-session -d -s "${SESSION_AGENTOS}" \
  "cd '${ROOT_DIR}' && SERVER_PORT=${AGENTOS_PORT} pnpm nx bootRun agentos-service"

# ---------------------------------------------------------------------------
# Session: Express server
# AGENTOS_PORT — tells the server where to proxy /api/agentos/* requests
# PORT         — the port the Express server binds to
# ANGULAR_CLIENT_PORT — tells the server where to proxy Angular HMR requests
# ---------------------------------------------------------------------------
tmux new-session -d -s "${SESSION_SERVER}" \
  "AGENTOS_PORT=${AGENTOS_PORT} PORT=${SERVER_PORT} ANGULAR_CLIENT_PORT=${CLIENT_PORT} BUILD_ENV=development pnpm nx run server:serve"

# ---------------------------------------------------------------------------
# Session: Angular dev server
# ---------------------------------------------------------------------------
tmux new-session -d -s "${SESSION_CLIENT}" \
  "pnpm nx run client:serve --port=${CLIENT_PORT}"

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo
echo "Started. Open: http://localhost:${SERVER_PORT}"
echo "Attach with : tmux attach -t ${SESSION_SERVER}"
echo "Stop with   : tmux kill-session -t ${SESSION_AGENTOS} && tmux kill-session -t ${SESSION_SERVER} && tmux kill-session -t ${SESSION_CLIENT}"
