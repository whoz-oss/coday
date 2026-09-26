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
    ├── V4__outbox_and_idempotency.sql       # Jalon B2 support tables (outbox + idempotency)
    ├── V5__evidence_and_interaction.sql     # Jalon B2 evidence & human interaction control plane
    ├── V6__artifacts_oracle_agentstep.sql   # Jalon B2 artifacts, oracle, agent-step attempts & worker/environment
    └── V7__lease_protocol.sql                # Jalon C1 lease protocol (heartbeat, expiry) & monotone fencing token
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
* `V5__evidence_and_interaction.sql` adds the evidence & human interaction control
  plane: the append-only `workflow_evidence` log, the mutable
  `human_interactions` aggregate root and the append-only
  `human_interaction_events` log (see below).
* `V6__artifacts_oracle_agentstep.sql` adds the artifacts aggregate (retention,
  purge, legal hold), the mutable `oracle_executions` aggregate, the agent-step
  attempt aggregate (`agent_step_attempts` + `agent_step_attempt_events` +
  `agent_step_results` + `result_capabilities`) and the worker/environment
  reservation skeletons (`work_units`, `work_environments`, `workers`,
  `work_unit_leases`) (see below).
* `V7__lease_protocol.sql` layers the Jalon C1 lease protocol on top of the V6
  skeletons (ALTER-only): active lease lifecycle on `work_unit_leases`, worker
  liveness/capabilities on `workers`, priority/deferral/attempt tracking on
  `work_units`, and the base-guaranteed monotone fencing token sequence
  `work_unit_lease_fencing_seq` (see below).
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

---

## Schema overview — V5 evidence & human interaction

V5 adds the control plane persistence for cross-cutting evidence and for human
decision nodes in a workflow. It is built so the whole decision — human answer,
its evidence, the workflow step/instance state transition, the interaction
closure and the outbox event — can be committed in **one** PostgreSQL
transaction. All three tables are tenant-scoped (`organization_id NOT NULL
DEFAULT 'default'` matching V1/V2/V3/V4) and reuse the shared `set_updated_at()`
trigger function from V2.

| Table | Primary key | Notable constraints |
|---|---|---|
| `workflow_evidence` | `(organization_id, workstream_id, namespace_id, workflow_id, evidence_id)` | composite FK → `workflow_instances` (`ON DELETE CASCADE`); append-only (no `updated_at`) |
| `human_interactions` | `(organization_id, workstream_id, namespace_id, workflow_id, interaction_id)` | composite FK → `workflow_instances` (`ON DELETE CASCADE`); `revision >= 1`; `status CHECK IN ('waiting','answered','closed')`; `updated_at` trigger |
| `human_interaction_events` | `(organization_id, workstream_id, namespace_id, workflow_id, interaction_id, event_id)` | composite FK → `human_interactions` (`ON DELETE CASCADE`); append-only (no `updated_at`) |

### `workflow_evidence`

Append-only cross-cutting evidence log. One immutable row per evidence item
attached to a workflow instance (Jalon B Amendment 3: evidence is captured as an
attributable record and never edited in place).

* Identity: `PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, evidence_id)`.
* Composite FK `(organization_id, workstream_id, namespace_id, workflow_id)` →
  `workflow_instances` (`ON DELETE CASCADE`): the database rejects an orphan or
  cross-tenant / cross-workstream / cross-namespace evidence row and purges the
  evidence with its instance (Amendment 8).
* `evidence_type`, `source` and `producer` are `VARCHAR(255) NOT NULL` and
  describe what the evidence is, where it came from and who produced it.
* `payload JSONB NOT NULL DEFAULT '{}'::jsonb` holds the evidence body verbatim.
* `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`.
* **Append-only:** there is deliberately **no** `updated_at` column and **no**
  update trigger — a correction is a new evidence row with its own `evidence_id`.
* Supporting index `idx_workflow_evidence_instance ON workflow_evidence
  (organization_id, workstream_id, namespace_id, workflow_id, created_at)` for
  the chronological per-instance listing.

### `human_interactions`

Mutable aggregate root of a human decision node (a question awaiting an answer).
Unlike the evidence log it is updated in place as the human answers.

* Identity: `PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, interaction_id)`.
* Composite FK `(organization_id, workstream_id, namespace_id, workflow_id)` →
  `workflow_instances` (`ON DELETE CASCADE`) — the interaction always belongs to
  the workflow instance that raised it (Amendment 8).
* `interaction_type VARCHAR(255) NOT NULL` classifies the decision node.
* `status VARCHAR(64) NOT NULL DEFAULT 'waiting'` with
  `CHECK (status IN ('waiting', 'answered', 'closed'))` tracks the lifecycle.
* `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)` is the
  optimistic-locking column: writers `UPDATE … WHERE revision = $expected` and
  surface a conflict when zero rows match.
* `payload JSONB NOT NULL DEFAULT '{}'::jsonb` holds the question/answer body.
* `created_at` / `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`;
  `updated_at` is maintained by the `trg_human_interactions_updated_at`
  `BEFORE UPDATE` trigger calling `set_updated_at()`.
* Supporting index `idx_human_interactions_instance ON human_interactions
  (organization_id, workstream_id, namespace_id, workflow_id, status)` for the
  pending queue (`… AND status = 'waiting'`).

### `human_interaction_events`

Append-only event log recording the lifecycle of a human interaction (creation,
response, closure). One immutable row per event.

* Identity: `PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, interaction_id, event_id)`.
* Composite FK `(organization_id, workstream_id, namespace_id, workflow_id,
  interaction_id)` → `human_interactions` (`ON DELETE CASCADE`): an event can
  never be attached to an interaction of another tenant / workstream / namespace,
  and is removed with its interaction (Amendment 8).
* `event_type VARCHAR(255) NOT NULL` and `actor_id VARCHAR(255) NOT NULL` record
  what happened and who did it.
* `payload JSONB NOT NULL DEFAULT '{}'::jsonb` holds the event body verbatim.
* `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`.
* **Append-only:** **no** `updated_at` column and **no** update trigger.
* Supporting index `idx_human_interaction_events_interaction ON
  human_interaction_events (organization_id, workstream_id, namespace_id,
  workflow_id, interaction_id, created_at)` for the chronological audit trail.

### Design rationale

* **Amendment 3 (append-only evidence log):** `workflow_evidence` and
  `human_interaction_events` carry no `updated_at` and no update trigger, so a
  record can only be appended, never rewritten — the audit trail is immutable by
  construction and a correction is a new row.
* **Amendment 8 (composite FKs for tenant isolation):** every child references
  its parent by the full `(organization_id, workstream_id, namespace_id,
  workflow_id[, interaction_id])` key with `ON DELETE CASCADE`, so the database
  itself rejects an orphan or cross-tenant record and purges children with their
  parent.
* **Amendment 2 (atomic multi-table transactions):** the schema intentionally
  contains no blocking trigger, deferred constraint or locking hook that would
  prevent a single PostgreSQL transaction from combining the human decision, its
  evidence row, the workflow step/instance state transition (revision increment),
  the interaction closure and the outbox event. `trg_human_interactions_updated_at`
  only touches the row being updated (`NEW.updated_at = CURRENT_TIMESTAMP`) — no
  extra lock, no side effect — so the commit stays atomic.

### Validate the V5 migration offline

The V5 schema is validated without PostgreSQL, Docker or the `pg` driver by
parsing V1 + V2 + V3 + V4 + V5 and asserting tables, columns, defaults, composite
PKs and FKs (FK targets matched against the referenced PK), supporting indexes,
CHECK constraints, the append-only guarantees and the `updated_at` trigger:

```bash
node factory/tests/test-v5-migration-schema.mjs
```

---

## Schema overview — V6 artifacts, oracle, agent-step & worker/environment

V6 closes the Jalon B2 persistence surface: artifact metadata with retention &
legal hold, oracle execution state, the agent-step attempt aggregate, and the
identifier/relationship skeletons for the worker & environment control plane.
All ten tables are tenant-scoped (`organization_id NOT NULL DEFAULT 'default'`
matching V1..V5) and reuse the shared `set_updated_at()` trigger function from
V2.

| Table | Primary key | Notable constraints |
|---|---|---|
| `artifacts` | `(organization_id, workstream_id, namespace_id, workflow_id, artifact_id)` | composite FK → `workflow_instances` (`ON DELETE CASCADE`); 3 orthogonal status columns; `CHECK` anti-purge sous legal hold; `updated_at` trigger |
| `oracle_executions` | `(organization_id, workstream_id, namespace_id, workflow_id, execution_id)` | composite FK → `workflow_instances` (`ON DELETE CASCADE`); `revision >= 1`; `status CHECK IN ('running','succeeded','failed','cancelled')`; `updated_at` trigger |
| `agent_step_attempts` | `(organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id)` | composite FK → `workflow_instances` (`ON DELETE CASCADE`); `revision >= 1`; `status CHECK IN ('running','completed','failed','timed_out','cancelled')`; `updated_at` trigger |
| `agent_step_attempt_events` | `(organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, event_id)` | composite FK → `agent_step_attempts` (`ON DELETE CASCADE`); append-only (no `updated_at`) |
| `agent_step_results` | `(organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, result_id)` | composite FK → `agent_step_attempts` (`ON DELETE CASCADE`); `result_status CHECK IN ('success','failure','collision_detected')`; append-only |
| `result_capabilities` | `(organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, result_id, capability_id)` | composite FK → `agent_step_results` (`ON DELETE CASCADE`); append-only |
| `work_units` | `(organization_id, workstream_id, work_unit_id)` | composite FK → `workstreams` (`ON DELETE CASCADE`); `revision >= 1`; `status CHECK IN ('created','assigned','running','completed','failed','cancelled')`; `updated_at` trigger |
| `work_environments` | `(organization_id, workstream_id, environment_id)` | composite FK → `workstreams` (`ON DELETE CASCADE`); `revision >= 1`; `status CHECK IN ('provisioning','ready','busy','decommissioned')`; `updated_at` trigger |
| `workers` | `(organization_id, worker_id)` | composite FK → `organizations` (`ON DELETE CASCADE`); `revision >= 1`; `status CHECK IN ('offline','idle','busy','maintenance')`; `updated_at` trigger |
| `work_unit_leases` | `(organization_id, workstream_id, work_unit_id, lease_id)` | composite FK → `work_units` (`ON DELETE CASCADE`); `status CHECK IN ('active','released','expired')`; append-only log |

### `artifacts` — three orthogonal status dimensions (Amendments 9 & 5)

The artifact lifecycle is deliberately modelled as three **independent and
orthogonal** dimensions rather than a single enum, so orthogonal facts never
overwrite each other:

* `availability_status VARCHAR(64) NOT NULL DEFAULT 'pending'`
  (`CHECK IN ('pending','uploading','available','unavailable','purged')`) — where
  the bytes are.
* `retention_status VARCHAR(64) NOT NULL DEFAULT 'active'`
  (`CHECK IN ('active','expired')`) — whether the retention window is still open.
* `legal_hold BOOLEAN NOT NULL DEFAULT FALSE` — an independent compliance lock.

The metadata and storage coordinates are `content_hash` (content-addressed hash,
e.g. sha-256), `size BIGINT`, `content_type`, `storage_key` (immutable storage
key) and `payload JSONB` (verbatim domain metadata, kept even after a purge).
Retention/purge/hold bookkeeping uses the nullable `retention_until`, `purged_at`,
`purge_reason`, `legal_hold_reason` and `legal_hold_set_at`.

**Golden rule (anti-purge under legal hold):**

```sql
CONSTRAINT artifacts_legal_hold_purge_check
  CHECK (NOT (legal_hold = TRUE AND availability_status = 'purged'))
```

The database therefore rejects any attempt to mark an artifact under legal hold
as `purged`: purging keeps the row (and its `payload`) and only advances
`availability_status`. The supporting index
`idx_artifacts_instance (organization_id, workstream_id, namespace_id,
workflow_id, availability_status)` serves the per-instance availability listing.

### `oracle_executions` — mutable oracle run

One row per oracle run attached to a workflow instance. `oracle_id` names the
oracle, `status` tracks `running → succeeded/failed/cancelled`, and `revision` is
the optimistic-locking column. `evidence_id` and `artifact_id` are optional soft
references to the evidence / artifact produced by the run (the relational
models live in `workflow_evidence` and `artifacts`). Supporting index
`idx_oracle_executions_instance (organization_id, workstream_id, namespace_id,
workflow_id, status)`.

### `agent_step_attempts` aggregate (Amendment 4)

An attempt is a single aggregate whose parts share one common transactional
boundary. The full attempt identity `(organization_id, workstream_id,
namespace_id, workflow_id, step_id, attempt_id)` is carried by every child, and
the mutable root carries `agent_id`, a lifecycle `status`
(`running/completed/failed/timed_out/cancelled`), an optimistic-locking
`revision` and an optional `idempotency_key` soft-link to `idempotency_records`
(V4). Supporting index `idx_agent_step_attempts_step (organization_id,
workstream_id, namespace_id, workflow_id, step_id, status)`.

Three physically distinct child tables complete the aggregate:

* `agent_step_attempt_events` — append-only event log (`event_type`, `payload`).
* `agent_step_results` — append-only results table, with `result_status`
  (`success/failure/collision_detected`) and `semantic_signature` used for
  `RESULT_SEMANTIC_COLLISION` detection.
* `result_capabilities` — append-only declared capabilities/effects of a result
  (`capability_type`, `payload`).

All three are append-only: **no** `updated_at` column and **no** update trigger.

### Worker / environment reservation skeletons (Amendment 6)

These four tables reserve composite identities and tenant-scoped relationships
only — **no** scheduler, **no** lease acquisition/renewal/expiry, **no** fencing
tokens and **no** heartbeats (those belong to Jalon C):

* `work_units` — mutable work unit / compute task skeleton
  (`unit_type`, `status`, `revision`).
* `work_environments` — mutable execution environment / sandbox skeleton
  (`env_type`, `status`, `revision`).
* `workers` — mutable worker node skeleton, organization-scoped
  (`worker_type`, `status`, `revision`).
* `work_unit_leases` — appointment/reservation log linking a work unit to a
  worker and optionally an environment. It is a plain append log (no `updated_at`,
  no active lease mechanics).

### Design rationale

* **Amendment 9 (orthogonal status dimensions):** availability, retention and
  legal hold are three separate columns with separate CHECKs, so a retention
  expiry can never be confused with a byte-availability change or a compliance
  hold.
* **Amendment 5 (retention, purge & legal hold):** `retention_until`,
  `purged_at` / `purge_reason` and the `legal_hold*` columns make retention
  auditable, and `artifacts_legal_hold_purge_check` forbids purging under hold at
  the database level.
* **Amendment 4 (attempt aggregate & single transactional boundary):** the
  attempt root plus events, results and capabilities all reference the same
  attempt identity, so a single PostgreSQL transaction can commit the attempt
  state, its events, its result(s), the declared capabilities and the outbox
  event atomically.
* **Amendment 6 (worker/environment reservation):** identity and relationship
  skeletons are reserved now (with tenant-scoped composite FKs), while the active
  scheduling/leasing mechanics are deferred to Jalon C.
* **Amendment 8 (composite FKs for tenant isolation):** every child references
  its parent by the full identity key with `ON DELETE CASCADE`, so the database
  itself rejects an orphan or cross-tenant record and purges children with their
  parent.

### Validate the V6 migration offline

The V6 schema is validated without PostgreSQL, Docker or the `pg` driver by
parsing V1 + V2 + V3 + V4 + V5 + V6 and asserting tables, columns, defaults,
composite PKs and FKs (FK targets matched against the referenced PK), supporting
indexes, CHECK constraints (the three orthogonal artifact dimensions and the
anti-purge rule), the append-only guarantees and the `updated_at` triggers:

```bash
node factory/tests/test-v6-migration-schema.mjs
```

---

## Schema overview — V7 lease protocol, heartbeat & monotone fencing (Jalon C1)

V7 layers the Jalon C1 lease protocol on top of the V6 worker / environment
reservation skeletons. It is **ALTER-only**: it never recreates a table and never
drops or retypes a V1..V6 column — it only adds columns, indexes, CHECK
constraints and a dedicated sequence. The three extended tables stay tenant-scoped
(`organization_id NOT NULL DEFAULT 'default'` matching V1..V6).

| Table | Columns added by V7 | Indexes added by V7 |
|---|---|---|
| `work_unit_leases` | `fencing_token BIGINT`, `acquired_at TIMESTAMPTZ`, `lease_expires_at TIMESTAMPTZ`, `heartbeat_at TIMESTAMPTZ`, `released_at TIMESTAMPTZ`, `expiry_reason VARCHAR(255)` | `idx_work_unit_leases_acquisition (organization_id, workstream_id, work_unit_id, status)`, `idx_work_unit_leases_expiry (organization_id, lease_expires_at)` |
| `workers` | `last_heartbeat_at TIMESTAMPTZ`, `protocol_version VARCHAR(64)`, `capabilities JSONB NOT NULL DEFAULT '[]'::jsonb` | — (CHECK `jsonb_typeof(capabilities) = 'array'`) |
| `work_units` | `priority INTEGER NOT NULL DEFAULT 0`, `not_before TIMESTAMPTZ` (nullable), `attempt_count INTEGER NOT NULL DEFAULT 0` | `idx_work_units_eligibility (organization_id, workstream_id, status, priority DESC, not_before)` |

### `work_unit_leases` — active lease lifecycle

V6 left `work_unit_leases` as a plain appointment log with no active mechanics
(Amendment 6). V7 adds the lifecycle bookkeeping that makes a lease *active*:

* `fencing_token BIGINT` — the monotone token (see below).
* `acquired_at TIMESTAMPTZ` — when the worker took the lease.
* `lease_expires_at TIMESTAMPTZ` — deadline after which the lease is reclamable.
* `heartbeat_at TIMESTAMPTZ` — timestamp of the last successful heartbeat.
* `released_at TIMESTAMPTZ` — when the worker (or the reaper) released it.
* `expiry_reason VARCHAR(255)` — machine-readable reason for an `expired`
  transition (e.g. `heartbeat_timeout`, `worker_lost`).

All six columns are nullable so existing V6 rows survive the ALTER untouched.
`idx_work_unit_leases_acquisition` serves the "is there an active lease for this
work unit?" lookup (`… status = 'active'`), and `idx_work_unit_leases_expiry`
serves the reaper scan over `lease_expires_at`.

### Monotone fencing token — guaranteed by the database

A fencing token must be strictly increasing even across concurrent transactions
and worker nodes. A `MAX(token) + 1` read inside a transaction can hand the same
token to two concurrent acquisitions, or an older token to the transaction that
commits last — which defeats fencing entirely (a stale worker could then hold a
valid-looking token and overwrite a fresh lease).

V7 therefore introduces a dedicated PostgreSQL **sequence** as the single
authoritative source of tokens:

```sql
CREATE SEQUENCE IF NOT EXISTS work_unit_lease_fencing_seq
  START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
```

* `nextval('work_unit_lease_fencing_seq')` is **non-transactional** (never rolled
  back), concurrency-safe and always strictly increasing for a positive
  `INCREMENT BY 1` — the core fencing property, enforced by the database itself.
* The sequence is wired as the column default, so an insert that omits
  `fencing_token` still receives a monotone value automatically:

  ```sql
  ALTER TABLE work_unit_leases
    ALTER COLUMN fencing_token SET DEFAULT nextval('work_unit_lease_fencing_seq');
  ```

  A caller may still capture the token explicitly (`nextval(...)`) inside the
  acquisition transaction; an explicit value always takes precedence.
* `BIGINT` matches the 64-bit sequence range; `CACHE 1` avoids burning cached
  values on a crash.

The choice (a sequence rather than a table-based counter) is documented in the
migration's SQL comments.

### `workers` — liveness, protocol version & capabilities

* `last_heartbeat_at TIMESTAMPTZ` — last heartbeat observed by the control plane;
  a worker whose heartbeat is stale is reaped.
* `protocol_version VARCHAR(64)` — the lease-protocol version the worker speaks,
  for negotiation / rejecting incompatible nodes.
* `capabilities JSONB NOT NULL DEFAULT '[]'::jsonb` — the capability keys the
  worker declares (e.g. `["nodejs","docker"]`), constrained to a JSON array by
  `workers_capabilities_array_check` and used for eligibility matching.

### `work_units` — priority scheduling, deferral & attempt tracking

* `priority INTEGER NOT NULL DEFAULT 0` — scheduling weight (higher first);
  existing rows become immediately schedulable.
* `not_before TIMESTAMPTZ` (nullable) — execution gate: the unit is not eligible
  before this instant (`NULL` = eligible now).
* `attempt_count INTEGER NOT NULL DEFAULT 0` — retry accounting, constrained by
  `work_units_attempt_count_check CHECK (attempt_count >= 0)`.

`idx_work_units_eligibility (organization_id, workstream_id, status, priority
DESC, not_before)` backs the eligible-work-unit scan used by
`SELECT … FOR UPDATE SKIP LOCKED`:

```sql
WHERE organization_id = ? AND workstream_id = ? AND status = 'created'
  AND (not_before IS NULL OR not_before <= now())
ORDER BY priority DESC, not_before
```

The index leads with the equality predicates and then carries the ordering
columns, so the scan and its ordering are served from the index.

### Design rationale

* **C1 — base-guaranteed fencing:** the monotone token lives in a PostgreSQL
  sequence (`nextval` is non-transactional), so token ordering can never be
  broken by transaction interleaving — the database, not the application, is the
  authority.
* **C1 — heartbeat / expiry:** explicit `heartbeat_at`, `lease_expires_at`,
  `released_at` and `expiry_reason` make lease liveness and reclamation
  auditable and let a reaper reclaim expired leases deterministically.
* **C1 — ordered, deferred scheduling:** `priority` / `not_before` express
  scheduling order and time gating, served by a purpose-built eligibility index.
* **Non-destructive extension:** V7 only ADDs (columns with `IF NOT EXISTS`,
  indexes with `IF NOT EXISTS`, one sequence with `IF NOT EXISTS`, two additive
  CHECK constraints); it never touches the V1..V6 tables' existing columns.

### Validate the V7 migration offline

The V7 migration is validated without PostgreSQL, Docker or the `pg` driver by
parsing the cumulated V1 + V2 + V3 + V4 + V5 + V6 + V7 schema and asserting the
added columns (type / NOT NULL / default), the added indexes (including
`priority DESC`), the fencing-token sequence definition **and the strictly
increasing behaviour of a `nextval` simulation**, the new CHECK constraints, and
that tenant isolation and previous tables are preserved:

```bash
node factory/tests/test-v7-migration-schema.mjs
```

---

## Local worker runtime (Jalon C2)

The C2 worker runtime is a thin loop that claims a work unit through the C1
lease protocol, runs an injected `WorkExecutor` and releases the lease (which
commits the terminal work-unit status atomically). The domain lives in
`factory/src/domain/worker-runtime/` (frozen, C2-T1); the local entrypoint
`factory/src/entrypoints/worker-runtime.ts` (C2-T2) wires it against the **real**
SQL persistence adapters:

| Piece | Source |
|---|---|
| Loop + vocabulary | `factory/src/domain/worker-runtime/{worker-runtime,types}.ts` |
| Local entrypoint / wiring | `factory/src/entrypoints/worker-runtime.ts` |
| SQL client (`pg` pool) | `createPgPoolClient` in `factory/src/adapters/persistence/sql/db.ts` |
| SQL repositories | `createSqlLeaseRepository`, `createSqlWorkUnitRepository`, `createSqlWorkerRepository` in `factory/src/adapters/persistence/sql/index.ts` |
| Bundle surface | `factory/runtime/factory-operational.mjs` (generated) |
| Stateless JS facade | `factory/lib/worker-runtime.mjs` |

`createLocalWorkerRuntime()` builds the three SQL repositories on top of a
`createPgPoolClient()` pool and returns a handle
`{ runtime, client, executor, config, start, stop }`. `runLocalWorker()` does the
same and starts the loop immediately. Both default to a **deterministic demo
executor** (`createDemoWorkExecutor`): it logs the work unit, waits a few
milliseconds and reports `completed` with an `executor: 'demo-echo'` payload
patch. It performs **no** ADW dispatch, no network and no filesystem I/O — it is
a scaffold for local runs only.

### Prerequisites

1. Start the disposable PostgreSQL (see the top of this file):

   ```bash
   docker compose -f factory/infra/docker-compose.yml up -d
   docker compose -f factory/infra/docker-compose.yml logs -f flyway   # wait for "Successfully applied"
   ```

2. Provide the `pg` driver in the runtime environment. The runtime artifact
   deliberately does **not** bundle a SQL driver (`createPgPoolClient` loads it
   lazily), so install it where the worker process resolves modules:

   ```bash
   npm install pg          # or: pnpm add -w pg
   ```

### Environment variables

| Variable | Used by | Default |
|---|---|---|
| `PGHOST` | `createPgPoolClient` / `resolveSqlDatabaseConfig` | `localhost` |
| `PGPORT` | idem | `5432` |
| `PGDATABASE` | idem | `coday_factory` |
| `PGUSER` | idem | `factory` |
| `PGPASSWORD` | idem | `factory_dev_pass` |
| `PGPOOL_MAX` | idem (pool size) | `10` |
| `PGSSL` | idem (`true` enables TLS) | `false` |
| `WORKER_ID` | `createLocalWorkerRuntime` (`config.workerId`) | `local-worker-1` |
| `LEASE_TTL_MS` | `createLocalWorkerRuntime` (`config.leaseTtlMs`) | `30000` |

`organizationId` / `workstreamId` default to `'default'` (the tenant the SQL
adapters are scoped to); override them with the corresponding
`createLocalWorkerRuntime({ organizationId, workstreamId })` options.

### Launch the worker

Run this from the repository root (it resolves `factory/lib/worker-runtime.mjs`
relative to the current directory):

```bash
node --input-type=module <<'EOF'
import { createDemoWorkExecutor, createLocalWorkerRuntime } from './factory/lib/worker-runtime.mjs'

const worker = await createLocalWorkerRuntime({
  workerId: process.env.WORKER_ID ?? 'local-worker-1',
  // Deterministic demo executor — no real ADW task is ever dispatched.
  // Raise delayMs temporarily if you want to observe the `running` state.
  executor: createDemoWorkExecutor({ delayMs: 50 }),
})

await worker.start()
console.log(`worker ${worker.config.workerId} running (org=${worker.config.organizationId}, ws=${worker.config.workstreamId})`)

const shutdown = async () => {
  await worker.stop({ drainTimeoutMs: 5000 })
  process.exit(0)
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
EOF
```

### Enqueue a demo work unit

The worker polls `work_units` rows in status `created` (or retryable `failed`).
`work_units` has a composite FK onto `workstreams`, so create the default tenant
first (both statements are idempotent):

```sql
-- Tenant prerequisites (once).
INSERT INTO organizations (organization_id, name)
  VALUES ('default', 'Default') ON CONFLICT (organization_id) DO NOTHING;
INSERT INTO workstreams (organization_id, workstream_id, name)
  VALUES ('default', 'default', 'Default') ON CONFLICT (organization_id, workstream_id) DO NOTHING;

-- The demo work unit the worker will claim.
INSERT INTO work_units (organization_id, workstream_id, work_unit_id, unit_type, status, priority, payload)
  VALUES ('default', 'default', 'wu-demo-1', 'demo-echo', 'created', 10, '{"message":"Hello Worker"}'::jsonb)
  ON CONFLICT (organization_id, workstream_id, work_unit_id) DO NOTHING;
```

Run it against the compose instance:

```bash
docker compose -f factory/infra/docker-compose.yml exec coday-postgres \
  psql -U factory -d coday_factory -c "<the SQL above>"
```

Alternatively, enqueue it from JavaScript with the same pool factory:

```bash
node --input-type=module <<'EOF'
import { createPgPoolClient } from './factory/runtime/factory-operational.mjs'

const client = await createPgPoolClient()
await client.query(`INSERT INTO organizations (organization_id, name)
  VALUES ('default', 'Default') ON CONFLICT (organization_id) DO NOTHING`)
await client.query(`INSERT INTO workstreams (organization_id, workstream_id, name)
  VALUES ('default', 'default', 'Default') ON CONFLICT (organization_id, workstream_id) DO NOTHING`)
await client.query(`INSERT INTO work_units
  (organization_id, workstream_id, work_unit_id, unit_type, status, priority, payload)
  VALUES ($1, $2, $3, $4, 'created', $5, $6::jsonb)`,
  ['default', 'default', 'wu-demo-1', 'demo-echo', 10, JSON.stringify({ message: 'Hello Worker' })])
console.log('enqueued wu-demo-1')
EOF
```

### Observe the lifecycle

Poll the work unit and its lease (from another terminal):

```sql
SELECT work_unit_id, status, attempt_count, payload, updated_at
  FROM work_units WHERE work_unit_id = 'wu-demo-1';

SELECT lease_id, status, fencing_token, lease_expires_at, released_at
  FROM work_unit_leases WHERE work_unit_id = 'wu-demo-1' ORDER BY fencing_token;
```

The expected lifecycle is:

1. **`created`** — right after the `INSERT` above.
2. **`running`** — the worker's `acquire` claims the unit and inserts an `active`
   row in `work_unit_leases` with the next monotone `fencing_token`; the worker
   row flips to `busy`. With the default demo delay (~50ms) this state is brief —
   pass a larger `delayMs` to `createDemoWorkExecutor` to observe it.
3. **`completed`** — the demo executor reports success, the payload is patched
   with `{"executor":"demo-echo","executedAt":"…"}`, and the lease `release`
   commits the terminal status; the lease row becomes `released` and the worker
   returns to `idle`.

You can also watch the worker's own log lines, e.g.
`demo executor: start (no real ADW dispatch)` then `demo executor: completed`.

### Stop cleanly

Send `SIGINT`/`SIGTERM` to the worker process (the snippet above drains with
`worker.stop({ drainTimeoutMs: 5000 })`), or call `await worker.stop()` yourself.
`stop()` stops claiming new work, lets in-flight executions finish (or aborts
them past the drain timeout, releasing their lease back to `created`) and marks
the worker `offline`.

### Offline verification

The whole C2-T2 surface is covered **without** PostgreSQL, Docker or the `pg`
driver by driving the real SQL adapters through the in-memory `SqlClient`:

```bash
node factory/tests/test-worker-runtime-entrypoint.mjs   # demo executor, wiring, lifecycle, facade
node factory/tests/test-worker-runtime-core.mjs         # frozen C2-T1 loop (fencing, drain, failure)
```
