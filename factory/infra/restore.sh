#!/usr/bin/env bash
# =============================================================================
# restore.sh — Factory PostgreSQL restore from a `.dump` / `.sql` file
# =============================================================================
#
# Restores a logical backup produced by `factory/infra/backup.sh` (custom
# `.dump`) or any plain-SQL dump (`.sql`) into a target database.
#
# Everything is parameterized through the standard `PG*` environment variables
# (see `factory/infra/README.md`); no host or credential is hardcoded.
#
#   PGHOST       default: localhost
#   PGPORT       default: 5432
#   PGDATABASE   default: coday_factory  (used when no target is given)
#   PGUSER       default: factory
#   PGPASSWORD   default: factory_dev_pass
#
# Optional:
#   TARGET_DATABASE   target database (overridden by the 2nd CLI argument)
#   PG_CONTAINER      docker container name  (default: coday-postgres)
#   PG_MECHANISM      auto | host | docker   (default: auto)
#
# Usage:
#   ./restore.sh <dump-file>              # restore into $PGDATABASE
#   ./restore.sh <dump-file> <target-db>  # restore into an explicit database
#
# The target database MUST already exist: this script restores objects, it does
# not create the database. `verify-restore.sh` creates the disposable database
# before calling it.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- PG* environment with local-dev defaults --------------------------------
export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGDATABASE="${PGDATABASE:-coday_factory}"
export PGUSER="${PGUSER:-factory}"
export PGPASSWORD="${PGPASSWORD:-factory_dev_pass}"

PG_CONTAINER="${PG_CONTAINER:-coday-postgres}"
PG_MECHANISM="${PG_MECHANISM:-auto}"

log() { printf '[restore] %s\n' "$*" >&2; }
die() {
  printf '[restore] ERROR: %s\n' "$*" >&2
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
      have pg_restore || have psql || die 'PG_MECHANISM=host but neither pg_restore nor psql is on PATH'
      printf 'host'
      ;;
    docker)
      have docker || die 'PG_MECHANISM=docker but docker is not on PATH'
      docker_container_running || die "docker container '$PG_CONTAINER' is not running"
      printf 'docker'
      ;;
    auto)
      if have pg_restore || have psql; then
        printf 'host'
      elif docker_container_running; then
        printf 'docker'
      else
        die "no usable restore mechanism: install the PostgreSQL client tools (pg_restore/psql on PATH) or start the container '$PG_CONTAINER'"
      fi
      ;;
    *)
      die "invalid PG_MECHANISM '$PG_MECHANISM' (expected: auto | host | docker)"
      ;;
  esac
}

# --- Arguments & validation -------------------------------------------------
DUMP_FILE="${1:-}"
TARGET_DATABASE="${2:-${TARGET_DATABASE:-$PGDATABASE}}"

[ -n "$DUMP_FILE" ] || die "usage: restore.sh <dump-file> [target-database]"
[ -e "$DUMP_FILE" ] || die "dump file not found: $DUMP_FILE"
[ -f "$DUMP_FILE" ] || die "dump path is not a regular file: $DUMP_FILE"
[ -r "$DUMP_FILE" ] || die "dump file is not readable: $DUMP_FILE"
[ -s "$DUMP_FILE" ] || die "dump file is empty: $DUMP_FILE"
[ -n "$TARGET_DATABASE" ] || die 'target database must not be empty'

case "$DUMP_FILE" in
  *.sql) FORMAT='plain' ;;
  *) FORMAT='custom' ;;
esac

MECHANISM="$(detect_mechanism)"
log "mechanism=$MECHANISM host=$PGHOST port=$PGPORT target=$TARGET_DATABASE user=$PGUSER"
log "dump=$DUMP_FILE format=$FORMAT"

if [ "$FORMAT" = 'custom' ]; then
  if [ "$MECHANISM" = 'host' ]; then
    have pg_restore || die 'pg_restore is required to restore a custom-format dump'
    if ! pg_restore --clean --if-exists --no-owner --no-privileges --exit-on-error \
      -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$TARGET_DATABASE" "$DUMP_FILE"; then
      die "pg_restore failed for database '$TARGET_DATABASE' on $PGHOST:$PGPORT"
    fi
  else
    if ! docker exec -i "$PG_CONTAINER" pg_restore --clean --if-exists --no-owner --no-privileges --exit-on-error \
      -U "$PGUSER" -d "$TARGET_DATABASE" <"$DUMP_FILE"; then
      die "docker exec pg_restore failed in container '$PG_CONTAINER'"
    fi
  fi
else
  if [ "$MECHANISM" = 'host' ]; then
    have psql || die 'psql is required to restore a plain-SQL dump'
    if ! psql -X -v ON_ERROR_STOP=1 \
      -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$TARGET_DATABASE" -f "$DUMP_FILE"; then
      die "psql failed for database '$TARGET_DATABASE' on $PGHOST:$PGPORT"
    fi
  else
    if ! docker exec -i "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 \
      -U "$PGUSER" -d "$TARGET_DATABASE" <"$DUMP_FILE"; then
      die "docker exec psql failed in container '$PG_CONTAINER'"
    fi
  fi
fi

log "restore complete: $DUMP_FILE -> $TARGET_DATABASE"
