#!/usr/bin/env bash
# Start the Coday dev stack (client + server) in a dedicated tmux session.
#
# Each worktree gets its own session named after its directory, so multiple
# worktrees on the same machine can run simultaneously without conflict.
#
# Port allocation: scan upward from START_SERVER_PORT / START_CLIENT_PORT
# until a free port is found for each. The server logs the URL it binds to,
# which the preview panel reads back via `tmux capture-pane`.
#
# Usage: pnpm run web:dev:tmux

set -euo pipefail

START_SERVER_PORT=4100
START_CLIENT_PORT=5100

# Unset TMUX so we can create a new top-level session even when called from
# inside an existing tmux session (e.g. via the Coday preview panel).
unset TMUX

DIR_NAME="$(basename "$(pwd)")"
SESSION="coday-dev-${DIR_NAME}"

# Find the first free port starting from a given port
find_free_port() {
  local port=$1
  while lsof -ti :"${port}" >/dev/null 2>&1; do
    port=$(( port + 1 ))
  done
  echo "${port}"
}

SERVER_PORT=$(find_free_port "${START_SERVER_PORT}")
CLIENT_PORT=$(find_free_port "${START_CLIENT_PORT}")

echo "Working directory : $(pwd)"
echo "Tmux session      : ${SESSION}"
echo "Server port       : ${SERVER_PORT}"
echo "Client port       : ${CLIENT_PORT}  (internal Angular dev server)"

# Kill any existing session for this worktree
if tmux has-session -t "${SESSION}" 2>/dev/null; then
  echo "Killing existing session '${SESSION}'..."
  tmux kill-session -t "${SESSION}"
fi

# Window 0: server
# PORT        — the port the Express server binds to
# ANGULAR_CLIENT_PORT — tells the server where to proxy non-API requests in dev mode
tmux new-session -d -s "${SESSION}" -n server \
  "PORT=${SERVER_PORT} ANGULAR_CLIENT_PORT=${CLIENT_PORT} BUILD_ENV=development pnpm nx run server:serve"

# Window 1: Angular dev server
tmux new-window -t "${SESSION}" -n client \
  "pnpm nx run client:serve --port=${CLIENT_PORT}"

echo
echo "Started. Open: http://localhost:${SERVER_PORT}"
echo "Attach with : tmux attach -t ${SESSION}"
echo "Stop with   : tmux kill-session -t ${SESSION}"

# Keep this process alive so the preview-manager can track liveness via
# tmux has-session on the outer preview session. Exits when inner session ends.
while tmux has-session -t "${SESSION}" 2>/dev/null; do
  sleep 5
done
echo "Session '${SESSION}' ended."
