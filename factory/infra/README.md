# Factory — local PostgreSQL & Flyway

This directory holds the **local, disposable** PostgreSQL infrastructure for the
Factory persistence pilot (Jalon B - Vague B1). It targets the two aggregates
migrated first from the filesystem:

| Aggregate | Domain port | SQL adapter |
|---|---|---|
| `WorkflowDefinition` | `src/ports/persistence/workflow-definition-repository.ts` | `src/adapters/persistence/sql/sql-workflow-definition-repository.ts` |
| `WorkflowInstance` | `src/ports/persistence/workflow-instance-repository.ts` | `src/adapters/persistence/sql/sql-workflow-instance-repository.ts` |

Nothing here is part of the autonomous Factory runtime: the runtime bundle keeps
`node:*` as its only external import (see `DEPENDENCY_MATRIX.md`). These adapters
are the SQL side of the shared repository contract and are exercised offline by
`factory/tests/test-sql-repository-ports-adapters.mjs` against an in-memory
client — no Docker required to run the tests.

## Layout

```
factory/infra/
├── docker-compose.yml                       # PostgreSQL 16 + one-shot Flyway runner
├── README.md                                # this file
└── migrations/
    ├── V1__init_workflow_pilot_schema.sql   # pilot schema (definitions + instances)
    ├── V2__tenant_and_membership.sql        # Jalon B2 tenant & membership schema
    ├── V3__workflow_core.sql                # Jalon B2 workflow core extension (definitions/grants/steps/transitions)
    └── V4__outbox_and_idempotency.sql       # Jalon B2 support tables (outbox + idempotency)
```

## Start PostgreSQL + apply migrations

```bash
# Start PostgreSQL and let the one-shot `flyway` service migrate it.
docker compose -f factory/infra/docker-compose.yml up -d

# Watch the migration converge to "Successfully applied 1 migration".
docker compose -f factory/infra/docker-compose.yml logs -f flyway

# Inspect the schema.
docker compose -f factory/infra/docker-compose.yml exec coday-postgres \
  psql -U factory -d coday_factory -c '\dt'
```

Connection settings (also the defaults used by the SQL adapters):

| Variable | Default |
|---|---|
| `PGHOST` | `localhost` |
| `PGPORT` | `5432` |
| `PGDATABASE` | `coday_factory` |
| `PGUSER` | `factory` |
| `PGPASSWORD` | `factory_dev_pass` |

## Migrations

Migrations are plain SQL applied by Flyway with the standard naming convention:

```
V<version>__<snake_case_description>.sql
```

* Versions are strictly increasing and never edited once applied; a fix is a new
  `V<n+1>__…sql` file.
* `V1__init_workflow_pilot_schema.sql` creates `workflow_definitions` and
  `workflow_instances` (the two pilot aggregates).
* `V2__tenant_and_membership.sql` creates the authoritative Jalon B2 tenant and
  membership schema: `organizations`, `workstreams`, `squads`, `principals`,
  `service_identities`, `roles`, `organization_memberships`,
  `workstream_memberships`, `squad_memberships`, `repositories` and
  `workstream_repositories` (see the schema overview below). Data
  migration/cutover of the remaining aggregates is deferred to B3/B4.
* `V3__workflow_core.sql` extends `workflow_definitions` with the visibility scope
  (`visibility`, `owner_workstream_id`) and adds the workflow core tables
  `workflow_definition_versions`, `workstream_workflow_grants`,
  `workflow_step_states` and `workflow_transitions` (see below).
* `V4__outbox_and_idempotency.sql` adds the cross-cutting support tables
  `outbox_events` (transactional outbox for external/async effects) and
  `idempotency_records` (tenant-scoped idempotent command submission) (see below).
* Flyway is the only migration authority. Do **not** modify a migration that has
  been applied to a shared database.

To apply migrations without the compose `flyway` service (e.g. a Flyway CLI
already on the host):

```bash
flyway \
  -url=jdbc:postgresql://localhost:5432/coday_factory \
  -user=factory -password=factory_dev_pass \
  -locations=filesystem:factory/infra/migrations \
  migrate
```

### Reset the local database

```bash
docker compose -f factory/infra/docker-compose.yml down -v  # drops the coday_pgdata volume
docker compose -f factory/infra/docker-compose.yml up -d
```

## Schema overview

### `workflow_definitions`

Read-only platform/organization/workstream catalog.

* Identity: `PRIMARY KEY (organization_id, workflow_type, version)`.
* `workstream_id` is nullable: `NULL` means platform/organization scope, a value
  means workstream scope.
* `definition_json` is the canonical `WorkflowDefinition`; `definition_hash` is
  the canonical SHA-256 the domain computes (`hashWorkflowDefinition`).
* A partial unique index keeps `workflowType@version` unique in the platform scope
  (`organization_id = 'default' AND workstream_id IS NULL`).

### `workflow_instances`

Mutable governed workflow state, tenant-scoped and versioned.

* Identity: `PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id)`.
* `revision` is the optimistic-locking column: writers `UPDATE … WHERE revision = $expected`
  and surface `REVISION_CONFLICT` when zero rows match.
* `instance_json` is the domain `WorkflowInstance`; `projection_json` is its read
  `WorkflowProjection`. `status` is `active` or `removed` (purge hard-deletes).
* `creation_command_hash` mirrors the filesystem store's idempotency key so a
  replayed start command returns the existing snapshot and a divergent command
  fails with `WORKFLOW_IDENTITY_CONFLICT`.

## Schema overview — V2 tenant & membership

All V2 tables are tenant-scoped: `organization_id` is `NOT NULL` (with
`DEFAULT 'default'` matching V1). Composite foreign keys carry `organization_id`
so the database itself prevents a cross-tenant reference.

| Table | Primary key | Notable constraints |
|---|---|---|
| `organizations` | `(organization_id)` | tenant root; `revision >= 1` |
| `workstreams` | `(organization_id, workstream_id)` | FK → `organizations`; explicit `UNIQUE` |
| `squads` | `(organization_id, workstream_id, squad_id)` | composite FK → `workstreams`; explicit `UNIQUE` |
| `principals` | `(organization_id, principal_id)` | FK → `organizations`; `email` optional |
| `service_identities` | `(organization_id, service_identity_id)` | FK → `organizations` |
| `roles` | `(organization_id, role_id, version)` | `permissions` JSONB; FK → `organizations` |
| `organization_memberships` | `(organization_id, subject_type, subject_id, role_id)` | composite FK → `roles`; `subject_type` check |
| `workstream_memberships` | `(organization_id, workstream_id, subject_type, subject_id, role_id)` | composite FK → `workstreams` and `roles` |
| `squad_memberships` | `(organization_id, workstream_id, squad_id, subject_type, subject_id, role_id)` | composite FK → `squads` and `roles` |
| `repositories` | `(organization_id, repository_id)` | FK → `organizations`; `url` optional |
| `workstream_repositories` | `(organization_id, workstream_id, repository_id)` | composite FK → `workstreams` and `repositories` |

Mutable tables (`organizations`, `workstreams`, `squads`, `principals`,
`service_identities`, `roles`, `repositories`) carry `revision INTEGER NOT NULL
DEFAULT 1 CHECK (revision >= 1)` and an `updated_at` maintained by a
`BEFORE UPDATE` trigger calling the shared `set_updated_at()` function.

## Schema overview — V3 workflow core

V3 extends the workflow pilot so the engine can persist visibility, explicit
access grants and per-instance execution state. All new tables stay tenant-scoped
(`organization_id NOT NULL DEFAULT 'default'`) and reuse the shared
`set_updated_at()` function from V2.

`workflow_definitions` gains:

* `visibility VARCHAR(32) NOT NULL DEFAULT 'organization'`
  (`CHECK (visibility IN ('platform', 'organization', 'workstream'))`).
* `owner_workstream_id VARCHAR(255)` — nullable target workstream when
  `visibility = 'workstream'`.

| Table | Primary key | Notable constraints |
|---|---|---|
| `workflow_definition_versions` | `(organization_id, workflow_type, version, revision)` | composite FK → `workflow_definitions`; `revision >= 1`; `updated_at` trigger |
| `workstream_workflow_grants` | `(organization_id, workstream_id, workflow_type)` | composite FK → `workstreams`; `version` optional pin; `enabled` / `configuration`; `updated_at` trigger |
| `workflow_step_states` | `(organization_id, workstream_id, namespace_id, workflow_id, step_id)` | composite FK → `workflow_instances`; `revision >= 1`; `updated_at` trigger |
| `workflow_transitions` | `(organization_id, workstream_id, namespace_id, workflow_id, transition_id)` | composite FK → `workflow_instances`; append-only (no `updated_at`) |

**Amendment 1 (visibility & grants):** `workflow_definitions.visibility` scopes a
definition to the platform, an organization or a single workstream, and
`workstream_workflow_grants` records the explicit per-workstream access decisions
(separately from the definition itself).

**Amendment 8 (composite FKs for tenant isolation):**
`workflow_step_states` and `workflow_transitions` reference
`workflow_instances (organization_id, workstream_id, namespace_id, workflow_id)`
with an `ON DELETE CASCADE` composite FK, so the database rejects any orphan or
cross-tenant / cross-workstream child and purges children with their instance.

## Contract tests

The shared contract suite runs the exact same assertions against the filesystem
and SQL adapters, using an in-memory SQL client:

```bash
node factory/tests/test-repository-ports-adapters.mjs        # existing filesystem suite
node factory/tests/test-sql-repository-ports-adapters.mjs    # shared contract: filesystem + SQL
```

The V2 migration itself is validated offline (no PostgreSQL, Docker or `pg`
driver required) by parsing the SQL and asserting tables, composite foreign keys,
uniqueness of FK targets, CHECK constraints and `updated_at` triggers:

```bash
node factory/tests/test-v2-migration-schema.mjs
```

The V3 workflow core migration is validated the same way, on the cumulated
V1 + V2 + V3 schema state (tables, columns, composite FKs, CHECK constraints,
`updated_at` triggers and tenant isolation guarantees):

```bash
node factory/tests/test-v3-migration-schema.mjs
```

A live PostgreSQL is never required for these tests. To validate the adapters
against a real database, point `PG*` at the compose instance and pass a
`pg`-backed client (`createPgPoolClient()` from `src/adapters/persistence/sql/db.ts`);
the driver is loaded lazily and is intentionally **not** bundled into the runtime
artifact.

---

## Schema overview — V4 outbox & idempotency

V4 adds the two cross-cutting infrastructure tables the durable Factory needs
once commands cross the wire: a transactional outbox for effects that leave the
database, and a tenant-scoped idempotency/dedupe store for command submission.
Both tables are tenant-scoped (`organization_id NOT NULL DEFAULT 'default'`
matching V1/V2/V3) and reuse the shared `set_updated_at()` trigger function from
V2.

| Table | Primary key | Notable constraints |
|---|---|---|
| `outbox_events` | `(organization_id, id)` | `status CHECK IN ('pending','dispatched','failed')`; `attempts >= 0`; append-style log (no `updated_at`) |
| `idempotency_records` | `(organization_id, idempotency_key)` | explicit `UNIQUE (organization_id, idempotency_key)`; `status CHECK IN ('processing','completed','failed')`; `updated_at` trigger |

### `outbox_events`

Transactional outbox for effects that leave the database — webhooks, agent
calls, any external system. A row is inserted in the **same transaction** as the
state change it announces; a drain worker later claims and dispatches it, then
records the outcome.

* Identity: `PRIMARY KEY (organization_id, id)` — the event `id` is only unique
  per tenant, so a client-generated id can never collide across organizations.
* `workstream_id` is nullable: the event is not always tied to a workstream.
* `event_type`, `payload` (JSONB, `DEFAULT '{}'::jsonb`) and `status`
  (`DEFAULT 'pending'`, `CHECK (status IN ('pending', 'dispatched', 'failed'))`)
  describe the pending effect.
* `attempts INTEGER NOT NULL DEFAULT 0` with `CHECK (attempts >= 0)` is the drain
  retry counter.
* `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`; `dispatched_at` is
  nullable and set on success.
* Drain index: `idx_outbox_events_drain ON outbox_events (organization_id, status, created_at)`,
  so the worker can cheaply scan `WHERE organization_id = ? AND status = 'pending'
  ORDER BY created_at`. It is tenant-scoped and covers the `(status, created_at)`
  drain ordering.
* No `updated_at` and no trigger: an event is never edited, only its status is
  advanced.

### `idempotency_records`

Tenant-scoped dedupe / response cache keyed by the client-supplied idempotency
key. It blocks duplicate side effects and detects key reuse with a divergent
body.

* Identity / uniqueness: `PRIMARY KEY (organization_id, idempotency_key)` plus an
  explicit `UNIQUE (organization_id, idempotency_key)` — the constraint names the
  collision-detection guarantee (mirroring the `uq_workstreams` convention in
  V2). A second submission of the same key by the same tenant collides at the
  database level.
* `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'` scopes the record to a
  workstream.
* `request_hash VARCHAR(64) NOT NULL` fingerprints the command body so a replayed
  key with a divergent body is rejected instead of silently re-executed.
* `resource_ref VARCHAR(255)` is the optional reference of the resource produced,
  and `response_payload JSONB NOT NULL DEFAULT '{}'::jsonb` caches the first
  response so an identical replay is answered without re-running the operation.
* `status VARCHAR(64) NOT NULL DEFAULT 'processing'` with
  `CHECK (status IN ('processing', 'completed', 'failed'))` tracks the lifecycle.
* `created_at` / `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`;
  `updated_at` is maintained by the `trg_idempotency_records_updated_at`
  `BEFORE UPDATE` trigger calling `set_updated_at()`.
* Supporting index `idx_idempotency_records_workstream ON
  idempotency_records (organization_id, workstream_id)` for the per-workstream
  operations view.

### Design rationale

* **Amendment 2 (idempotency & request hashing):** `request_hash` alongside the
  idempotency key lets a replay be distinguished (identical body → cached
  response) from a collision (divergent body → conflict) without re-executing the
  command.
* **Amendment 4 (resource locking & operations):** `resource_ref`, `status` and
  `workstream_id` track the operation a key is bound to, so clients can poll the
  produced resource and the record can be reclaimed.
* **Amendment 7 (outbox for external effects):** `outbox_events` confines
  non-transactional external calls to a drain worker: state and intent are
  committed together, and delivery is retried (`attempts`) idempotently from the
  durable backlog — the database is the single source of truth for pending
  effects.

### Validate the V4 migration offline

The V4 schema is validated without PostgreSQL, Docker or the `pg` driver by
parsing V1 + V2 + V3 + V4 and asserting tables, columns, PK/uniqueness, the drain
index, CHECK constraints, defaults and the `updated_at` trigger:

```bash
node factory/tests/test-v4-migration-schema.mjs
```
