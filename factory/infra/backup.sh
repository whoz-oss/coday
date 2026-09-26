#!/usr/bin/env bash
# =============================================================================
# backup.sh — Factory PostgreSQL logical backup (custom format `.dump`)
# =============================================================================
#
# Produces a timestamped logical backup of the Factory database using
# `pg_dump -Fc` (PostgreSQL custom format), which is restorable with
# `factory/infra/restore.sh` (B4-T3).
#
# Everything is parameterized through the standard `PG*` environment variables
# (see `factory/infra/README.md`); no host or credential is hardcoded.
#
#   PGHOST       default: localhost
#   PGPORT       default: 5432
#   PGDATABASE   default: coday_factory
#   PGUSER       default: factory
#   PGPASSWORD   default: factory_dev_pass
#
# Optional:
#   DUMP_DIR        output directory            (default: factory/infra/dumps)
#   PG_CONTAINER    docker container name       (default: coday-postgres)
#   PG_MECHANISM    auto | host | docker        (default: auto)
#
# Usage:
#   ./backup.sh                 # -> $DUMP_DIR/factory_backup_<db>_<stamp>.dump
#   ./backup.sh mydump.dump     # -> $DUMP_DIR/mydump.dump
#   ./backup.sh /tmp/absolute.dump
#
# On success the absolute path of the created dump is printed on stdout; every
# log line goes to stderr so the path can be captured with `$(backup.sh)`.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- PG* environment with local-dev defaults --------------------------------
export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGDATABASE="${PGDATABASE:-coday_factory}"
export PGUSER="${PGUSER:-factory}"
export PGPASSWORD="${PGPASSWORD:-factory_dev_pass}"

DUMP_DIR="${DUMP_DIR:-$SCRIPT_DIR/dumps}"
PG_CONTAINER="${PG_CONTAINER:-coday-postgres}"
PG_MECHANISM="${PG_MECHANISM:-auto}"

log() { printf '[backup] %s\n' "$*" >&2; }
die() {
  printf '[backup] ERROR: %s\n' "$*" >&2
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
      have pg_dump || die 'PG_MECHANISM=host but pg_dump is not on PATH'
      printf 'host'
      ;;
    docker)
      have docker || die 'PG_MECHANISM=docker but docker is not on PATH'
      docker_container_running || die "docker container '$PG_CONTAINER' is not running"
      printf 'docker'
      ;;
    auto)
      if have pg_dump; then
        printf 'host'
      elif docker_container_running; then
        printf 'docker'
      else
        die "no usable backup mechanism: install the PostgreSQL client tools (pg_dump on PATH) or start the container '$PG_CONTAINER'"
      fi
      ;;
    *)
      die "invalid PG_MECHANISM '$PG_MECHANISM' (expected: auto | host | docker)"
      ;;
  esac
}

# --- Validate mandatory settings --------------------------------------------
[ -n "$PGHOST" ] || die 'PGHOST must not be empty'
[ -n "$PGDATABASE" ] || die 'PGDATABASE must not be empty'
[ -n "$PGUSER" ] || die 'PGUSER must not be empty'
case "$PGPORT" in
  '' | *[!0-9]*) die "PGPORT must be a positive integer (got '$PGPORT')" ;;
esac

# --- Resolve the output path ------------------------------------------------
OUT_ARG="${1:-}"
if [ -n "$OUT_ARG" ]; then
  case "$OUT_ARG" in
    /*) OUT_FILE="$OUT_ARG" ;;
    */*) OUT_FILE="$PWD/$OUT_ARG" ;;
    *) OUT_FILE="$DUMP_DIR/$OUT_ARG" ;;
  esac
else
  STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
  OUT_FILE="$DUMP_DIR/factory_backup_${PGDATABASE}_${STAMP}.dump"
fi

OUT_DIR="$(dirname "$OUT_FILE")"
mkdir -p "$OUT_DIR" || die "cannot create dump directory '$OUT_DIR'"

MECHANISM="$(detect_mechanism)"
log "mechanism=$MECHANISM host=$PGHOST port=$PGPORT database=$PGDATABASE user=$PGUSER"
log "output=$OUT_FILE"

umask 077
if [ "$MECHANISM" = 'host' ]; then
  if ! pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -Fc -f "$OUT_FILE"; then
    rm -f "$OUT_FILE"
    die "pg_dump failed for database '$PGDATABASE' on $PGHOST:$PGPORT"
  fi
else
  if ! docker exec "$PG_CONTAINER" pg_dump -U "$PGUSER" -d "$PGDATABASE" -Fc >"$OUT_FILE"; then
    rm -f "$OUT_FILE"
    die "docker exec pg_dump failed in container '$PG_CONTAINER'"
  fi
fi

if [ ! -s "$OUT_FILE" ]; then
  rm -f "$OUT_FILE"
  die "pg_dump produced an empty file: $OUT_FILE"
fi

log "backup complete: $OUT_FILE ($(du -h "$OUT_FILE" | cut -f1))"
printf '%s\n' "$OUT_FILE"
