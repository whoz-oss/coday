# Implementation Plan - Jalon B - Vague B1 - Tâche T1: PostgreSQL Local Foundations & SQL Repository Adapters Skeleton

## 1. Overview & Objectives

Establish the local PostgreSQL containerized infrastructure, SQL migration framework (Flyway), database schemas for pilot aggregates (`WorkflowDefinition` and `WorkflowInstance`), and SQL repository adapters skeleton adhering strictly to tenant scoping (`organization_id`, `workstream_id`), optimistic locking (`revision`), and domain interfaces (`WorkflowDefinitionRepository` and `WorkflowInstanceRepository`).

In addition, create a shared contract test runner validating that both Filesystem and SQL adapters fulfill the exact same behavioral specifications without regressions.

All work is confined strictly within `factory/` (specifically `factory/infra/`, `factory/src/adapters/persistence/sql/`, and `factory/tests/`). No files outside `factory/` or domain port interfaces will be changed.

---

## 2. Directory Structure & File Map

```
factory/
├── infra/
│   ├── docker-compose.yml              # Local Dev & Test PostgreSQL setup (pgvector/postgres 16, port 5432, volumes, healthcheck)
│   ├── README.md                       # Documentation for dev DB setup & migrations
│   └── migrations/
│       └── V1__init_workflow_pilot_schema.sql  # Initial DDL migration script for definitions & instances
├── src/
│   ├── adapters/
│   │   └── persistence/
│   │       └── sql/
│   │           ├── db.ts               # Connection pool and helper utilities for PG client
│   │           ├── sql-workflow-definition-repository.ts  # SqlWorkflowDefinitionRepository adapter implementation
│   │           ├── sql-workflow-instance-repository.ts    # SqlWorkflowInstanceRepository adapter implementation
│   │           └── index.ts            # Public exports for sql persistence adapters
│   └── index.ts (or persistence/index.ts)  # Re-export SQL adapters if appropriate
└── tests/
    ├── test-repository-contract.mjs     # Shared contract tests executed against both FS and SQL (mock/in-memory driver or live PG fallback)
    └── test-sql-repository-ports-adapters.mjs  # Dedicated runner for SQL contract & unit tests
```

---

## 3. Schema & Migration Specifications

### `factory/infra/migrations/V1__init_workflow_pilot_schema.sql`

1. **`workflow_definitions` Table**:
   - `workflow_type` VARCHAR(255) NOT NULL
   - `version` VARCHAR(64) NOT NULL
   - `organization_id` VARCHAR(255) NOT NULL DEFAULT 'default'
   - `workstream_id` VARCHAR(255) NULL -- optional for definitions (platform / org / workstream scoping)
   - `definition_hash` VARCHAR(64) NOT NULL
   - `definition_json` JSONB NOT NULL
   - `created_at` TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
   - **Primary Key**: `(organization_id, workflow_type, version)`
   - **Index**: Unique index on `(workflow_type, version)` where `organization_id = 'default'` (or scoped lookup), index on `(organization_id, workstream_id)`.

2. **`workflow_instances` Table**:
   - `namespace_id` VARCHAR(255) NOT NULL -- maps to case/namespace boundary
   - `workflow_id` VARCHAR(255) NOT NULL
   - `organization_id` VARCHAR(255) NOT NULL DEFAULT 'default'
   - `workstream_id` VARCHAR(255) NOT NULL DEFAULT 'default'
   - `revision` INTEGER NOT NULL DEFAULT 1 -- optimistic locking
   - `instance_json` JSONB NOT NULL -- domain WorkflowInstance state
   - `projection_json` JSONB NOT NULL -- read projection state
   - `status` VARCHAR(64) NOT NULL -- active, removed, purged, etc.
   - `created_at` TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
   - `updated_at` TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
   - **Primary Key**: `(organization_id, workstream_id, namespace_id, workflow_id)`
   - **Indexes**:
     - `idx_workflow_instances_namespace`: `(organization_id, workstream_id, namespace_id)`
     - `idx_workflow_instances_lookup`: `(namespace_id, workflow_id)`

---

## 4. Container Infrastructure & Tooling Setup

### `factory/infra/docker-compose.yml`
- Service `coday-postgres`:
  - Image: `postgres:16-alpine`
  - Container name: `coday-postgres`
  - Ports: `5432:5432`
  - Environment: `POSTGRES_DB=coday_factory`, `POSTGRES_USER=factory`, `POSTGRES_PASSWORD=factory_dev_pass`
  - Healthcheck: `pg_isready -U factory -d coday_factory`
  - Volumes: `coday_pgdata:/var/lib/postgresql/data`

### `factory/infra/README.md`
- Clear instructions on starting PostgreSQL via `docker compose -f factory/infra/docker-compose.yml up -d`.
- Flyway CLI or container migration commands: `flyway -url=jdbc:postgresql://localhost:5432/coday_factory -user=factory -password=factory_dev_pass -locations=filesystem:factory/infra/migrations migrate`.

---

## 5. Repository Adapters Specification

### `factory/src/adapters/persistence/sql/db.ts`
- Pure, lightweight DB client interface or driver wrapper.
- Uses `pg` (or standard `pg.Pool` or mockable adapter interface `SqlDbClient`).
- Supports query execution, transactions, and parameter serialization/deserialization.
- Accepts connection details from environment variables (`PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`) or a provided mock for testing without external runtime dependencies.

### `factory/src/adapters/persistence/sql/sql-workflow-definition-repository.ts`
- Implements `WorkflowDefinitionRepository`:
  - `list()`: Returns all definitions mapped to `WorkflowDefinitionWithHash[]`, ordered by `workflowType` and `version`.
  - `get(workflowType, version)`: Queries exact match for definition, returns object or `null`.
  - `resolveUnique(workflowType)`: Resolves highest semver/version for `workflowType`. Throws `WorkflowDefinitionRepositoryError(WORKFLOW_DEFINITION_NOT_FOUND)` if missing.

### `factory/src/adapters/persistence/sql/sql-workflow-instance-repository.ts`
- Implements `WorkflowInstanceRepository`:
  - `list(namespaceId)`: Returns `WorkflowProjection[]` for active instances in `namespaceId`.
  - `get(namespaceId, workflowId)`: Reads `WorkflowInstanceSnapshot` (instance + projection) or `null`.
  - `create(namespaceId, command, definition, controllerExecution)`:
    - Inserts initial instance with `revision = 1`.
    - Returns `WorkflowInstanceSnapshot`. Handle idempotency gracefully.
  - `transition(namespaceId, workflowId, transition)`:
    - Performs optimistic locking check (`revision = expectedRevision`).
    - Increments `revision = expectedRevision + 1`.
    - If `revision` mismatch, throws `WorkflowInstanceRepositoryError('REVISION_CONFLICT' or 'CONCURRENCY_ERROR')`.
    - Evaluates policy if provided; throws `WorkflowInstanceRepositoryError(code)` when policy denies transition.
  - `remove(namespaceId, workflowId, actor)`: Updates status to `removed` or soft deletes.
  - `restore(namespaceId, workflowId, actor)`: Restores removed instance.
  - `purge(namespaceId, workflowId, actor)`: Deletes hard from database.

---

## 6. Shared Contract Tests

### `factory/tests/test-repository-contract.mjs`
- Module exporting standard assertion logic for repository ports:
  - `runDefinitionRepositoryContractTests(createRepoFn)`
  - `runInstanceRepositoryContractTests(createRepoFn)`

### `factory/tests/test-sql-repository-ports-adapters.mjs`
- Test runner executing the contract tests against:
  1. `FilesystemWorkflowDefinitionRepository` & `FilesystemWorkflowInstanceRepository` (verifying full parity with existing tests).
  2. `SqlWorkflowDefinitionRepository` & `SqlWorkflowInstanceRepository` (using mock/in-memory PG driver client or live PG connection when present).
- Run existing suite `node factory/tests/test-repository-ports-adapters.mjs` to ensure zero regressions.

---

## 7. Verification Steps

1. **Verify Existing Tests**:
   - `node factory/tests/test-repository-ports-adapters.mjs` must pass 100%.

2. **Verify New SQL Contract Tests**:
   - `node factory/tests/test-sql-repository-ports-adapters.mjs` must run cleanly and pass.

3. **Verify Static Code & Types**:
   - Build/check TypeScript types if applicable in factory module.

---

## 8. Commit & Artifact Instructions

- Plan file saved to: `/work/data/sessions/3a853c9a/context_handoff/plan.md`
- Spec file copied to: `specs/3a853c9a_postgres_foundations_sql_adapters.md`
