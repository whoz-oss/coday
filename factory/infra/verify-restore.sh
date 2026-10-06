#!/usr/bin/env bash
# =============================================================================
# verify-restore.sh — end-to-end backup / restore fidelity exercise (B4-T3)
# =============================================================================
#
# Fully automated drill that proves a Factory PostgreSQL backup can be restored
# and is byte-faithful. It:
#
#   1. dumps the active database through `backup.sh`;
#   2. creates a disposable target database (default `coday_factory_test_restore_<pid>`);
#   3. restores the dump into it through `restore.sh`;
#   4. compares, per key table, the row count AND a canonical content hash
#      between the source database and the restored one;
#   5. optionally invokes the B4-T1 `verifyImport` (filesystem → PostgreSQL)
#      against the restored database when `FACTORY_DATA_ROOT` and the `pg`
#      driver are available;
#   6. always drops the disposable database and removes the temporary dump
#      (bash `trap` on EXIT / INT / TERM), even on failure.
#
# Exit status: 0 when every checked table matches, non-zero otherwise.
#
# Everything is parameterized through the standard PG* environment variables:
#
#   PGHOST       default: localhost
#   PGPORT       default: 5432
#   PGDATABASE   default: coday_factory        (the source / active database)
#   PGUSER       default: factory
#   PGPASSWORD   default: factory_dev_pass
#
# Optional:
#   VERIFY_RESTORE_DATABASE   disposable database name
#   PG_MAINTENANCE_DATABASE   database used for CREATE/DROP DATABASE (default: postgres)
#   PG_CONTAINER              docker container name  (default: coday-postgres)
#   PG_MECHANISM              auto | host | docker   (default: auto)
#   FACTORY_DATA_ROOT         enables step 5 (B4-T1 verifyImport) when set
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# --- PG* environment with local-dev defaults --------------------------------
export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGDATABASE="${PGDATABASE:-coday_factory}"
export PGUSER="${PGUSER:-factory}"
export PGPASSWORD="${PGPASSWORD:-factory_dev_pass}"

PG_CONTAINER="${PG_CONTAINER:-coday-postgres}"
PG_MECHANISM="${PG_MECHANISM:-auto}"
PG_MAINTENANCE_DATABASE="${PG_MAINTENANCE_DATABASE:-postgres}"

SOURCE_DB="$PGDATABASE"
TARGET_DB="${VERIFY_RESTORE_DATABASE:-coday_factory_test_restore_$$}"
RUNTIME_ENTRY="$REPO_ROOT/factory/runtime/factory-operational.mjs"

# Key persistence tables materialised by Flyway V1..V7. The `deliveries` table
# is listed for completeness; when a schema version does not create it the check
# is skipped for that table with a warning (see README).
KEY_TABLES=(
  workflow_definitions
  workflow_instances
  workflow_evidence
  human_interactions
  agent_step_attempts
  agent_step_results
  oracle_executions
  work_environments
  deliveries
)

log() { printf '[verify-restore] %s\n' "$*" >&2; }
die() {
  printf '[verify-restore] ERROR: %s\n' "$*" >&2
  exit 1
}

have() { command -v "$1" >/dev/null 2>&1; }

docker_container_running() {
  have docker || return 1
  [ "$(docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" 2>/dev/null || true)" = 'true' ]
}

detect_mechanism() {
  case "$PG_MECHANISM" in
    host)
      have psql || die 'PG_MECHANISM=host but psql is not on PATH'
      printf 'host'
      ;;
    docker)
      have docker || die 'PG_MECHANISM=docker but docker is not on PATH'
      docker_container_running || die "docker container '$PG_CONTAINER' is not running"
      printf 'docker'
      ;;
    auto)
      if have psql; then
        printf 'host'
      elif docker_container_running; then
        printf 'docker'
      else
        die "no usable PostgreSQL client: install psql on PATH or start the container '$PG_CONTAINER'"
      fi
      ;;
    *)
      die "invalid PG_MECHANISM '$PG_MECHANISM' (expected: auto | host | docker)"
      ;;
  esac
}

# --- SQL helpers ------------------------------------------------------------
# Runs one SQL statement and prints the raw (unaligned, tuples-only) result.
sql_on() {
  local db="$1" sql="$2"
  if [ "$MECHANISM" = 'host' ]; then
    psql -X -q -t -A -v ON_ERROR_STOP=1 -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$db" -c "$sql"
  else
    docker exec -i "$PG_CONTAINER" psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$db" -c "$sql"
  fi
}

admin_sql() { sql_on "$PG_MAINTENANCE_DATABASE" "$1"; }

# Canonical, order-independent content hash of a whole table. `t::text` is
# deterministic (column order) and md5-string ordering makes the aggregation
# stable across source and restored databases.
table_hash_sql() {
  printf "SELECT coalesce(md5(string_agg(h, '' ORDER BY h)), '') FROM (SELECT md5(t::text) AS h FROM %s t) s" "$1"
}

# --- Cleanup trap -----------------------------------------------------------
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/factory-verify-restore.XXXXXX")"
TEMP_DB_CREATED=0

cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  set +e
  if [ "$TEMP_DB_CREATED" = '1' ]; then
    log "cleanup: dropping database '$TARGET_DB'"
    admin_sql "DROP DATABASE IF EXISTS \"$TARGET_DB\" WITH (FORCE)" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP_DIR"
  exit "$rc"
}
trap cleanup EXIT INT TERM

# --- Preflight --------------------------------------------------------------
[ -n "$SOURCE_DB" ] || die 'PGDATABASE must not be empty'
[ -f "$RUNTIME_ENTRY" ] || log "note: runtime bundle not found at $RUNTIME_ENTRY (node verification will be skipped)"

MECHANISM="$(detect_mechanism)"
log "mechanism=$MECHANISM source=$SOURCE_DB target=$TARGET_DB host=$PGHOST port=$PGPORT user=$PGUSER"

if ! sql_on "$SOURCE_DB" 'SELECT 1' >/dev/null 2>&1; then
  die "cannot reach source database '$SOURCE_DB' on $PGHOST:$PGPORT as '$PGUSER'"
fi

log "source database reachable"

# --- 1. Backup --------------------------------------------------------------
log 'step 1/5: dumping the active database...'
DUMP_FILE="$(DUMP_DIR="$TMP_DIR" PG_MECHANISM="$MECHANISM" "$SCRIPT_DIR/backup.sh")"
[ -s "$DUMP_FILE" ] || die "backup did not produce a dump: $DUMP_FILE"
log "dump created: $DUMP_FILE"

# --- 2. Create disposable database -----------------------------------------
log "step 2/5: creating disposable database '$TARGET_DB'..."
admin_sql "DROP DATABASE IF EXISTS \"$TARGET_DB\" WITH (FORCE)" >/dev/null 2>&1 || true
if ! admin_sql "CREATE DATABASE \"$TARGET_DB\"" >/dev/null; then
  die "failed to create disposable database '$TARGET_DB' (using '$PG_MAINTENANCE_DATABASE')"
fi
TEMP_DB_CREATED=1

# --- 3. Restore -------------------------------------------------------------
log 'step 3/5: restoring the dump into the disposable database...'
PG_MECHANISM="$MECHANISM" "$SCRIPT_DIR/restore.sh" "$DUMP_FILE" "$TARGET_DB"

# --- 4. Count + canonical hash fidelity -------------------------------------
log 'step 4/5: comparing table counts and canonical hashes...'
FAILURES=0
CHECKED=0

for table in "${KEY_TABLES[@]}"; do
  source_exists="$(sql_on "$SOURCE_DB" "SELECT to_regclass('$table') IS NOT NULL" 2>/dev/null || printf 'f')"
  if [ "$source_exists" != 't' ]; then
    log "  skip  $table: absent from source database (schema migration not applied?)"
    continue
  fi

  if ! source_count="$(sql_on "$SOURCE_DB" "SELECT count(*) FROM $table")"; then
    log "  FAIL  $table: cannot read source count"
    FAILURES=$((FAILURES + 1))
    continue
  fi
  if ! target_count="$(sql_on "$TARGET_DB" "SELECT count(*) FROM $table" 2>/dev/null)"; then
    log "  FAIL  $table: missing from restored database"
    FAILURES=$((FAILURES + 1))
    continue
  fi
  if ! source_hash="$(sql_on "$SOURCE_DB" "$(table_hash_sql "$table")")"; then
    log "  FAIL  $table: cannot read source hash"
    FAILURES=$((FAILURES + 1))
    continue
  fi
  if ! target_hash="$(sql_on "$TARGET_DB" "$(table_hash_sql "$table")" 2>/dev/null)"; then
    log "  FAIL  $table: cannot read restored hash"
    FAILURES=$((FAILURES + 1))
    continue
  fi

  if [ "$source_count" = "$target_count" ] && [ "$source_hash" = "$target_hash" ]; then
    log "  ok    $table count=$source_count hash=$(printf '%s' "$source_hash" | cut -c1-12)…"
  else
    log "  FAIL  $table source(count=$source_count hash=$source_hash) != restored(count=$target_count hash=$target_hash)"
    FAILURES=$((FAILURES + 1))
  fi
  CHECKED=$((CHECKED + 1))
done

# --- 5. B4-T1 verifyImport against the restored database (optional) ---------
log 'step 5/5: node import verification (optional)...'
NODE_SKIPPED=0
if [ -z "${FACTORY_DATA_ROOT:-}" ]; then
  log "  skip  FACTORY_DATA_ROOT is not set"
  NODE_SKIPPED=1
elif ! have node; then
  log '  skip  node is not on PATH'
  NODE_SKIPPED=1
elif [ ! -f "$RUNTIME_ENTRY" ]; then
  log "  skip  runtime bundle not found at $RUNTIME_ENTRY"
  NODE_SKIPPED=1
else
  NODE_VERIFY_FILE="$TMP_DIR/node-verify.mjs"
  cat >"$NODE_VERIFY_FILE" <<'NODE'
import { pathToFileURL } from 'node:url'

const runtime = await import(pathToFileURL(process.env.FACTORY_RUNTIME_ENTRY).href)
const dataRoot = process.env.FACTORY_DATA_ROOT
const organizationId = process.env.ORGANIZATION_ID ?? process.env.DEFAULT_ORGANIZATION_ID ?? 'default'
const workstreamId = process.env.WORKSTREAM_ID ?? process.env.DEFAULT_WORKSTREAM_ID ?? 'default'

let sqlClient
try {
  sqlClient = await runtime.createPgPoolClient(runtime.resolveSqlDatabaseConfig())
} catch (error) {
  console.warn(`[verify-restore] node verification skipped: cannot create SQL client (${error?.message ?? error})`)
  process.exit(78)
}

let exitCode = 78
try {
  const report = await runtime.verifyImport({ dataRoot, sqlClient, organizationId, workstreamId })
  console.log(
    `[verify-restore] verifyImport ok=${report.ok} aggregates=${report.totalFilesystemAggregates}/${report.totalSqlAggregates}`
  )
  for (const context of Object.values(report.contexts)) {
    if (!context.ok) {
      console.error(
        `[verify-restore]   ${context.context}: filesystem=${context.filesystemCount} sql=${context.sqlCount} discrepancies=${context.discrepancies.length}`
      )
    }
  }
  exitCode = report.ok ? 0 : 1
} catch (error) {
  console.warn(`[verify-restore] node verification skipped: ${error?.message ?? error}`)
  exitCode = 78
} finally {
  await sqlClient.end?.()
}
process.exit(exitCode)
NODE

  set +e
  FACTORY_RUNTIME_ENTRY="$RUNTIME_ENTRY" PGDATABASE="$TARGET_DB" node "$NODE_VERIFY_FILE"
  node_rc=$?
  set -e
  case "$node_rc" in
    0) log '  ok    verifyImport reports count/hash parity' ;;
    78) log "  skip  'pg' driver not installed" ;;
    *)
      log "  FAIL  verifyImport reported a mismatch (exit $node_rc)"
      FAILURES=$((FAILURES + 1))
      ;;
  esac
  if [ "$node_rc" = '78' ]; then NODE_SKIPPED=1; fi
fi

# --- Result -----------------------------------------------------------------
if [ "$FAILURES" -ne 0 ]; then
  log "RESULT: FAIL — $FAILURES check(s) failed, $CHECKED key table(s) compared"
  exit 1
fi

if [ "$CHECKED" -eq 0 ]; then
  log 'RESULT: FAIL — no key table could be compared (is the schema migrated?)'
  exit 1
fi

if [ "$NODE_SKIPPED" = '1' ]; then
  log "RESULT: PASS — $CHECKED key table(s) match (count + canonical hash); node verification skipped"
else
  log "RESULT: PASS — $CHECKED key table(s) match and node verification passed"
fi
exit 0
