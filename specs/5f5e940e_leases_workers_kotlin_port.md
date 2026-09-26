# Spec Plan: Task W2 Leases & Workers Port in Factory Service (Kotlin)

## Context & Objectives
This document specifies the Kotlin implementation of **Aggregate A3 (Work Units & Leases)** and **Aggregate A4 (Workers & Environments)** within `factory-service/` (`io.whozoss.factory`).

The database schema is already in place (`V1`..`V7__lease_protocol.sql`). The Node domain semantics are defined in `factory/src/domain/work-unit.ts`, `factory/src/domain/lease/lease.ts`, `factory/src/adapters/persistence/sql/sql-lease-repository.ts`, `factory/src/domain/worker.ts`, `factory/src/domain/environment/work-unit-environment.ts`, `factory/src/application/environment/work-unit-environment-service.ts`, and `factory/src/application/environment/work-unit-environment-controller.ts`.

All new code will reside in `factory-service/src/main/kotlin/io/whozoss/factory/` across packages `workunit`, `lease`, `worker`, and `environment`.
Mandatory Testcontainers-based PostgreSQL integration tests will verify concurrent acquisition, fencing, expiry, lifecycle state machines, and HTTP endpoints under `factory-service/src/test/kotlin/io/whozoss/factory/`.

---

## Architecture & Subsystems

```
factory-service/src/main/kotlin/io/whozoss/factory/
├── workunit/
│   ├── domain/        (WorkUnit, WorkUnitState, transitions, errors)
│   ├── persistence/   (JdbcWorkUnitRepository, WorkUnitEntity)
├── lease/
│   ├── domain/        (WorkUnitLease, LeaseStatus, LeaseException, LeaseErrorCode)
│   ├── service/       (LeaseService / JdbcLeaseRepository with @Transactional operations)
├── worker/
│   ├── domain/        (Worker, WorkerState, transitions, errors)
│   ├── service/       (WorkerService / JdbcWorkerRepository with capabilities & heartbeat)
├── environment/
│   ├── domain/        (WorkUnitEnvironment, WorkUnitEnvironmentState)
│   ├── port/          (GitProvisioner interface & LocalGitProvisioner / FakeGitProvisioner)
│   ├── service/       (WorkUnitEnvironmentService)
│   ├── web/           (WorkUnitEnvironmentController - endpoints under /api/factory/workflows/{workflowId}/environment)
```

---

## 1. Aggregate A3 Requirements (Work Units & Leases)

### Packages:
`io.whozoss.factory.workunit` and `io.whozoss.factory.lease`

### Exception & Error Codes (Exact Machine Codes)
- `LEASE_FENCED` (HTTP 409 Conflict)
- `LEASE_EXPIRED` (HTTP 409 Conflict)
- `NO_ELIGIBLE_WORK_UNIT` (HTTP 404 or empty result return depending on method)
- `WORK_UNIT_NOT_FOUND` (HTTP 404 Not Found)
- `INVALID_LEASE_STATE` (HTTP 409 Conflict)

Define `LeaseException` extending `FactoryException`:
- `LeaseFencedException(message, details)` -> 409, code `LEASE_FENCED`
- `LeaseExpiredException(message, details)` -> 409, code `LEASE_EXPIRED`
- `NoEligibleWorkUnitException(message, details)` -> 404, code `NO_ELIGIBLE_WORK_UNIT`
- `WorkUnitNotFoundException(message, details)` -> 404, code `WORK_UNIT_NOT_FOUND`
- `InvalidLeaseStateException(message, details)` -> 409, code `INVALID_LEASE_STATE`

### Work Unit Domain & Persistence
- Domain fields: `workUnitId`, `unitType`, `status` (created, assigned, running, completed, failed, cancelled), `revision`, `priority`, `notBefore` (Instant?), `attemptCount`, `payload` (JSONB string / Map), `createdAt`, `updatedAt`.
- Repository methods to save, findById, update status/attempt.

### Lease Domain & Persistence Service (`@Transactional`)
Operations using `NamedParameterJdbcTemplate` for PostgreSQL specific atomic locks and sequences:
1. **`acquire(scope, workerId, ttlMs, environmentId)`**:
   - Executes `SELECT work_unit_id, revision, attempt_count FROM work_units WHERE organization_id = :org AND workstream_id = :ws AND status IN ('created', 'failed') AND (not_before IS NULL OR not_before <= :now) ORDER BY priority DESC, created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED`.
   - If no candidate, returns `null` (or throws `NoEligibleWorkUnitException` if explicitly requested).
   - Fetches fencing token via sequence: `SELECT nextval('work_unit_lease_fencing_seq')`.
   - Generates `lease_id` (`lease_<UUID>`).
   - Inserts row into `work_unit_leases` with `status = 'active'`, `fencing_token`, `acquired_at = now`, `lease_expires_at = now + ttlMs`, `heartbeat_at = now`.
   - Updates `work_units` setting `status = 'running'`, `attempt_count = attempt_count + 1`, `revision = revision + 1`, `updated_at = now`.
   - Returns the acquired `WorkUnitLease` along with `workUnitId`.

2. **`renew(scope, workUnitId, leaseId, fencingToken, ttlMs)`**:
   - `SELECT * FROM work_unit_leases WHERE organization_id = :org AND workstream_id = :ws AND work_unit_id = :wu AND lease_id = :lease FOR UPDATE`.
   - If not found -> throw `LeaseNotFoundException` / `WORK_UNIT_NOT_FOUND`.
   - Check status: if not `active` -> throw `InvalidLeaseStateException`.
   - Check expiry: if `lease_expires_at <= now` -> throw `LeaseExpiredException`.
   - Check fencing token: if `fencingToken != lease.fencingToken` -> throw `LeaseFencedException`.
   - Updates `lease_expires_at = now + ttlMs`, `heartbeat_at = now`.
   - Returns updated lease.

3. **`release(scope, workUnitId, leaseId, fencingToken, resultStatus)`**:
   - `SELECT * FROM work_unit_leases ... FOR UPDATE`.
   - Check fencing token (if supplied) -> throw `LeaseFencedException` if mismatch.
   - Check status: if not `active` -> throw `InvalidLeaseStateException`.
   - Sets `status = 'released'`, `released_at = now` on lease.
   - Updates `work_units` status to `resultStatus` (`completed`, `failed`, or `created`), `revision = revision + 1`, `updated_at = now`.
   - Returns released lease.

4. **`expire(scope, expiryReason)`**:
   - `SELECT * FROM work_unit_leases WHERE organization_id = :org AND workstream_id = :ws AND status = 'active' AND lease_expires_at < :now FOR UPDATE`.
   - For each expired lease:
     - Sets lease `status = 'expired'`, `released_at = now`, `expiry_reason = expiryReason` (default `heartbeat_timeout`).
     - Re-queues work unit: updates `work_units` `status = 'created'`, `revision = revision + 1`, `updated_at = now`.
   - Returns list of expired leases.

---

## 2. Aggregate A4 Requirements (Workers & Environments)

### Packages:
`io.whozoss.factory.worker` and `io.whozoss.factory.environment`

### Worker Domain & Persistence Service
- Schema fields in `workers`: `worker_id`, `worker_type`, `status` (offline, idle, busy, maintenance), `revision`, `last_heartbeat_at`, `protocol_version`, `capabilities` (JSONB array), `payload` (JSONB object), `created_at`, `updated_at`.
- Service operations:
  - Register / Upsert worker with declared capabilities (JSONB array).
  - Heartbeat: updates `last_heartbeat_at = now`, returns updated worker status.
  - State transitions (`idle` <-> `busy`, `offline`, `maintenance`).

### Environment Domain & Provisioner Interface
- Domain fields in `work_unit_environments`: `environment_id`, `work_unit_id`, `workflow_id`, `namespace_id`, `parent_case_id`, `repo_root`, `integration_branch`, `branch`, `worktree_path`, `base_commit`, `created_at`, `created_by`, `lifecycle_state` (`provisioning`, `active`, `completed`, `abandoned`, `error`, `removed`).
- Git Provisioner interface: `GitProvisioner`
  - `provisionWorktree(input): EnvironmentFacts`
  - `reconcile(environment): ReconciliationResult`
  - `removeWorktree(environment)`
  - Provide a `FakeGitProvisioner` (for default/test usage) and optionally a `LocalGitProvisioner` using process execution or jgit if needed. Default bean will be `FakeGitProvisioner`.

### Environment REST API (`WorkUnitEnvironmentController`)
Path base: `/api/factory/workflows/{workflowId}/environment`

Endpoints:
1. **`GET /api/factory/workflows/{workflowId}/environment`**
   - Retrieves active/current environment for `workflowId`.
   - Response: `{"data": { "revision": 1, "environment": {...}, "reconciliation": {...}, "headCommit": "...", "fileAccess": {...} } }`
2. **`POST /api/factory/workflows/{workflowId}/environment/provision`**
   - Accepts body with `workUnitId`, `integrationBranch`, `branch`.
   - Uses `TrustContext` for `namespaceId`, `caseId`, `actorId`.
   - Provisions environment and binds parent case. Returns 201 Created or 200 OK wrapped in `{ "data": ... }`.
3. **`POST /api/factory/workflows/{workflowId}/environment/reconcile`**
   - Reconciles Git worktree status. Returns `{ "data": ... }`.
4. **`POST /api/factory/workflows/{workflowId}/environment/release`**
   - Accepts body `{ "state": "completed" | "abandoned" }`.
   - Transitions environment state to `completed`/`abandoned`. Returns `{ "data": ... }`.

Enforce `DataEnvelope<T>` (`{ "data": T }`) for all successful responses and standard `ErrorResponse` (`{ "error": { "code": ..., "message": ... } }`) for errors.

---

## 3. Mandatory Verification & Tests

### Integration Tests (PostgreSQL Testcontainers)

Location: `factory-service/src/test/kotlin/io/whozoss/factory/`

1. **`WorkUnitLeaseConcurrencyTest`**:
   - Setup multiple work units in `created` state in PostgreSQL.
   - Spawn parallel threads (e.g. 10 concurrent threads).
   - Execute `leaseService.acquire(...)` simultaneously.
   - Assertions:
     - No two threads acquire the same `work_unit_id`.
     - Fencing tokens are strictly distinct and monotonically increasing (`nextval('work_unit_lease_fencing_seq')`).
     - No deadlock or serialization failure occur (`SKIP LOCKED` guarantees non-blocking claim).

2. **`LeaseFencingAndExpiryTest`**:
   - **Fencing test**: Acquire lease (token $T_1$). Attempt `renew` or `release` passing stale token $T_0$. Assert throws `LeaseFencedException` with machine code `LEASE_FENCED`.
   - **Expiry test**: Acquire lease with TTL = 1ms. Fast-forward or sleep slightly. Call `renew`. Assert throws `LeaseExpiredException` with machine code `LEASE_EXPIRED`. Call `expire()`, verify lease status becomes `expired` with `expiry_reason = 'heartbeat_timeout'` and work unit is re-queued as `created`.

3. **`WorkerAndCapabilitiesTest`**:
   - Test worker registration with capabilities array (e.g. `["kotlin", "docker"]`).
   - Test worker heartbeat updating `last_heartbeat_at`.
   - Test invalid state transitions throwing invalid state errors.

4. **`EnvironmentLifecycleControllerTest`**:
   - Real Spring Boot web test / MockMvc test for `/api/factory/workflows/{workflowId}/environment/*`.
   - Provision -> Ready (`active`) -> Reconcile -> Release (`completed`/`abandoned`).
   - Validate HTTP envelope structure `{ "data": ... }` and error envelope `{ "error": ... }`.

---

## Implementation Steps & Tasks

1. **Work Unit & Lease Core (A3)**
   - Create package `io.whozoss.factory.workunit` (Domain models, `WorkUnitState`, Exception classes).
   - Create package `io.whozoss.factory.lease` (Domain models, `LeaseService`, `JdbcLeaseRepository`).
   - Implement `SELECT ... FOR UPDATE SKIP LOCKED` and `nextval('work_unit_lease_fencing_seq')`.

2. **Worker & Environment Control Plane (A4)**
   - Create package `io.whozoss.factory.worker` (Worker model, capabilities JSONB handling, heartbeat).
   - Create package `io.whozoss.factory.environment` (Environment model, `GitProvisioner`, `WorkUnitEnvironmentService`, `WorkUnitEnvironmentController`).
   - Wire `WorkUnitEnvironmentController` under `/api/factory/workflows/{workflowId}/environment`.

3. **Integration Tests**
   - Implement `WorkUnitLeaseConcurrencyTest` using Testcontainers.
   - Implement `LeaseFencingAndExpiryTest`.
   - Implement `WorkerAndCapabilitiesTest`.
   - Implement `EnvironmentLifecycleControllerTest`.

4. **Verification & Specification Generation**
   - Run `./gradlew build` in `factory-service/` and verify all tests pass.
   - Run `factory-service/check-openapi-spec.sh` to update OpenAPI documentation if needed.
