#!/usr/bin/env bash
# Start the full Coday dev stack in dedicated tmux sessions.
#
# Three processes are started, each in its own tmux session:
#   1. AgentOS (Spring Boot)  — session: agentos_<branch>_<port>
#   2. Express server (Node)  — session: server_<branch>_<port>
#   3. Angular client         — session: client_<branch>_<port>
#
# Port allocation: the DEV profile of the Coday port scheme. The numbers (dev base, block size, step, offsets) are NOT
# written here: they are read from apps/server/src/lib/runtime/port-scheme.json, the single source of truth.
# A block of ten ports per stack with the same offsets everywhere:
#   +0 Express (the only address to open)  +1 Angular client and +2 Factory cockpit (internal dev servers, reached
#   through Express)  +5 AgentOS  +6 Factory  +7 AgentOS Bolt
# The first block whose role ports are all free is used (dev base, then one step further...: several worktrees side
# by side), so a stack never lands on the packaged ones (prod / local, 3xxx). Ports are passed between processes via
# environment variables so that Express knows where to proxy.
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
  DEV block of the port scheme (apps/server/src/lib/runtime/port-scheme.json, read at start): one
  block of ten ports per stack, the first one with free role ports (dev base, then one block further...).
    SERVER_PORT          base+0  Express (the address to open)
    CLIENT_PORT          base+1  Angular client (internal, reached through Express)
    FACTORY_COCKPIT_PORT base+2  Factory cockpit (Angular, internal, reached through Express)
    AGENTOS_PORT         base+5  AgentOS (Spring), embedded Neo4j Bolt on base+7
    FACTORY_PORT         base+6  Factory (Spring; reserved, not started by this script)

OUTPUT
  The script prints the allocated ports in a machine-readable block:
    AGENTOS_PORT=<n>
    SERVER_PORT=<n>
    CLIENT_PORT=<n>
    FACTORY_COCKPIT_PORT=<n>
    FACTORY_PORT=<n>
    BOLT_PORT=<n>
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
# Port discovery: the DEV block of the port scheme, read from the JSON (see the header)
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT_SCHEME_FILE="${SCRIPT_DIR}/../apps/server/src/lib/runtime/port-scheme.json"
if [[ ! -f "${PORT_SCHEME_FILE}" ]]; then
  echo "Port scheme not found: ${PORT_SCHEME_FILE}" >&2
  exit 1
fi
# node is always present in this repo: one call prints the numbers as shell assignments (trusted file of the repo).
eval "$(node -e '
  const s = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"))
  const out = {
    DEV_PORT_BASE: s.profiles.dev.base,
    DEV_BLOCK_STEP: s.profiles.dev.step,
    PORT_BLOCK_SIZE: s.blockSize,
    OFFSET_SERVER: s.offsets.server,
    OFFSET_CLIENT: s.offsets.client,
    OFFSET_COCKPIT: s.offsets.factoryCockpit,
    OFFSET_AGENTOS: s.offsets.agentos,
    OFFSET_FACTORY: s.offsets.factory,
    OFFSET_BOLT: s.offsets.bolt,
    // Candidate blocks stay in the thousand of the dev base.
    MAX_BLOCKS: Math.floor((Math.floor(s.profiles.dev.base / 1000) * 1000 + 1000 - s.blockSize - s.profiles.dev.base) / s.profiles.dev.step) + 1,
  }
  for (const [k, v] of Object.entries(out)) console.log(`${k}=${Number(v)}`)
' "${PORT_SCHEME_FILE}")"

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

port_is_free() {
  ! lsof -ti :"$1" >/dev/null 2>&1
}

# A block is usable when every role port of it is free.
block_is_free() {
  local base=$1 offset
  for offset in "${OFFSET_SERVER}" "${OFFSET_CLIENT}" "${OFFSET_COCKPIT}" "${OFFSET_AGENTOS}" "${OFFSET_FACTORY}" "${OFFSET_BOLT}"; do
    port_is_free $(( base + offset )) || return 1
  done
}

PORT_BASE=""
for (( i = 0; i < MAX_BLOCKS; i++ )); do
  candidate=$(( DEV_PORT_BASE + i * DEV_BLOCK_STEP ))
  if block_is_free "${candidate}"; then
    PORT_BASE=${candidate}
    break
  fi
  echo "Port block ${candidate}-$(( candidate + PORT_BLOCK_SIZE - 1 )) is taken, trying the next one" >&2
done
if [[ -z "${PORT_BASE}" ]]; then
  echo "No free port block between ${DEV_PORT_BASE} and $(( DEV_PORT_BASE + MAX_BLOCKS * DEV_BLOCK_STEP - 1 )): stop another dev stack first." >&2
  exit 1
fi

SERVER_PORT=$(( PORT_BASE + OFFSET_SERVER ))
CLIENT_PORT=$(( PORT_BASE + OFFSET_CLIENT ))
COCKPIT_PORT=$(( PORT_BASE + OFFSET_COCKPIT ))
AGENTOS_PORT=$(( PORT_BASE + OFFSET_AGENTOS ))
FACTORY_PORT=$(( PORT_BASE + OFFSET_FACTORY ))
BOLT_PORT=$(( PORT_BASE + OFFSET_BOLT ))

SESSION_AGENTOS="agentos_${BRANCH_SLUG}_${AGENTOS_PORT}"
SESSION_SERVER="server_${BRANCH_SLUG}_${SERVER_PORT}"
SESSION_CLIENT="client_${BRANCH_SLUG}_${CLIENT_PORT}"
SESSION_FACTORY="factory_${BRANCH_SLUG}_${COCKPIT_PORT}"

# ---------------------------------------------------------------------------
# Summary (human + machine readable)
# ---------------------------------------------------------------------------
echo "Working directory : $(pwd)"
echo "Port block        : ${PORT_BASE}-$(( PORT_BASE + PORT_BLOCK_SIZE - 1 ))"
echo "AGENTOS_PORT=${AGENTOS_PORT}"
echo "SERVER_PORT=${SERVER_PORT}"
echo "CLIENT_PORT=${CLIENT_PORT}"
echo "FACTORY_COCKPIT_PORT=${COCKPIT_PORT}"
echo "FACTORY_PORT=${FACTORY_PORT}"
echo "BOLT_PORT=${BOLT_PORT}"
echo
echo "AgentOS session   : ${SESSION_AGENTOS}"
echo "Server session    : ${SESSION_SERVER}"
echo "Client session    : ${SESSION_CLIENT}"
echo "Cockpit session   : ${SESSION_FACTORY}"

# ---------------------------------------------------------------------------
# Kill any existing sessions for this worktree (any port variant)
# A previous run may have used a different port, leaving a stale session that
# still holds the Neo4j store_lock. Match on branch slug prefix to catch all.
# ---------------------------------------------------------------------------
for PREFIX in "agentos_${BRANCH_SLUG}_" "server_${BRANCH_SLUG}_" "client_${BRANCH_SLUG}_" "factory_${BRANCH_SLUG}_"; do
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
  "cd '${ROOT_DIR}' && SERVER_PORT=${AGENTOS_PORT} AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT=${BOLT_PORT} SPRING_NEO4J_URI=bolt://localhost:${BOLT_PORT} pnpm nx bootRun agentos-service"

# ---------------------------------------------------------------------------
# Session: Express server
# AGENTOS_URL / FACTORY_URL — where to proxy /api/agentos/* and /api/factory/* (the services of this block;
#                       the Factory is reserved but not started here: external mode means nothing is downloaded)
# PORT                — the port the Express server binds to (strict)
# CODAY_PORT_BASE     — the block of this stack (strict: the dev profile never moves to another block by itself)
# ANGULAR_CLIENT_PORT / FACTORY_COCKPIT_PORT — where to proxy the Angular dev servers
# ---------------------------------------------------------------------------
tmux new-session -d -s "${SESSION_SERVER}" \
  "AGENTOS_URL=http://localhost:${AGENTOS_PORT} FACTORY_URL=http://localhost:${FACTORY_PORT} PORT=${SERVER_PORT} CODAY_PORT_BASE=${PORT_BASE} ANGULAR_CLIENT_PORT=${CLIENT_PORT} FACTORY_COCKPIT_PORT=${COCKPIT_PORT} BUILD_ENV=development pnpm nx run server:serve"

# ---------------------------------------------------------------------------
# Session: Angular dev server (internal: reached through Express; CODAY_PORT_BASE lets proxy.conf.mjs target the
# AgentOS and Express of THIS block)
# ---------------------------------------------------------------------------
tmux new-session -d -s "${SESSION_CLIENT}" \
  "CODAY_PORT_BASE=${PORT_BASE} pnpm nx run client:serve --port=${CLIENT_PORT}"

# ---------------------------------------------------------------------------
# Session: Factory cockpit dev server (baseHref /factory/, reached via Express)
# ---------------------------------------------------------------------------
tmux new-session -d -s "${SESSION_FACTORY}" \
  "pnpm nx run factory-cockpit:serve --port=${COCKPIT_PORT}"

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo
echo "Started. Open: http://localhost:${SERVER_PORT}/   (Express; client ${CLIENT_PORT} and cockpit ${COCKPIT_PORT} are internal dev servers)"
echo "Attach with : tmux attach -t ${SESSION_SERVER}"
echo "Cockpit     : http://localhost:${SERVER_PORT}/factory/"
echo "Stop with   : tmux kill-session -t ${SESSION_AGENTOS} && tmux kill-session -t ${SESSION_SERVER} && tmux kill-session -t ${SESSION_CLIENT} && tmux kill-session -t ${SESSION_FACTORY}"
