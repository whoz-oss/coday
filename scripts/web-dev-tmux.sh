#!/usr/bin/env bash
# Start the full Coday dev stack in dedicated tmux sessions.
#
# Five processes are started, each in its own tmux session:
#   1. AgentOS (Spring Boot)  — session: agentos_<branch>_<port>
#   2. Express server (Node)  — session: server_<branch>_<port>
#   3. Angular client         — session: client_<branch>_<port>
#   4. Factory cockpit        — session: cockpit_<branch>_<port>
#   5. Factory (Spring Boot)  — session: factory_<branch>_<port>
#
# Port allocation: the DEV profile of the Coday port scheme. The numbers (dev base, block size, step, offsets) are NOT
# written here: they are read from apps/server/src/lib/runtime/port-scheme.json, the single source of truth.
# A block of ten ports per stack with the same offsets everywhere:
#   +0 Express (the only address to open)  +1 Angular client and +2 Factory cockpit (internal dev servers, reached
#   through Express)  +5 AgentOS  +6 Factory  +7 AgentOS Bolt
# The first block whose role ports are all free is used unless --port-base pins a block. Ports are passed between processes via
# environment variables so that Express knows where to proxy.
#
# Each worktree gets its own sessions (branch slug + port in name) so multiple
# worktrees on the same machine can run simultaneously without conflict.
#
# Usage:
#   pnpm run web:dev:tmux          # start the full stack
#   bash scripts/web-dev-tmux.sh --help   # show this help
#   bash scripts/web-dev-tmux.sh --port-base 4100 --config-dir /path/to/config

set -euo pipefail

# ---------------------------------------------------------------------------
# Help
# ---------------------------------------------------------------------------
if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'EOF'
web-dev-tmux.sh — Start the full Coday dev stack

USAGE
  bash scripts/web-dev-tmux.sh [--port-base <dev-block>] [--config-dir <absolute-path>] [--dry-run]
  bash scripts/web-dev-tmux.sh --help

PROCESSES STARTED
  AgentOS   Spring Boot backend. The script first builds its plugins into agentos/plugins/.
            Ready signal in logs:
            "Started AgentOSApplication".
            Session name: agentos_<branch>_<AGENTOS_PORT>

  Server    Express/Node proxy server. Proxies /api/agentos/* to AgentOS.
            Ready signal in logs: "Server is running on http://localhost:<port>"
            Session name: server_<branch>_<SERVER_PORT>

  Client    Angular dev server with HMR.
            Ready signal in logs: "Local: http://localhost:<port>"
            Session name: client_<branch>_<CLIENT_PORT>
  Cockpit   Angular dev server with HMR, reached via Express /factory/.
            Session name: cockpit_<branch>_<FACTORY_COCKPIT_PORT>
  Factory   Spring Boot backend. The script first builds its plugins into factory/plugins/.
            Ready signal in logs: "Started FactoryServiceApplication".
            Session name: factory_<branch>_<FACTORY_PORT>

PORT ALLOCATION
  DEV block of the port scheme (apps/server/src/lib/runtime/port-scheme.json, read at start): one
  block of ten ports per stack, the first one with free role ports (dev base, then one block further...).
    SERVER_PORT          base+0  Express (the address to open)
    CLIENT_PORT          base+1  Angular client (internal, reached through Express)
    FACTORY_COCKPIT_PORT base+2  Factory cockpit (Angular, internal, reached through Express)
    AGENTOS_PORT         base+5  AgentOS (Spring), embedded Neo4j Bolt on base+7
    FACTORY_PORT         base+6  Factory (Spring)

OPTIONS
  --port-base <n>  Use this dev block exactly; fail if any of its role ports is occupied.
  --config-dir <absolute-path>
                   Reuse AgentOS/Factory data and AgentOS exchange under this config directory.
                   Omit for an isolated, persistent .coday-dev/ directory in this checkout.
                   Stop the existing services before reusing their embedded Neo4j databases.
  --dry-run        Print the selected paths and ports without building or starting anything.

OUTPUT
  The script prints the allocated ports in a machine-readable block:
    AGENTOS_PORT=<n>
    SERVER_PORT=<n>
    CLIENT_PORT=<n>
    FACTORY_COCKPIT_PORT=<n>
    FACTORY_PORT=<n>
    BOLT_PORT=<n>
  This block can be parsed by DaemonManager or other scripts.

EXAMPLES
  pnpm run web:dev:tmux                       # start the full stack
  tmux attach -t agentos_<branch>_<port>      # attach to AgentOS logs
  tmux attach -t server_<branch>_<port>       # attach to server logs
  tmux attach -t client_<branch>_<port>       # attach to client logs
  tmux kill-session -t <session>              # stop a process
EOF
  exit 0
fi

EXPLICIT_BASE=""
CONFIG_DIR=""
DRY_RUN=false
while (( $# > 0 )); do
  case "$1" in
    --port-base|--config-dir)
      if (( $# < 2 )) || [[ -z "$2" ]]; then
        echo "Missing value for $1" >&2
        exit 2
      fi
      if [[ "$1" == "--port-base" ]]; then EXPLICIT_BASE="$2"; else CONFIG_DIR="$2"; fi
      shift 2
      ;;
    --dry-run) DRY_RUN=true; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

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

ROOT_DIR="$(pwd)"
DIR_NAME="$(basename "$ROOT_DIR")"
if [[ -z "$CONFIG_DIR" ]]; then
  CONFIG_DIR="$ROOT_DIR/.coday-dev"
elif [[ "$CONFIG_DIR" != /* || ! -d "$CONFIG_DIR" ]]; then
  echo "--config-dir must name an existing absolute directory: $CONFIG_DIR" >&2
  exit 2
fi
CONFIG_DIR="$(cd "$(dirname "$CONFIG_DIR")" && pwd)/$(basename "$CONFIG_DIR")"

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
if [[ -n "$EXPLICIT_BASE" ]]; then
  if [[ ! "$EXPLICIT_BASE" =~ ^[0-9]+$ ]] || (( EXPLICIT_BASE < DEV_PORT_BASE || (EXPLICIT_BASE - DEV_PORT_BASE) % DEV_BLOCK_STEP != 0 || EXPLICIT_BASE >= DEV_PORT_BASE + MAX_BLOCKS * DEV_BLOCK_STEP )); then
    echo "--port-base must select a DEV block from the port scheme: $EXPLICIT_BASE" >&2
    exit 2
  fi
  PORT_BASE="$EXPLICIT_BASE"
  if [[ "$DRY_RUN" == false ]] && ! block_is_free "$PORT_BASE"; then
    echo "Port block $PORT_BASE-$(( PORT_BASE + PORT_BLOCK_SIZE - 1 )) is occupied; stop its stack before starting this one." >&2
    exit 1
  fi
else
  for (( i = 0; i < MAX_BLOCKS; i++ )); do
    candidate=$(( DEV_PORT_BASE + i * DEV_BLOCK_STEP ))
    if block_is_free "${candidate}"; then
      PORT_BASE=${candidate}
      break
    fi
    echo "Port block ${candidate}-$(( candidate + PORT_BLOCK_SIZE - 1 )) is taken, trying the next one" >&2
  done
fi
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
SESSION_COCKPIT="cockpit_${BRANCH_SLUG}_${COCKPIT_PORT}"
SESSION_FACTORY="factory_${BRANCH_SLUG}_${FACTORY_PORT}"

# ---------------------------------------------------------------------------
# Summary (human + machine readable)
# ---------------------------------------------------------------------------
echo "Working directory : $(pwd)"
echo "Config directory   : ${CONFIG_DIR}"
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
echo "Cockpit session   : ${SESSION_COCKPIT}"
echo "Factory session   : ${SESSION_FACTORY}"

# ---------------------------------------------------------------------------
# Refuse to replace a running session. DaemonManager must inspect and stop it
# explicitly, especially when a service holds an embedded Neo4j store lock.
# ---------------------------------------------------------------------------
if [[ "$DRY_RUN" == true ]]; then
  echo "Dry run: no plugins built, sessions started or services stopped."
  exit 0
fi

for PREFIX in "agentos_${BRANCH_SLUG}_" "server_${BRANCH_SLUG}_" "client_${BRANCH_SLUG}_" "cockpit_${BRANCH_SLUG}_" "factory_${BRANCH_SLUG}_"; do
  while read -r SESSION; do
    if [[ "$SESSION" == "$PREFIX"* ]]; then
      echo "Existing session '$SESSION' may own this checkout; inspect and stop it explicitly before starting." >&2
      exit 1
    fi
  done < <(tmux list-sessions -F '#{session_name}' 2>/dev/null || true)
done

mkdir -p "$CONFIG_DIR/agentos/data/database" "$CONFIG_DIR/agentos/data/exchange" "$CONFIG_DIR/factory/data/database"

# Both services load plugins only at startup. Build the checkout's plugin JARs
# before starting either backend, so the running code matches this checkout.
(cd "$ROOT_DIR/agentos" && ./gradlew deployPlugins)
(cd "$ROOT_DIR/factory" && ./gradlew deployPlugins)

# ---------------------------------------------------------------------------
# Session: AgentOS (Spring Boot)
# Plugins were built by the preflight above.
# bootRun is invoked via pnpm nx from the worktree root.
# ---------------------------------------------------------------------------
printf -v AGENTOS_COMMAND 'cd %q && SERVER_PORT=%q SERVER_ADDRESS=127.0.0.1 AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT=%q SPRING_NEO4J_URI=%q AGENTOS_PERSISTENCE_DATA_DIR=%q AGENTOS_EXCHANGE_MOUNT_ROOT=%q AGENTOS_PLUGINS_DIR=%q AGENTOS_OAUTH_REDIRECT_URI=%q FACTORY_PORT=%q pnpm nx bootRun agentos-service' \
  "$ROOT_DIR" "$AGENTOS_PORT" "$BOLT_PORT" "bolt://localhost:$BOLT_PORT" "$CONFIG_DIR/agentos/data/database" "$CONFIG_DIR/agentos/data/exchange" "$ROOT_DIR/agentos/plugins" "http://localhost:$SERVER_PORT/oauth/callback" "$FACTORY_PORT"
tmux new-session -d -s "${SESSION_AGENTOS}" "$AGENTOS_COMMAND"

# ---------------------------------------------------------------------------
# Session: Express server
# AGENTOS_URL / FACTORY_URL — where to proxy /api/agentos/* and /api/factory/* (the services of this block)
# PORT                — the port the Express server binds to (strict)
# CODAY_PORT_BASE     — the block of this stack (strict: the dev profile never moves to another block by itself)
# ANGULAR_CLIENT_PORT / FACTORY_COCKPIT_PORT — where to proxy the Angular dev servers
# ---------------------------------------------------------------------------
printf -v SERVER_COMMAND 'cd %q && AGENTOS_URL=%q FACTORY_URL=%q PORT=%q CODAY_PORT_BASE=%q ANGULAR_CLIENT_PORT=%q FACTORY_COCKPIT_PORT=%q BUILD_ENV=development pnpm exec tsx watch apps/server/src/server.ts --configDir %q' \
  "$ROOT_DIR" "http://localhost:$AGENTOS_PORT" "http://localhost:$FACTORY_PORT" "$SERVER_PORT" "$PORT_BASE" "$CLIENT_PORT" "$COCKPIT_PORT" "$CONFIG_DIR"
tmux new-session -d -s "${SESSION_SERVER}" "$SERVER_COMMAND"

# ---------------------------------------------------------------------------
# Session: Angular dev server (internal: reached through Express; CODAY_PORT_BASE lets proxy.conf.mjs target the
# AgentOS and Express of THIS block)
# ---------------------------------------------------------------------------
printf -v CLIENT_COMMAND 'cd %q && CODAY_PORT_BASE=%q pnpm nx run client:serve --port=%q' "$ROOT_DIR" "$PORT_BASE" "$CLIENT_PORT"
tmux new-session -d -s "${SESSION_CLIENT}" "$CLIENT_COMMAND"

# ---------------------------------------------------------------------------
# Session: Factory cockpit dev server (baseHref /factory/, reached via Express)
# ---------------------------------------------------------------------------
printf -v COCKPIT_COMMAND 'cd %q && pnpm nx run factory-cockpit:serve --port=%q' "$ROOT_DIR" "$COCKPIT_PORT"
tmux new-session -d -s "${SESSION_COCKPIT}" "$COCKPIT_COMMAND"

# Factory backend on the same block, using the same persistent data root and
# the freshly built Forge plugin from this checkout.
printf -v FACTORY_COMMAND 'cd %q && SERVER_PORT=%q SERVER_ADDRESS=127.0.0.1 AGENTOS_URL=%q FACTORY_PERSISTENCE_DATA_DIR=%q FACTORY_PLUGINS_DIR=%q pnpm nx bootRun factory-service' \
  "$ROOT_DIR" "$FACTORY_PORT" "http://localhost:$AGENTOS_PORT" "$CONFIG_DIR/factory/data/database" "$ROOT_DIR/factory/plugins"
tmux new-session -d -s "${SESSION_FACTORY}" "$FACTORY_COMMAND"

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo
echo "Started. Open: http://localhost:${SERVER_PORT}/   (Express; client ${CLIENT_PORT} and cockpit ${COCKPIT_PORT} are internal dev servers)"
echo "Attach with : tmux attach -t ${SESSION_SERVER}"
echo "Cockpit     : http://localhost:${SERVER_PORT}/factory/"
echo "Sessions    : ${SESSION_AGENTOS} ${SESSION_SERVER} ${SESSION_CLIENT} ${SESSION_COCKPIT} ${SESSION_FACTORY}"
