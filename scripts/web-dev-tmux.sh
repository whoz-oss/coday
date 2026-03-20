#!/usr/bin/env bash
# Start the Coday dev stack (client + server) in a tmux session.
# Ports are derived deterministically from the working directory name so that
# multiple worktrees on the same machine never collide.
#
# Port allocation:
#   offset  = sum of ASCII codes of dir basename  mod 100
#   server  = 4100 + offset
#   client  = 4200 + offset
#
# The two processes run in separate tmux windows inside the same session.
# The server proxies non-API requests to the Angular dev server, so the
# entry point for the browser is the SERVER port, not the client port.
#
# Usage: pnpm run web:dev:tmux

set -euo pipefail

# Unset TMUX so we can create a new top-level session even when this script
# is called from inside an existing tmux session (e.g. via the preview panel).
unset TMUX

# Compute a stable offset from the current directory's basename
DIR_NAME="$(basename "$(pwd)")"
OFFSET=0
for (( i=0; i<${#DIR_NAME}; i++ )); do
  OFFSET=$(( OFFSET + $(printf '%d' "'${DIR_NAME:$i:1}") ))
done
OFFSET=$(( OFFSET % 100 ))

SERVER_PORT=$(( 4100 + OFFSET ))
CLIENT_PORT=$(( 4200 + OFFSET ))
SESSION="coday-dev-${DIR_NAME}"

echo "Working directory : $(pwd)"
echo "Tmux session      : ${SESSION}"
echo "Server port       : ${SERVER_PORT}  (open this in your browser)"
echo "Client port       : ${CLIENT_PORT}  (internal Angular dev server)"

# Kill any existing session with the same name
if tmux has-session -t "${SESSION}" 2>/dev/null; then
  echo "Killing existing session '${SESSION}'..."
  tmux kill-session -t "${SESSION}"
fi

# Window 0: server (reads PORT from env)
tmux new-session -d -s "${SESSION}" -n server \
  "PORT=${SERVER_PORT} BUILD_ENV=development tsx watch apps/server/src/server.ts"

# Window 1: Angular dev server (--port flag passed directly to ng serve)
tmux new-window -t "${SESSION}" -n client \
  "pnpm nx run client:serve --port=${CLIENT_PORT}"

echo
echo "Started. Open: http://localhost:${SERVER_PORT}"
echo "Attach with : tmux attach -t ${SESSION}"
echo "Stop with   : tmux kill-session -t ${SESSION}"
