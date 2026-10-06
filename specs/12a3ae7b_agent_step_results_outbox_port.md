# Implementation Plan - Port Aggregate A7 (agent-step + outbox) to Kotlin/Spring Boot

## Overview
Port aggregate A7 (`agent-step` + submission capability + outbox drain + idempotency) from Node/TS (`factory/src/domain/agent-attempt`, `factory/src/adapters/persistence/sql/sql-agent-step-result-repository.ts`, `factory/dashboard/agent-step-result-routes.mjs`) to Kotlin / Spring Boot inside `factory-service` under package `io.whozoss.factory.agentattempt`.
The HTTP endpoint `POST /api/factory/agent-step-results` must serve the exact REST API contract, envelope structure, status codes, and error codes as expected by clients/plugins (`agentos-factory-bridge-plugin`).

---

## Architecture & Package Structure

New package: `io.whozoss.factory.agentattempt`

```
factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/
├── domain/
│   ├── AgentStepResultModels.kt         # Business result models, claims, artifacts, findings, statuses, limits
│   ├── AgentStepResultValidation.kt     # Pure validation logic (limits, severity enum checks, allowed keys)
│   ├── AgentStepAttemptModels.kt        # Attempt aggregate models and terminal status mappings
│   └── CanonicalJsonHash.kt             # SHA-256 canonical JSON key-sorting & hashing (SHA-256 hex/digest)
├── persistence/
│   ├── AgentStepAttemptRepository.kt    # Port interface and Jdbc implementation for agent_step_attempts & events
│   ├── AgentStepResultRepository.kt     # Port interface and Jdbc implementation for agent_step_results, result_capabilities, outbox_events
│   └── IdempotencyRepository.kt         # Port interface and Jdbc implementation for idempotency_records
├── service/
│   ├── AgentStepResultService.kt        # Application service managing capability verification, submission, idempotency & multi-table TX
│   └── OutboxDrainService.kt            # Outbox polling worker service (pending -> dispatched/failed)
└── web/
    ├── AgentStepResultController.kt     # REST Controller: POST /api/factory/agent-step-results
    └── AgentStepResultDtos.kt           # Request / Response DTOs and DataEnvelope wrappers
```

Test package: `io.whozoss.factory.agentattempt/`
- `AgentStepResultValidationTest.kt` (Unit tests for business limits & canonical hashing)
- `AgentStepResultServiceIntegrationTest.kt` (Extends `DomainIntegrationTest`)
- `OutboxDrainServiceIntegrationTest.kt` (Extends `DomainIntegrationTest`)
- `AgentStepResultControllerHttpTest.kt` (Extends `DomainIntegrationTest`)

---

## Detailed Components Design

### 1. Business Validation & Canonical Hashing (`domain/`)

- **`AgentStepResultModels.kt`**:
  - `status`: `PASS` or `FAIL`
  - `limits`:
    - `summary`: <= 2000 chars, non-empty
    - `modifiedFiles`: <= 1000 items, each string <= 1024 chars, non-empty
    - `artifacts`: <= 8 items, kind <= 128 chars, encoding strictly `"markdown"`, content UTF-8 bytes <= 262144 (256 KB)
    - `findings`: <= 100 items, severities in `['info', 'warning', 'error', 'blocking']`, code <= 128 chars, summary <= 1000 chars, file <= 1024 chars, line >= 1 (if present)
  - `AgentStepResultBusiness`: status, summary, claims, artifacts (optional), findings (optional)

- **`CanonicalJsonHash.kt`**:
  - Implement recursive key-sorting for Map / JSON structures using Jackson `ObjectMapper` or custom sorter.
  - Produce canonical JSON string (no unexpected whitespace, sorted keys).
  - Compute SHA-256 hex digest prefixed with `sha256:` (or raw sha256 as required: TS uses `sha256:<hex>`). Note: `sha256(canonicalJson)`.
  - Provide constant-time comparison `safeEqual(a, b)` for token hash check (`MessageDigest.isEqual`).

- **`AgentStepResultValidation.kt`**:
  - Pure function `validateAgentStepResultBusiness(payload: Any?): Boolean` checking all limit constraints and ensuring no extra top-level/nested keys outside allowed sets.

### 2. Multi-Table Transaction & Repositories (`persistence/` & `service/`)

- **`AgentStepResultRepository.kt`**:
  - Uses `NamedParameterJdbcTemplate`.
  - Operations on `result_capabilities`, `agent_step_results`, `agent_step_attempts`, `outbox_events`.
  - `findByTokenHash(tokenHash: String)`: Finds issued capability record in `result_capabilities` tenant-scoped (`organization_id`, `workstream_id`). Note: `tokenHash` is stored in JSONB `payload->>'tokenHash'` or checked in memory.
  - `selectResult(...)`: Finds existing result in `agent_step_results`.
  - `submit(...)` inside single `@Transactional` method / boundary:
    1. Check `result_capabilities`: lookup capability by SHA-256 token digest. If missing -> error `RESULT_CAPABILITY_INVALID` (401).
    2. Check observed identity (`attemptId`, `caseId`, `agentName`). If mismatch -> error `RESULT_IDENTITY_MISMATCH` (400).
    3. Check `agent_step_results`: if result already exists for attempt:
       - If `existing.resultHash == newResultHash` -> Return idempotent success (`idempotent = true`, return existing result).
       - If `existing.resultHash != newResultHash` -> Return error `RESULT_SEMANTIC_COLLISION` (409).
    4. Check expiry: if `expiresAt < now` -> Return error `RESULT_CAPABILITY_EXPIRED` (410).
    5. Write `agent_step_results` append-only row with `result_status` (`success` for `PASS`, `failure` for `FAIL`), `semantic_signature` (`resultHash`), and `payload` (JSONB).
    6. Update `agent_step_attempts` terminal state: update `status` (`completed` for `PASS`, `failed` for `FAIL`), increment `revision = revision + 1`, update `updated_at`.
    7. Insert row into `outbox_events`:
       - `organization_id`, `workstream_id`
       - `id`: random UUID
       - `event_type`: `'result_submitted'`
       - `status`: `'pending'`
       - `payload`: JSONB containing `{ "aggregateType": "agent_step_result", "attemptId": ..., "resultId": ..., "status": ... }`
       - `created_at`: timestamp

- **Idempotency Support (`idempotency_records`)**:
  - Endpoint support via optional header `X-Idempotency-Key` or payload body.
  - If `X-Idempotency-Key` is provided:
    - Look up `idempotency_records` by `(organization_id, idempotency_key)`.
    - If record exists:
      - Compare `request_hash` (SHA-256 of canonical request payload).
      - If hash mismatch -> return 409 `IDEMPOTENCY_KEY_COLLISION`.
      - If exact match -> return cached response payload (200 / 201).
    - If new key -> execute submission inside TX and save `idempotency_records` row (`status = 'completed'`, `response_payload = ...`).

### 3. Outbox Drain Worker Service (`OutboxDrainService.kt`)

- Simple SQL polling service using `NamedParameterJdbcTemplate`:
  - `drainPendingEvents(scope: TenantScope, limit: Int = 50)`:
    - `SELECT id, payload FROM outbox_events WHERE organization_id = :orgId AND status = 'pending' ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED`
    - Process each event and mark status as `'dispatched'` (or `'failed'` on dispatch error with incremented `attempts`).
  - Safe for testing & background execution.

### 4. REST Controller & Error Handling (`web/`)

- **`AgentStepResultController.kt`**:
  - `@RestController`, `@RequestMapping("/api/factory/agent-step-results")`, `@Tag(name = "agent-step-results")`.
  - `@PostMapping`:
    - Header: `Authorization: Bearer <token>`
    - Optional Headers: `X-AgentOS-Case-Id`, `X-AgentOS-Agent-Name`, `X-Idempotency-Key`
    - Request Body: `{ "attemptId": "...", "result": { ... } }`
  - Validations & Status Code Mappings:
    - Missing `Authorization` header or token -> 401 `RESULT_CAPABILITY_INVALID` / `TRUST_CONTEXT_UNAVAILABLE`
    - Invalid request body format / missing attemptId -> 400 `INVALID_RESULT_REQUEST`
    - Schema / Limits invalid -> 400 `RESULT_SCHEMA_INVALID`
    - Identity mismatch -> 400 `RESULT_IDENTITY_MISMATCH`
    - Capability expired -> 410 `RESULT_CAPABILITY_EXPIRED`
    - Semantic collision -> 409 `RESULT_SEMANTIC_COLLISION`
    - Idempotency key collision -> 409 `IDEMPOTENCY_KEY_COLLISION`
    - Success (new) -> 201 Created `{ "data": { "resultId": "...", "idempotent": false, "resultHash": "..." } }`
    - Success (idempotent replay) -> 200 OK `{ "data": { "resultId": "...", "idempotent": true, "resultHash": "..." } }`

- Direct Exception Throwing / Handling:
  - Create specific exceptions subclassing `FactoryException` or throw directly for standard handling by `FactoryExceptionHandler`:
    - `ResultSchemaInvalidException` (400, `RESULT_SCHEMA_INVALID`)
    - `ResultCapabilityInvalidException` (401, `RESULT_CAPABILITY_INVALID`)
    - `ResultIdentityMismatchException` (400, `RESULT_IDENTITY_MISMATCH`)
    - `ResultCapabilityExpiredException` (410, `RESULT_CAPABILITY_EXPIRED`)
    - `ResultSemanticCollisionException` (409, `RESULT_SEMANTIC_COLLISION`)
    - `IdempotencyKeyCollisionException` (409, `IDEMPOTENCY_KEY_COLLISION`)

---

## Implementation Steps

1. **Domain Models & Validation**:
   - Create `AgentStepResultModels.kt` with all bounds and limits.
   - Create `CanonicalJsonHash.kt` with canonical JSON serialization and SHA-256 computation.
   - Create `AgentStepResultValidation.kt` and unit tests in `AgentStepResultValidationTest.kt`.

2. **Persistence & Outbox**:
   - Create `AgentStepResultRepository.kt` & `JdbcAgentStepResultRepository.kt`.
   - Implement single-method atomic transaction in service/repository for result write + attempt status update + outbox event insert.
   - Implement `IdempotencyRepository.kt` for `idempotency_records`.
   - Implement `OutboxDrainService.kt`.

3. **Service & Controller**:
   - Implement `AgentStepResultService.kt` handling token extraction, capability check, idempotency check, result submission, and response formatting.
   - Implement `AgentStepResultController.kt` with SpringDoc `@Operation` annotations matching `openapi.json`.

4. **Integration Tests**:
   - Update `resetControlPlane()` in `DomainIntegrationTest.kt` to truncate `agent_step_results`, `agent_step_attempts`, `agent_step_attempt_events`, `result_capabilities`, `outbox_events`, `idempotency_records` on test setup so state doesn't leak.
   - Implement integration test classes extending `DomainIntegrationTest`:
     - `AgentStepResultServiceIntegrationTest.kt`: Test multi-table atomicity, rollback on error, capability issuance & single-use/expiry, semantic collision vs idempotent replay, idempotency records deduplication.
     - `OutboxDrainServiceIntegrationTest.kt`: Test outbox polling and state transitions from `pending` to `dispatched`/`failed`.
     - `AgentStepResultControllerHttpTest.kt`: Test HTTP endpoints, headers, payload envelopes, and status codes.

5. **Validation**:
   - Run `cd factory-service && ./gradlew clean test` to confirm 100% pass without context cache eviction errors.
   - Run `./check-openapi-spec.sh` or verify SpringDoc OpenAPI generation.

---

## Verification Plan

1. **Gradle Build & Test Execution**:
   - Command: `cd factory-service && ./gradlew clean test`
   - Verification: All tests pass cleanly, standard Spring test context is reused, zero context caching eviction errors.

2. **Database Verification**:
   - Confirm table interactions for V4 & V6 tables: `agent_step_attempts`, `agent_step_results`, `result_capabilities`, `outbox_events`, `idempotency_records`.
   - Ensure Flyway checksums and migration files V1..V9 remain untouched.

3. **API Contract Verification**:
   - Compare `POST /api/factory/agent-step-results` response envelopes with Node dashboard implementation (`factory/dashboard/agent-step-result-routes.mjs`) and OpenAPI spec (`factory/dashboard/openapi.json`).
