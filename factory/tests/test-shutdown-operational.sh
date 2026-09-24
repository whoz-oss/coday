#!/usr/bin/env bash
# =============================================================================
# test-shutdown-operational.sh — A5/F24 SIGTERM live integration test
#
# Automates the full real SIGTERM scenario against a live AgentOS instance.
#
# WHAT IT PROVES
#   A1. process exits with code 1
#   A2. exactly one run_end line in the JSONL
#   A3. run_end.status = "fail"
#   A4. run_end.facts.terminatedBySignal = "SIGTERM"
#   A5. run_end.facts.checkoutMayBeIntermediate = true
#   A6. AgentOS case status = KILLED  (IDLE fails; it does not prove killCase fired)
#   A7. working tree byte-for-byte stable for STABILITY_S seconds after shutdown
#       (contents of untracked files included, not only paths)
#
# HOW THE CASE ID IS OBTAINED (no assumed endpoint)
#   active-case.mjs writes the active caseId to FACTORY_ACTIVE_CASE_FILE
#   whenever setActiveCaseId() is called, and removes it on clearActiveCaseId().
#   The script passes that file path via the env var; the test polls it.
#   This is the only production-code change: one env-var-gated writeFileSync
#   in active-case.mjs. Production semantics are unchanged when the var is unset.
#
# SIGTERM TIMING
#   SIGTERM is sent as soon as the active-case file is non-empty, i.e. the
#   moment runAgentTurn has published the caseId and the case is confirmed
#   RUNNING via GET /api/cases/<id>. SIGTERM_DELAY_S (default 0) is an
#   optional extra wait after that confirmation.
#
# CHECKOUT STABILITY
#   Hashes: git diff HEAD (tracked changes byte-for-byte) + sorted list of
#   untracked paths with their SHA-256 content hashes. Excludes the test
#   artifact and factory/runs/ from both sides.
#
# PREREQUISITES
#   - AgentOS running and reachable (AGENTOS_URL, default http://localhost:8124)
#   - FACTORY_NAMESPACE_ID set to a namespace containing FACTORY_AGENT
#   - FACTORY_AGENT: enabled, subAgents=[], FILE_ACCESS pointing at this repo
#   - Python 3, node, git, curl on PATH
#
# USAGE
#   FACTORY_NAMESPACE_ID=<id> FACTORY_AGENT=<name> \
#     bash factory/tests/test-shutdown-operational.sh
#
# OPTIONAL OVERRIDES
#   AGENTOS_URL      default: http://localhost:8124
#   FACTORY_USER     default: benjamin.valdes
#   SIGTERM_DELAY_S  default: 0    extra seconds after RUNNING confirmed
#   POLL_TIMEOUT_S   default: 90   max seconds to wait for agent phase
#   STABILITY_S      default: 30   seconds to monitor checkout after shutdown
#
# ISOLATION
#   Creates factory-sigterm-test-artifact.md at repo root and removes it on
#   exit. Pre-existing working-tree changes are not touched or asserted against.
# =============================================================================
set -euo pipefail

AGENTOS_URL="${AGENTOS_URL:-http://localhost:8124}"
FACTORY_USER="${FACTORY_USER:-benjamin.valdes}"
SIGTERM_DELAY_S="${SIGTERM_DELAY_S:-0}"
POLL_TIMEOUT_S="${POLL_TIMEOUT_S:-90}"
STABILITY_S="${STABILITY_S:-30}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FACTORY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNS_DIR="$FACTORY_DIR/runs"
REPO_ROOT="$(cd "$FACTORY_DIR/.." && pwd)"

# Test artifact — agent appends a comment here; cleaned up on exit
FACTORY_TEST_ARTIFACT="$REPO_ROOT/factory-sigterm-test-artifact.md"
ARTIFACT_BASENAME="$(basename "$FACTORY_TEST_ARTIFACT")"

# Observability file — active-case.mjs writes the live caseId here
ACTIVE_CASE_FILE="/tmp/factory-active-case-$$.txt"

# ---------------------------------------------------------------------------
# Colour helpers
# ---------------------------------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log_ok()   { printf "${GREEN}[PASS]${NC} %s\n" "$*"; }
log_fail() { printf "${RED}[FAIL]${NC} %s\n" "$*"; }
log_info() { printf "${YELLOW}[INFO]${NC} %s\n" "$*"; }
die()      { printf "${RED}[ERROR]${NC} %s\n" "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Assertion counter
# ---------------------------------------------------------------------------
PASS_COUNT=0
FAIL_COUNT=0

assert_eq() {
  local name="$1" actual="$2" expected="$3"
  if [ "$actual" = "$expected" ]; then
    log_ok "$name"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    log_fail "$name"
    printf '       expected : %s\n' "$expected"
    printf '       actual   : %s\n' "$actual"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

# ---------------------------------------------------------------------------
# Checkout stability hash
# ---------------------------------------------------------------------------
checkout_hash() {
  (
    git diff HEAD 2>/dev/null \
      | grep -v "^diff --git.*${ARTIFACT_BASENAME}" \
      | grep -v "^diff --git.*/factory/runs/"

    git ls-files --others --exclude-standard 2>/dev/null \
      | grep -v "^${ARTIFACT_BASENAME}$" \
      | grep -v '^factory/runs/' \
      | sort \
      | while IFS= read -r f; do
          if [ -f "$REPO_ROOT/$f" ]; then
            printf '%s ' "$f"
            python3 -c \
              "import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" \
              "$REPO_ROOT/$f" 2>/dev/null || printf 'unreadable\n'
          fi
        done
  ) | python3 -c 'import sys,hashlib; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())'
}

# ---------------------------------------------------------------------------
# State for cleanup
# ---------------------------------------------------------------------------
CHILD_PID=''
CREATED_CASE_ID=''
JSONL_FILE=''
TAIL_PID=''
RUN_LOG=''

cleanup() {
  local ec=$?
  if [ -n "$TAIL_PID" ] && kill -0 "$TAIL_PID" 2>/dev/null; then
    kill "$TAIL_PID" 2>/dev/null || true
    wait "$TAIL_PID" 2>/dev/null || true
  fi
  if [ -n "$CHILD_PID" ] && kill -0 "$CHILD_PID" 2>/dev/null; then
    log_info "cleanup: killing child process $CHILD_PID"
    kill -KILL "$CHILD_PID" 2>/dev/null || true
    wait "$CHILD_PID" 2>/dev/null || true
  fi
  if [ -n "$CREATED_CASE_ID" ]; then
    log_info "cleanup: killing AgentOS case $CREATED_CASE_ID (best-effort)"
    curl -sf -X POST \
      -H "X-External-User-Id: $FACTORY_USER" \
      -H 'Content-Type: application/json' \
      "$AGENTOS_URL/api/cases/$CREATED_CASE_ID/kill" >/dev/null 2>&1 || true
  fi
  rm -f "$FACTORY_TEST_ARTIFACT"
  rm -f "$ACTIVE_CASE_FILE"
  [ -n "$RUN_LOG" ] && rm -f "$RUN_LOG"
  if [ $ec -ne 0 ] && [ -n "$JSONL_FILE" ] && [ -f "$JSONL_FILE" ]; then
    log_info "cleanup: removing run file (test failed): $JSONL_FILE"
    rm -f "$JSONL_FILE"
  fi
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------
echo
echo '============================================================'
echo ' A5/F24 SIGTERM operational test'
echo '============================================================'
echo

[ -n "${FACTORY_NAMESPACE_ID:-}" ] || die 'FACTORY_NAMESPACE_ID is not set'
[ -n "${FACTORY_AGENT:-}" ]        || die 'FACTORY_AGENT is not set'
command -v python3 >/dev/null 2>&1  || die 'python3 not found'
command -v node    >/dev/null 2>&1  || die 'node not found'
command -v git     >/dev/null 2>&1  || die 'git not found'
command -v curl    >/dev/null 2>&1  || die 'curl not found'

log_info "AGENTOS_URL          : $AGENTOS_URL"
log_info "FACTORY_NAMESPACE_ID : $FACTORY_NAMESPACE_ID"
log_info "FACTORY_AGENT        : $FACTORY_AGENT"
log_info "FACTORY_USER         : $FACTORY_USER"
log_info "SIGTERM_DELAY_S      : $SIGTERM_DELAY_S"
log_info "POLL_TIMEOUT_S       : $POLL_TIMEOUT_S"
log_info "STABILITY_S          : $STABILITY_S"
log_info "ACTIVE_CASE_FILE     : $ACTIVE_CASE_FILE"
echo

log_info 'Checking AgentOS reachability...'
curl -sf \
  -H "X-External-User-Id: $FACTORY_USER" \
  "$AGENTOS_URL/api/agent-configs/by-parentId/$FACTORY_NAMESPACE_ID" \
  -o /dev/null \
  || die "AgentOS not reachable at $AGENTOS_URL or namespace $FACTORY_NAMESPACE_ID not found"
log_ok 'AgentOS reachable'
echo

# ---------------------------------------------------------------------------
# Step 1: Create test artifact + capture checkout baseline
# ---------------------------------------------------------------------------
log_info 'Creating test artifact...'
printf '# factory-sigterm-test-artifact\n\nCreated by test-shutdown-operational.sh.\nThe agent appends a comment line here.\n' \
  > "$FACTORY_TEST_ARTIFACT"
log_info "Test artifact : $FACTORY_TEST_ARTIFACT"

BASELINE_HASH="$(checkout_hash)"
log_info "Baseline checkout hash: $BASELINE_HASH"
echo

# ---------------------------------------------------------------------------
# Step 2: Snapshot runs directory before launch
# ---------------------------------------------------------------------------
mkdir -p "$RUNS_DIR"
EXISTING_JSONL="$(ls "$RUNS_DIR"/*.jsonl 2>/dev/null | sort || true)"

# ---------------------------------------------------------------------------
# Step 3: Launch fix-loop with FACTORY_ACTIVE_CASE_FILE set
# ---------------------------------------------------------------------------
log_info 'Launching fix-loop in background...'

RUN_LOG="/tmp/factory-sigterm-test-run-$$.log"

FACTORY_NAMESPACE_ID="$FACTORY_NAMESPACE_ID" \
FACTORY_AGENT="$FACTORY_AGENT" \
FACTORY_USER="$FACTORY_USER" \
AGENTOS_URL="$AGENTOS_URL" \
FACTORY_ACTIVE_CASE_FILE="$ACTIVE_CASE_FILE" \
FACTORY_TASK="Append the markdown line '<!-- sigterm-test -->' at the very end of the file ${ARTIFACT_BASENAME} at the repository root. Write only that line, nothing else." \
FACTORY_DOMAIN=front \
  node "$FACTORY_DIR/run.mjs" fix-loop > "$RUN_LOG" 2>&1 &
CHILD_PID=$!
log_info "fix-loop PID: $CHILD_PID"

tail -f "$RUN_LOG" &
TAIL_PID=$!

# ---------------------------------------------------------------------------
# Step 4: Identify the run JSONL file
# ---------------------------------------------------------------------------
log_info 'Waiting for run JSONL file to appear...'
JSONL_WAIT=0
while true; do
  sleep 0.5
  JSONL_WAIT=$((JSONL_WAIT + 1))
  if [ $JSONL_WAIT -gt 30 ]; then
    die 'Timed out (15s) waiting for run JSONL file'
  fi
  JSONL_FILE=''
  while IFS= read -r f; do
    if [ -n "$f" ] && ! printf '%s' "$EXISTING_JSONL" | grep -qF "$f"; then
      JSONL_FILE="$f"
      break
    fi
  done < <(ls "$RUNS_DIR"/*.jsonl 2>/dev/null | sort || true)
  if [ -n "$JSONL_FILE" ]; then
    log_info "Run JSONL: $JSONL_FILE"
    break
  fi
done

# ---------------------------------------------------------------------------
# Step 5: Wait for agent phase line in JSONL
# ---------------------------------------------------------------------------
log_info 'Waiting for agent phase to appear in JSONL...'
PHASE_WAIT=0
while true; do
  sleep 1
  PHASE_WAIT=$((PHASE_WAIT + 1))
  if [ $PHASE_WAIT -gt "$POLL_TIMEOUT_S" ]; then
    die "Timed out after ${POLL_TIMEOUT_S}s waiting for agent phase"
  fi
  if ! kill -0 "$CHILD_PID" 2>/dev/null; then
    die 'fix-loop process exited before agent phase started'
  fi
  if grep -q '"phaseKind":"agent"' "$JSONL_FILE" 2>/dev/null; then
    log_info "Agent phase detected in JSONL (${PHASE_WAIT}s)"
    break
  fi
done

# ---------------------------------------------------------------------------
# Step 6: Wait for ACTIVE_CASE_FILE to contain the caseId
# ---------------------------------------------------------------------------
log_info "Polling $ACTIVE_CASE_FILE for caseId..."
CASE_WAIT=0
CREATED_CASE_ID=''
while true; do
  sleep 0.5
  CASE_WAIT=$((CASE_WAIT + 1))
  if [ $CASE_WAIT -gt 60 ]; then
    die 'Timed out (30s) waiting for active caseId in ACTIVE_CASE_FILE'
  fi
  if ! kill -0 "$CHILD_PID" 2>/dev/null; then
    die 'fix-loop process exited before caseId was published'
  fi
  if [ -f "$ACTIVE_CASE_FILE" ]; then
    CREATED_CASE_ID="$(cat "$ACTIVE_CASE_FILE" 2>/dev/null || true)"
    if [ -n "$CREATED_CASE_ID" ]; then
      log_info "Active caseId: $CREATED_CASE_ID"
      break
    fi
  fi
done

# ---------------------------------------------------------------------------
# Step 7: Confirm the case is RUNNING
# ---------------------------------------------------------------------------
log_info 'Confirming case is RUNNING via GET /api/cases/<id>...'
RUNNING_WAIT=0
while true; do
  sleep 1
  RUNNING_WAIT=$((RUNNING_WAIT + 1))
  if [ $RUNNING_WAIT -gt 30 ]; then
    die 'Timed out (30s) waiting for case to reach RUNNING status'
  fi
  if ! kill -0 "$CHILD_PID" 2>/dev/null; then
    die 'fix-loop process exited before case reached RUNNING'
  fi
  CASE_JSON="$(curl -sf \
    -H "X-External-User-Id: $FACTORY_USER" \
    "$AGENTOS_URL/api/cases/$CREATED_CASE_ID" 2>/dev/null || printf '{}')"
  CASE_STATUS_NOW="$(printf '%s' "$CASE_JSON" | python3 -c \
    'import sys,json; print(json.loads(sys.stdin.read()).get("status",""))' 2>/dev/null || true)"
  if [ "$CASE_STATUS_NOW" = 'RUNNING' ]; then
    log_info "Case $CREATED_CASE_ID is RUNNING"
    break
  fi
  log_info "Case status: $CASE_STATUS_NOW (waiting for RUNNING...)"
done

if [ "$SIGTERM_DELAY_S" -gt 0 ] 2>/dev/null; then
  log_info "Waiting ${SIGTERM_DELAY_S}s extra before SIGTERM (SIGTERM_DELAY_S)..."
  sleep "$SIGTERM_DELAY_S"
fi

if ! kill -0 "$CHILD_PID" 2>/dev/null; then
  die 'fix-loop process exited before SIGTERM was sent'
fi

# ---------------------------------------------------------------------------
# Step 8: Send SIGTERM
# ---------------------------------------------------------------------------
log_info "Sending SIGTERM to PID $CHILD_PID..."
kill -TERM "$CHILD_PID"

# ---------------------------------------------------------------------------
# Step 9: Wait for process exit
# ---------------------------------------------------------------------------
log_info 'Waiting for process exit (max 30s)...'
WAIT_EXIT=0
CHILD_EXIT_CODE=''
while true; do
  sleep 1
  WAIT_EXIT=$((WAIT_EXIT + 1))
  if [ $WAIT_EXIT -gt 30 ]; then
    die 'Process did not exit within 30s of SIGTERM'
  fi
  if ! kill -0 "$CHILD_PID" 2>/dev/null; then
    set +e
    wait "$CHILD_PID"
    CHILD_EXIT_CODE=$?
    set -e
    CHILD_PID=''
    log_info "Process exited with code: $CHILD_EXIT_CODE"
    break
  fi
done

kill "$TAIL_PID" 2>/dev/null || true
wait "$TAIL_PID" 2>/dev/null || true
TAIL_PID=''

echo
echo '--- Assertions ---'
echo

assert_eq 'A1: process exit code = 1' "$CHILD_EXIT_CODE" '1'

RUN_END_COUNT=$(grep -c '"kind":"run_end"' "$JSONL_FILE" 2>/dev/null || echo 0)
assert_eq 'A2: exactly one run_end in JSONL' "$RUN_END_COUNT" '1'

RUN_END_JSON=$(grep '"kind":"run_end"' "$JSONL_FILE" | tail -1)

RUN_END_STATUS=$(printf '%s' "$RUN_END_JSON" | python3 -c \
  'import sys,json; print(json.loads(sys.stdin.read()).get("status",""))')
assert_eq 'A3: run_end.status = fail' "$RUN_END_STATUS" 'fail'

TERMINATED_BY=$(printf '%s' "$RUN_END_JSON" | python3 -c \
  'import sys,json; d=json.loads(sys.stdin.read()); print(d.get("facts",{}).get("terminatedBySignal",""))')
assert_eq 'A4: run_end.facts.terminatedBySignal = SIGTERM' "$TERMINATED_BY" 'SIGTERM'

CHECKOUT_INTERMEDIATE=$(printf '%s' "$RUN_END_JSON" | python3 -c \
  'import sys,json; d=json.loads(sys.stdin.read()); print(str(d.get("facts",{}).get("checkoutMayBeIntermediate",False)).lower())')
assert_eq 'A5: run_end.facts.checkoutMayBeIntermediate = true' "$CHECKOUT_INTERMEDIATE" 'true'

log_info "Checking AgentOS case $CREATED_CASE_ID final status..."
CASE_STATUS_JSON=$(curl -sf \
  -H "X-External-User-Id: $FACTORY_USER" \
  "$AGENTOS_URL/api/cases/$CREATED_CASE_ID" 2>/dev/null || printf '{}')
CASE_STATUS=$(printf '%s' "$CASE_STATUS_JSON" | python3 -c \
  'import sys,json; print(json.loads(sys.stdin.read()).get("status","UNKNOWN"))')
assert_eq 'A6: AgentOS case status = KILLED (not IDLE)' "$CASE_STATUS" 'KILLED'
CREATED_CASE_ID=''  # prevent double-kill in cleanup

log_info "Monitoring checkout stability for ${STABILITY_S}s..."
HASH_AT_EXIT=$(checkout_hash)
log_info "Hash at exit    : $HASH_AT_EXIT"
sleep "$STABILITY_S"
HASH_AFTER_WAIT=$(checkout_hash)
log_info "Hash after ${STABILITY_S}s: $HASH_AFTER_WAIT"
assert_eq "A7: working tree stable for ${STABILITY_S}s (no orphan writes)" \
  "$HASH_AFTER_WAIT" "$HASH_AT_EXIT"

if [ "$HASH_AT_EXIT" != "$BASELINE_HASH" ]; then
  log_info 'NOTE: working tree differs from pre-test baseline.'
  log_info 'Expected if the agent wrote to the artifact before SIGTERM.'
  log_info 'A7 (stability post-shutdown) is the definitive assertion.'
fi

echo
echo '============================================================'
printf ' Results: %d passed, %d failed\n' "$PASS_COUNT" "$FAIL_COUNT"
echo '============================================================'

if [ -n "$JSONL_FILE" ] && [ -f "$JSONL_FILE" ]; then
  echo
  log_info "Run file: $JSONL_FILE"
  log_info 'run_end line:'
  printf '%s' "$RUN_END_JSON" | python3 -m json.tool 2>/dev/null || printf '%s\n' "$RUN_END_JSON"
fi

echo
if [ "$FAIL_COUNT" -gt 0 ]; then
  printf "${RED}FAIL — %d assertion(s) failed.${NC}\n" "$FAIL_COUNT"
  exit 1
else
  printf "${GREEN}PASS — all assertions passed.${NC}\n"
  exit 0
fi
