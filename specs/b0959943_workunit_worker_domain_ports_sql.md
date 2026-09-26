# Implementation Plan: Jalon C1 Task C1-T1a — Domain, Ports, and SQL Adapters for WorkUnit and Worker

## Overview
This task creates pure domain models, port interfaces, SQL persistence adapters, and a comprehensive conformance test suite for `WorkUnit` and `Worker` entities in `factory/`.
It addresses tenant-scoped persistence and state machines matching tables created in Flyway migrations V6 and V7 (`work_units` and `workers`).

## Affected Scope & Structural Constraints
1. ** Pure Domain Layer**:
   - `factory/src/domain/work-unit.ts` (or `factory/src/domain/work-unit/work-unit.ts` - using single file pattern `factory/src/domain/work-unit.ts` matching existing domain exports like `work-unit-environment.ts` or `workflow-definition.ts`)
   - `factory/src/domain/worker.ts`
   - Zero external / node non-stdlib dependencies (no `node:fs`, `node:child_process`, `git`, or `AgentOS`).

2. ** Ports Layer**:
   - `factory/src/ports/persistence/work-unit-repository.ts`
   - `factory/src/ports/persistence/worker-repository.ts`
   - Tenant-scoped interfaces using domain terms (`organizationId`, `workstreamId` for work unit; `organizationId` for worker).

3. ** SQL Persistence Adapters Layer**:
   - `factory/src/adapters/persistence/sql/sql-work-unit-repository.ts`
   - `factory/src/adapters/persistence/sql/sql-worker-repository.ts`
   - Standard SQL operations over V6/V7 DB schemas using `SqlClient` and optimistic locking via `revision`.

4. ** Conformance Test Suite**:
   - `factory/tests/test-conformance-workunit-worker.mjs`
   - Standalone node script matching B3 / work-environment conformance test style, using `in-memory-sql-client.mjs` and `esbuild` for in-memory TS compilation.

5. ** Strict Constraints **:
   - DO NOT implement or touch lease mechanics (`work_unit_leases` / lease renewal / expiry / acquisition).
   - DO NOT modify barrels: `factory/src/ports/persistence/index.ts`, `factory/src/adapters/persistence/index.ts`, `factory/src/adapters/persistence/sql/index.ts`.
   - DO NOT touch `db.ts`, `unit-of-work.ts`, `in-memory-sql-client.mjs` unless strictly required.
   - DO NOT touch Flyway migrations or generated runtime bundles (`factory-operational.mjs`).
   - DO NOT touch `agentos/`.

---

## Detailed Step-by-Step Design & Specification

### 1. Pure Domain Models

#### `factory/src/domain/work-unit.ts`
- **Lifecycle States**: `WORK_UNIT_STATES = ['created', 'assigned', 'running', 'completed', 'failed', 'cancelled']`
- **State Machine Transitions**:
  - `created` -> `assigned`, `cancelled`
  - `assigned` -> `running`, `created` (unassigned/retry), `cancelled`, `failed`
  - `running` -> `completed`, `failed`, `cancelled`
  - Terminal states: `completed`, `failed`, `cancelled`
- **Types / Interfaces**:
  - `WorkUnitState` = `'created' | 'assigned' | 'running' | 'completed' | 'failed' | 'cancelled'`
  - `WorkUnit`:
    - `workUnitId`: string
    - `unitType`: string
    - `status`: `WorkUnitState`
    - `revision`: number
    - `priority`: number (default 0)
    - `notBefore`: string | null (ISO timestamp)
    - `attemptCount`: number (default 0)
    - `payload`: Record<string, unknown>
    - `createdAt`: string
    - `updatedAt`: string
  - Domain validation functions (e.g., `validateWorkUnit`, `canTransitionWorkUnit`, error codes `WORK_UNIT_ERROR_CODES`: `INVALID_WORK_UNIT`, `INVALID_STATE`, `INVALID_TRANSITION`, `REVISION_CONFLICT`, `NOT_FOUND`).

#### `factory/src/domain/worker.ts`
- **Lifecycle States**: `WORKER_STATES = ['offline', 'idle', 'busy', 'maintenance']`
- **State Machine Transitions**:
  - `offline` -> `idle`, `maintenance`
  - `idle` -> `busy`, `offline`, `maintenance`
  - `busy` -> `idle`, `offline`, `maintenance`, `failed` (or reset to offline)
  - `maintenance` -> `offline`, `idle`
- **Types / Interfaces**:
  - `WorkerState` = `'offline' | 'idle' | 'busy' | 'maintenance'`
  - `Worker`:
    - `workerId`: string
    - `workerType`: string
    - `status`: `WorkerState`
    - `revision`: number
    - `lastHeartbeatAt`: string | null (ISO timestamp)
    - `protocolVersion`: string | null
    - `capabilities`: string[] (JSON array of capabilities)
    - `payload`: Record<string, unknown>
    - `createdAt`: string
    - `updatedAt`: string
  - Domain validation logic (e.g., `validateWorker`, `canTransitionWorker`, error codes `WORKER_ERROR_CODES`: `INVALID_WORKER`, `INVALID_STATE`, `INVALID_TRANSITION`, `REVISION_CONFLICT`, `NOT_FOUND`).

---

### 2. Repository Ports

#### `factory/src/ports/persistence/work-unit-repository.ts`
- **Interface `WorkUnitRepository`**:
  - `get(workUnitId: string): Promise<WorkUnit | null>`
  - `create(workUnit: Omit<WorkUnit, 'revision' | 'createdAt' | 'updatedAt'> & { revision?: number }): Promise<WorkUnit>`
  - `update(workUnitId: string, patch: Partial<WorkUnit>, expectedRevision: number): Promise<WorkUnit>`
  - `transition(workUnitId: string, nextState: WorkUnitState, expectedRevision: number, payloadUpdate?: Record<string, unknown>): Promise<WorkUnit>`
  - `list(filter?: { status?: WorkUnitState | WorkUnitState[]; priorityMin?: number; limit?: number }): Promise<WorkUnit[]>`
  - Note: Tenant parameters (`organizationId`, `workstreamId`) are scoped at instantiation/wiring level in line with `SqlWorkflowInstanceRepository` and `SqlWorkEnvironmentRepository`.

#### `factory/src/ports/persistence/worker-repository.ts`
- **Interface `WorkerRepository`**:
  - `get(workerId: string): Promise<Worker | null>`
  - `create(worker: Omit<Worker, 'revision' | 'createdAt' | 'updatedAt'> & { revision?: number }): Promise<Worker>`
  - `update(workerId: string, patch: Partial<Worker>, expectedRevision: number): Promise<Worker>`
  - `transition(workerId: string, nextState: WorkerState, expectedRevision: number, payloadUpdate?: Record<string, unknown>): Promise<Worker>`
  - `heartbeat(workerId: string, heartbeatAt: string, expectedRevision?: number): Promise<Worker>`
  - `list(filter?: { status?: WorkerState | WorkerState[]; workerType?: string }): Promise<Worker[]>`
  - Note: Tenant scoping (`organizationId`) is fixed at repository instantiation time.

---

### 3. SQL Persistence Adapters

#### `factory/src/adapters/persistence/sql/sql-work-unit-repository.ts`
- **Class `SqlWorkUnitRepository` implements `WorkUnitRepository`**:
  - Options: `{ organizationId?: string; workstreamId?: string }` (defaults to `'default'`).
  - Table: `work_units`
    - Columns: `organization_id`, `workstream_id`, `work_unit_id`, `unit_type`, `status`, `revision`, `priority`, `not_before`, `attempt_count`, `payload`, `created_at`, `updated_at`.
  - Implements CRUD operations, transition logic, optimistic locking (`WHERE work_unit_id = $3 AND revision = $4`), error throwing (`SqlWorkUnitRepositoryError` with error codes matching domain/filesystem standards like `REVISION_CONFLICT`, `NOT_FOUND`, `INVALID_TRANSITION`).

#### `factory/src/adapters/persistence/sql/sql-worker-repository.ts`
- **Class `SqlWorkerRepository` implements `WorkerRepository`**:
  - Options: `{ organizationId?: string }` (defaults to `'default'`).
  - Table: `workers`
    - Columns: `organization_id`, `worker_id`, `worker_type`, `status`, `revision`, `last_heartbeat_at`, `protocol_version`, `capabilities` (JSONB), `payload`, `created_at`, `updated_at`.
  - Implements CRUD operations, heartbeat updates, transition logic, optimistic locking (`WHERE worker_id = $2 AND revision = $3`), error handling.

---

### 4. Conformance Test Suite

#### `factory/tests/test-conformance-workunit-worker.mjs`
- Standard Node test runner script using `esbuild` to compile `sql-work-unit-repository.ts` and `sql-worker-repository.ts` in-memory.
- Standard test cases:
  1. `WorkUnit` CRUD operations (Create, Read, Update, List).
  2. `WorkUnit` state machine transitions (Valid vs Invalid transitions, e.g., `created` -> `assigned` ok; `created` -> `completed` fails).
  3. `WorkUnit` optimistic locking (revision check failure on concurrent or stale updates).
  4. `Worker` CRUD operations and heartbeat updates.
  5. `Worker` state machine transitions (Valid vs Invalid transitions).
  6. `Worker` optimistic locking revision conflict tests.
  7. Tenant isolation tests (asserting non-colliding organization/workstream scoping).

---

## Verification Plan

### Manual Verification Commands
1. TypeScript compilation check:
   ```bash
   npx tsc --noEmit --project factory/toolchain/tsconfig.json
   ```
2. Run new conformance test suite:
   ```bash
   node factory/tests/test-conformance-workunit-worker.mjs
   ```
3. Run existing persistence conformance tests to ensure no regressions:
   ```bash
   node factory/tests/test-conformance-work-environment-delivery.mjs
   ```

---

## Directives for Next Agent (Builder)
- Ensure all created TypeScript files follow standard Coday style (no semicolons, single quotes, standard imports).
- Verify in-memory bundling pattern in `test-conformance-workunit-worker.mjs` matches existing `test-conformance-work-environment-delivery.mjs`.
- Do NOT touch lease tables or write lease logic in this task.
- Do NOT edit barrel index files (`factory/src/ports/persistence/index.ts` or `factory/src/adapters/persistence/sql/index.ts`).
