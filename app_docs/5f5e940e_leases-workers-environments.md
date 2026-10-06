# Kotlin leases, workers, and environments port

## What changed

`factory-service` now contains additive Kotlin implementations of the W2 aggregates:

- **Work units and leases (A3):** tenant-scoped work-unit persistence and state transitions, plus transactional lease acquisition, renewal, release, and expiry.
- **Workers (A4):** worker registration, lifecycle transitions, JSONB capabilities/payload persistence, and heartbeat updates.
- **Work environments (A4):** provisioning and reconciliation behind a `GitProvisioner` port, with `provisioning -> ready <-> busy -> decommissioned` lifecycle handling and optimistic revision updates.

The lease claim uses PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED`, filters created/failed units by `not_before`, orders by priority and creation time, and draws fencing tokens from `nextval('work_unit_lease_fencing_seq')`. Lease operations preserve the exact protocol codes `LEASE_FENCED`, `LEASE_EXPIRED`, `NO_ELIGIBLE_WORK_UNIT`, `WORK_UNIT_NOT_FOUND`, and `INVALID_LEASE_STATE`; expiry records a reason and re-queues the work unit without incrementing its attempt count again. Service mutations are wrapped in Spring `@Transactional` methods.

The environment control plane is exposed at `/api/factory/workflows/{workflowId}/environment`:

- `GET /` — inspect the current environment and reconciliation
- `POST /provision` — create or idempotently reuse a worktree environment (`201` for a new environment, `200` for reuse)
- `POST /reconcile` — reconcile the recorded environment with its worktree
- `POST /release` — decommission the environment

Successful HTTP responses use `{ "data": ... }`; the existing factory error handler supplies the standard `{ "error": ... }` shape. Trusted namespace and case context is carried through `TrustContext` and may be used by provisioning. The default `FakeGitProvisioner` is deterministic and filesystem-free, keeping real Git worktree operations behind the `GitProvisioner` interface.

## Where it lives

- `factory-service/src/main/kotlin/io/whozoss/factory/workunit/` — `WorkUnit`, state machine, exceptions, JDBC repository, and `WorkUnitService`.
- `factory-service/src/main/kotlin/io/whozoss/factory/lease/` — lease model/status and expiry reasons, JDBC protocol implementation, repository port, and `LeaseService`.
- `factory-service/src/main/kotlin/io/whozoss/factory/worker/` — worker model/state/errors, JDBC repository, and `WorkerService`.
- `factory-service/src/main/kotlin/io/whozoss/factory/environment/` — environment model/state/errors, JDBC repository, provisioner port/fake, service, DTOs, and controller.
- `factory-service/src/main/kotlin/io/whozoss/factory/web/TrustContext.kt` and `TrustContextExtractor.kt` — namespace/case trust-context fields consumed by the environment controller.
- `factory-service/openapi/factory-openapi.yaml` — generated endpoint and schema additions for work environments.
- `specs/5f5e940e_leases_workers_kotlin_port.md` — implementation specification and verification contract.

## Verification

From `factory-service/`, run:

```bash
./gradlew build
```

The added PostgreSQL Testcontainers coverage is in:

- `src/test/kotlin/io/whozoss/factory/workunit/WorkUnitLeaseConcurrencyTest.kt` — concurrent claims, distinct work units, and fencing tokens, including the single-unit contention case.
- `src/test/kotlin/io/whozoss/factory/lease/LeaseFencingAndExpiryTest.kt` — stale-token rejection, expiry/requeue behavior, completion, empty eligibility, and `not_before` filtering.
- `src/test/kotlin/io/whozoss/factory/workunit/WorkUnitAndWorkerIntegrationTest.kt` — persisted work-unit and worker behavior, capabilities, and heartbeat.
- `src/test/kotlin/io/whozoss/factory/domain/DomainStateMachineTest.kt` — work-unit, worker, and environment transition rules.
- `src/test/kotlin/io/whozoss/factory/environment/WorkEnvironmentLifecycleServiceTest.kt` and `EnvironmentLifecycleControllerTest.kt` — environment lifecycle and HTTP envelopes/errors.
- `src/test/kotlin/io/whozoss/factory/DomainIntegrationTest.kt` — shared PostgreSQL/Testcontainers integration-test setup.

The OpenAPI file reflects the environment endpoints and response schemas; it is the only changed API specification artifact in this port.
