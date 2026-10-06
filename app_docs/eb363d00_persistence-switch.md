# B4-T2 persistence authority switching

## What changed

Factory persistence is now selected at the composition root with `FACTORY_PERSISTENCE`:

- `fs` is the default and preserves the existing filesystem stores and read/write behavior.
- `fs` plus `FACTORY_PERSISTENCE_SHADOW=true` keeps filesystem responses authoritative, while selected reads query PostgreSQL and compare canonical hashes. Differences emit `[SHADOW_READ_DISCREPANCY]`; SQL or comparison failures emit `[SHADOW_READ_ERROR]`. Both paths return the filesystem result without failing the request.
- `sql` builds PostgreSQL repositories and SQL-backed store facades. Mapped operations read and write only PostgreSQL; operations without a SQL adapter fail closed rather than falling back to filesystem writes.

The SQL client is injectable for tests and otherwise created lazily from the standard PostgreSQL configuration. Tenant scope is taken from `FACTORY_ORGANIZATION_ID` and `FACTORY_WORKSTREAM_ID`, defaulting to `default` for both. Startup logging now reports the selected persistence mode.

## Where it lives

- `factory/dashboard/composition-root.mjs` parses the environment, creates filesystem stores, and selects either the unchanged filesystem bundle, shadow decorators, or SQL-authority stores. `createStores` and `createCompositionRoot` accept optional injected SQL client/logger options.
- `factory/dashboard/persistence-authority.mjs` contains the policy and wiring: mode parsing, lazy client, SQL repository construction, shadow-read wrappers/probes, SQL store facades, and the `PERSISTENCE_OPERATION_NOT_MIGRATED` fail-closed behavior.
- `factory/PERSISTENCE_SWITCH_ROLLBACK.md` is the operational guide. It covers the FS/shadow/SQL matrix, B4-T1 seeding, environment switch and rollback commands, PostgreSQL Docker Compose checks, filesystem write verification, log markers, and the current migrated-operation coverage/gaps.
- `factory/README.md` links to the new switch/rollback guide.
- `specs/eb363d00_shadow_read_switch_rollback.md` records the B4-T2 design, boundaries, implementation plan, and verification commands.

The implementation leaves the existing filesystem, SQL, migration, and AgentOS code untouched.

## Verification

Run the offline proof with:

```sh
node factory/tests/test-persistence-shadow-and-switch.mjs
```

It uses a temporary filesystem root and `createInMemorySqlClient` and covers default FS behavior, faithful and divergent shadow reads, shadow SQL-error containment, SQL-only reads/writes with no filesystem directory writes, and switching back to filesystem authority. The test is described in `factory/tests/README.md`.

For a containerized PostgreSQL check, follow `factory/PERSISTENCE_SWITCH_ROLLBACK.md`: start `factory/infra/docker-compose.yml`, wait for Flyway, run the B4-T1 import against the dashboard data root, observe shadow markers, then start with `FACTORY_PERSISTENCE=sql`. The guide includes `find`/`diff` checks for an untouched filesystem and `psql` queries for persisted rows. Rollback is performed by restarting with `FACTORY_PERSISTENCE=fs` or by unsetting both persistence variables.

## Important coverage note

Shadow probes currently cover workflow projection, work-unit environment, and delivery reads. Evidence, human-interaction, and agent-step-result reads are intentionally not shadowed because their filesystem digest keys do not align with the SQL scope-keyed ledgers. SQL authority wiring is present for those migrated repositories. Delivery-evidence and resume-dispatch have no SQL adapters and therefore fail closed in SQL mode; several workflow/delivery journal operations are also explicitly unmapped until their SQL coverage exists.
