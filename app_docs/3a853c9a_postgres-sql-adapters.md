# PostgreSQL foundations and SQL repository adapters

## What changed

Jalon B / Vague B1 / T1 now has a local PostgreSQL + Flyway foundation and driver-agnostic SQL repository skeleton for the two pilot aggregates:

- `WorkflowDefinition` definitions are read from `workflow_definitions`, with organization/workstream scope filtering and version resolution.
- `WorkflowInstance` state and projection are stored in `workflow_instances`, scoped by organization, workstream, and namespace. Lifecycle status is persisted as `active`/`removed`, while purge hard-deletes the row.
- Instance transitions reuse the existing domain transition policy and use `revision` in the SQL `UPDATE` predicate for optimistic locking. A failed compare-and-set is surfaced as `REVISION_CONFLICT`; creation retains the filesystem-compatible command hash for idempotent replay and identity-conflict detection.

The domain repository ports and domain types remain unchanged. The SQL adapters depend only on the structural `SqlClient` interface, so the PostgreSQL driver is loaded lazily at the composition edge rather than becoming a runtime bundle dependency.

## Main files

- `factory/infra/docker-compose.yml` starts PostgreSQL 16 and a one-shot Flyway service.
- `factory/infra/migrations/V1__init_workflow_pilot_schema.sql` creates both pilot tables, tenant columns, JSONB payloads, composite primary keys, indexes, status/revision checks, and the instance `updated_at` trigger.
- `factory/infra/README.md` documents startup, connection defaults, migration naming/versioning, reset, schema behavior, and verification commands.
- `factory/src/adapters/persistence/sql/db.ts` defines `SqlClient`, resolves `PG*` configuration, provides the lazy `pg` pool factory, and normalizes JSONB values.
- `factory/src/adapters/persistence/sql/sql-workflow-definition-repository.ts` implements the definition repository port.
- `factory/src/adapters/persistence/sql/sql-workflow-instance-repository.ts` implements create/get/list/transition/remove/restore/purge for instances.
- `factory/src/adapters/persistence/sql/index.ts` and `factory/src/adapters/persistence/index.ts` expose the SQL primitives and adapters.
- `factory/runtime/factory-operational.mjs` contains the corresponding runtime bundle implementation and exports.

## Contract coverage and verification

`factory/tests/test-repository-contract.mjs` is the shared behavioral assertion suite. `factory/tests/test-sql-repository-ports-adapters.mjs` runs the same definition and instance scenarios against the filesystem repositories and SQL repositories, using `factory/tests/support/in-memory-sql-client.mjs` so Docker is not required. Coverage includes definition list/get/resolve-not-found behavior; instance creation, idempotent replay, identity conflict, get/list, policy transition, stale revision rejection, remove/restore, and purge. The SQL runner also has a SQL-specific optimistic-lock assertion where policy allows but the revision predicate rejects the write.

Run:

```bash
node factory/tests/test-repository-ports-adapters.mjs
node factory/tests/test-sql-repository-ports-adapters.mjs
```

For a live database, start the documented compose stack and wire `createPgPoolClient()` from `factory/src/adapters/persistence/sql/db.ts` with the standard `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, optional `PGPOOL_MAX`, and `PGSSL` settings. The compose defaults are also listed in `factory/infra/README.md`.

`specs/3a853c9a_postgres_foundations_sql_adapters.md` records the implementation plan and file/schema/verification specification associated with this change.
