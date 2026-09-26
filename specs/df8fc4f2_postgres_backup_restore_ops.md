# Plan: B4-T3 Backup/restore ops & procedures for local Factory PostgreSQL

## Overview
This task delivers automated, parameterized backup and restore procedures and script verification for the local/VM containerized Factory PostgreSQL instance (`coday_factory`).

All work is strictly contained in `factory/infra/`:
1. `factory/infra/backup.sh`: Parameterized dump exporter (`pg_dump` with fallback/support for direct container execution or host `pg_dump`).
2. `factory/infra/restore.sh`: Parameterized restore runner (`pg_restore` or `psql`).
3. `factory/infra/verify-restore.sh`: Fully automated end-to-end exercise (creates dump, creates temporary database `coday_factory_test_restore`, restores dump, compares table counts, executes Node import verification check, cleans up temporary database/dumps with `trap`).
4. `factory/infra/README.md`: Updated documentation covering Backup & Restore procedures, verification usage, and the required VM readiness checklist ("Checklist avant d'importer / placer des données partagées sur la VM").

---

## Detailed Requirements & Design

### 1. `factory/infra/backup.sh`
- **Environment Parameterization & Defaults**:
  - `PGHOST` (default: `localhost`)
  - `PGPORT` (default: `5432`)
  - `PGDATABASE` (default: `coday_factory`)
  - `PGUSER` (default: `factory`)
  - `PGPASSWORD` (default: `factory_dev_pass`)
  - `DUMP_DIR` (default: `factory/infra/dumps`, auto-created if absent)
- **Validation**:
  - Validates that mandatory parameters are non-empty.
  - Supports passing optional output filename as arg 1 (`$1`), or auto-generates timestamped file: `factory_backup_$(date +%Y%m%d_%H%M%S).dump`.
- **Execution Mechanism**:
  - Exports standard PostgreSQL custom format (`-Fc` / `.dump`), enabling structured restoration via `pg_restore`.
  - Attempts host `pg_dump` with environment `PGPASSWORD` passed.
  - Fallback / container support: If `pg_dump` is not installed on host, falls back to `docker exec -i coday-postgres pg_dump -U "$PGUSER" -d "$PGDATABASE" -Fc`.
  - Handles errors with `set -euo pipefail`, explicit error logs to `stderr`, and non-zero exit code on failure.
  - Emits the path of the created dump file on stdout upon success.

### 2. `factory/infra/restore.sh`
- **Usage**: `./restore.sh <path-to-dump-file> [target-db]`
- **Environment Parameterization & Defaults**:
  - Same `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, and default `PGDATABASE` as backup.
  - Optional target database override via `$2` argument or `TARGET_DATABASE` environment variable (defaults to `PGDATABASE`).
- **Validation**:
  - Checks if dump file parameter `$1` is provided and file exists.
  - Produces clean error message and exits non-zero if missing or unreadable.
- **Execution Mechanism**:
  - If custom format (`.dump`), uses `pg_restore --clean --if-exists --no-owner --no-privileges -d "$TARGET_DATABASE"` (or container fallback `docker exec -i coday-postgres pg_restore ...`).
  - If plain SQL (`.sql`), uses `psql -d "$TARGET_DATABASE"`.
  - Handles errors cleanly with proper exit codes.

### 3. `factory/infra/verify-restore.sh`
- **Fully Automated Verification Exercise**:
  1. Sets up temporary database name (e.g. `coday_factory_test_restore_${RANDOM}`).
  2. Register `trap` cleanup function to ensure temporary database drop (`DROP DATABASE IF EXISTS ...`) and temporary dump file deletion on EXIT/INT/TERM, even if a step fails.
  3. Dumps active database (`coday_factory`) using `./backup.sh`.
  4. Creates disposable target database using `psql -c "CREATE DATABASE ..."` or `docker exec`.
  5. Restores dump into disposable database using `./restore.sh`.
  6. Table count verification:
     - Queries key tables on source DB (`coday_factory`) and target DB (`coday_factory_test_restore`):
       - `workflow_definitions`
       - `workflow_instances`
       - `workflow_evidence`
       - `human_interactions`
       - `agent_step_attempts`
       - `agent_step_results`
       - `oracle_executions`
       - `work_environments`
       - `deliveries`
     - Asserts source row count == restored row count for every key table.
  7. Node runtime/import verification logic execution:
     - Invokes Node import verification helper against the restored DB (e.g. running `verifyImport` or equivalent JS check with `PGDATABASE=$TEMP_DB`).
  8. Exits 0 on total parity, non-zero on any count/hash discrepancy or error.

### 4. `factory/infra/README.md`
- Add dedicated section: **Backup & Restore Procedures**.
- Document running `backup.sh`, `restore.sh`, and `verify-restore.sh` with environment variable examples.
- Add mandatory **CHECKLIST**:
  ```markdown
  ## Checklist avant d'importer / placer des données partagées sur la VM

  - [ ] Backup testé (`backup.sh` validé)
  - [ ] Restore testé (`restore.sh` / `verify-restore.sh` validé)
  - [ ] Procédure et rollback `FACTORY_PERSISTENCE=fs|sql` connus et documentés (B4-T2)
  - [ ] Migrations Flyway à jour et vérifiées (V1..V7 jouées)
  ```

---

## Verification Plan

### Test Commands
1. Bash syntax check & executable bit:
   ```bash
   chmod +x factory/infra/backup.sh factory/infra/restore.sh factory/infra/verify-restore.sh
   bash -n factory/infra/backup.sh
   bash -n factory/infra/restore.sh
   bash -n factory/infra/verify-restore.sh
   ```
2. Existing Node unit/integration tests:
   ```bash
   node factory/tests/test-persistence-import.mjs
   ```
3. Full verification exercise run (if containerized postgres is running, or dry-run validation):
   ```bash
   ./factory/infra/verify-restore.sh
   ```

---

## Files to Modify/Create
- `factory/infra/backup.sh` (new executable script)
- `factory/infra/restore.sh` (new executable script)
- `factory/infra/verify-restore.sh` (new executable script)
- `factory/infra/README.md` (modified documentation)
