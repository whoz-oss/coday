# B4-T3 — PostgreSQL backup/restore operations

## What changed

Factory PostgreSQL now has parameterized operational scripts for logical backups, restores, and an end-to-end restore drill. The scripts default to the local development connection (`localhost:5432`, database `coday_factory`, user `factory`, password `factory_dev_pass`) but read the standard `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD` variables. They can use PostgreSQL client tools on the host or fall back to a running Docker container, controlled automatically or with `PG_MECHANISM=host|docker`; `PG_CONTAINER` selects the container.

- `factory/infra/backup.sh` creates a timestamped custom-format `.dump` in `factory/infra/dumps` by default, or accepts a relative/absolute output path. It validates connection settings and the selected mechanism, removes failed/empty output, logs to stderr, and prints the successful absolute dump path to stdout for command substitution.
- `factory/infra/restore.sh` validates a supplied readable, non-empty dump, then restores custom-format files with `pg_restore` and `.sql` files with `psql`. The target is the second argument, then `TARGET_DATABASE`, then `PGDATABASE`; the target database must already exist. Restore uses clean/no-owner/no-privilege options for custom dumps and stops on SQL errors.
- `factory/infra/verify-restore.sh` performs the complete drill: backup, disposable database creation, restore, and source-vs-restored comparison. It checks both row counts and an order-independent canonical content hash for the key workflow, evidence, interaction, agent-step, oracle, environment, and delivery tables. Tables absent from the source are logged as skipped; at least one key table must be compared. A `trap` drops the temporary database and removes temporary files on success or failure. If `FACTORY_DATA_ROOT`, the runtime bundle, and Node are available, it also invokes the B4-T1 `verifyImport`; unavailable optional verification is skipped, while a reported mismatch fails the drill.

`factory/infra/dumps/.gitignore` keeps generated backup artifacts out of version control.

## How to use and verify

Make sure PostgreSQL is reachable and the schema is migrated, then run:

```bash
# Create a timestamped backup; capture its path if needed
DUMP="$(./factory/infra/backup.sh)"

# Restore into an existing database (defaults to PGDATABASE)
./factory/infra/restore.sh "$DUMP" coday_factory_restore

# Run the automated backup -> disposable DB -> restore -> fidelity check
./factory/infra/verify-restore.sh
```

For a VM or other endpoint, set the PG variables explicitly, for example:

```bash
PGHOST=vm.internal PGPORT=5432 PGDATABASE=coday_factory \
  PGUSER=factory PGPASSWORD='…' ./factory/infra/verify-restore.sh
```

`DUMP_DIR` changes backup output location, `VERIFY_RESTORE_DATABASE` names the disposable verification database, and `PG_MAINTENANCE_DATABASE` selects the database used to create/drop it. `FACTORY_DATA_ROOT` enables the optional Node import verification. The documented success condition is exit status 0; any backup, restore, table count/hash mismatch, or non-optional verification failure returns non-zero.

## Operational readiness checklist

`factory/infra/README.md` adds the required VM checklist, in French:

- backup tested with `backup.sh`;
- restore tested with `restore.sh` / `verify-restore.sh`;
- `FACTORY_PERSISTENCE=fs|sql` switch and rollback procedure known and documented for B4-T2;
- Flyway migrations V1..V7 up to date and verified as applied.

The same README documents the host/Docker modes, defaults, examples, cleanup behavior, and the fact that `deliveries` is included in the key-table list but skipped when absent from the migrated schema.

## Files carrying the change

- `factory/infra/backup.sh`
- `factory/infra/restore.sh`
- `factory/infra/verify-restore.sh`
- `factory/infra/README.md`
- `factory/infra/dumps/.gitignore`
- `specs/df8fc4f2_postgres_backup_restore_ops.md` (the added implementation and verification plan)

The plan records syntax, executable-bit, existing Node test, and live drill commands as the verification procedure. The captured change does not provide execution results for those commands.
