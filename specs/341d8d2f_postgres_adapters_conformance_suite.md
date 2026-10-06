# Plan: PostgreSQL Adapters for Agent-Step and Oracle, plus Conformance Test Suite

## Overview
This plan details the implementation of three PostgreSQL persistence adapters (`SqlAgentStepAttemptRepository`, `SqlAgentStepResultRepository`, and `SqlOracleExecutionRepository`) and a cross-implementation conformance test suite (`factory/tests/test-conformance-agent-step-oracle.mjs`).

The SQL adapters will implement their respective domain persistence ports (`AgentStepAttemptRepository`, `AgentStepResultRepository`, `OracleExecutionRepository`) using PostgreSQL tables defined in V6 (`agent_step_attempts`, `agent_step_attempt_events`, `agent_step_results`, `result_capabilities`, `oracle_executions`, `artifacts`) and V4 (`outbox_events`).

## Files to Create

1. `factory/src/adapters/persistence/sql/sql-agent-step-attempt-repository.ts`
2. `factory/src/adapters/persistence/sql/sql-agent-step-result-repository.ts`
3. `factory/src/adapters/persistence/sql/sql-oracle-execution-repository.ts`
4. `factory/tests/test-conformance-agent-step-oracle.mjs`

## Files under Strict Constraints (DO NOT TOUCH)
- `factory/src/adapters/persistence/sql/db.ts`
- `factory/src/adapters/persistence/sql/unit-of-work.ts`
- `factory/tests/support/in-memory-sql-client.mjs`
- `factory/src/adapters/persistence/sql/index.ts` (reserved for W3)
- `factory/src/adapters/persistence/index.ts` (reserved for W3)
- Filesystem adapters, migrations, generated bundles, `agentos/**`

---

## Detailed Specifications per Component

### 1. `SqlAgentStepAttemptRepository` (`factory/src/adapters/persistence/sql/sql-agent-step-attempt-repository.ts`)

- **Port implemented**: `AgentStepAttemptRepository` from `../../../ports/persistence/agent-step-attempt-repository.js`
- **Domain Imports & Constants**:
  - `AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS`, `AGENT_STEP_ATTEMPT_TRANSITIONS`, `validateAgentStepAttempt`, `type AgentStepAttempt` from `../../../domain/agent-attempt/agent-step-attempt.js`
  - `DEFAULT_ORGANIZATION_ID`, `DEFAULT_WORKSTREAM_ID`, `parseJsonColumn`, `type SqlClient` from `./db.js`
  - `withTransaction` from `./unit-of-work.ts`
- **Constructor & Options**:
  ```ts
  export interface SqlAgentStepAttemptRepositoryOptions {
    organizationId?: string
    workstreamId?: string
  }
  ```
  Default `organizationId = 'default'`, `workstreamId = 'default'`.
- **Methods**:
  - `list(namespaceId: string, storageId: string): Promise<AgentStepAttempt[]>`
    - Query `agent_step_attempt_events` (or `agent_step_attempts` / events) where `organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND step_id = $4` (note: `storageId` maps to `step_id` or `workflow_id`/`step_id` scope; in attempt store filesystem layout `storageId` is the step/storage scope identifier).
    - Query: `SELECT payload FROM agent_step_attempt_events WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND step_id = $4 ORDER BY created_at ASC`
    - Returns array of `parseJsonColumn<AgentStepAttempt>(row.payload)`.
  - `append(namespaceId: string, storageId: string, attempt: AgentStepAttempt): Promise<AgentStepAttempt>`
    - Execution in transaction via `withTransaction(this.#client, async (tx) => { ... })`.
    - Validate attempt: `validateAgentStepAttempt(attempt)`.
    - Namespace check: `if (attempt.namespaceId !== namespaceId) throw new Error('AGENT_STEP_ATTEMPT_NAMESPACE_MISMATCH')`.
    - Query existing attempt by PK: `organization_id`, `workstream_id`, `namespace_id`, `workflow_id` (`attempt.workflowId`), `step_id` (`storageId`), `attempt_id` (`attempt.attemptId`) from `agent_step_attempts`.
    - If existing:
      - Parse previous attempt domain object from `payload` JSONB column.
      - Check immutable fields (`AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS`): if any changed, throw `new Error('AGENT_STEP_ATTEMPT_IDENTITY_CONFLICT')`.
      - Check status transition: `allowed = AGENT_STEP_ATTEMPT_TRANSITIONS[previous.status] ?? []`. If `!allowed.includes(attempt.status)`, throw `new Error('INVALID_AGENT_STEP_ATTEMPT_TRANSITION')`.
      - Update `agent_step_attempts` row: status = `attempt.status`, revision = `revision + 1`, payload = `JSON.stringify(attempt)`, updated_at = `CURRENT_TIMESTAMP` WHERE PK and revision matches.
    - If new:
      - Must start with status `'starting'`: `if (attempt.status !== 'starting') throw new Error('AGENT_STEP_ATTEMPT_MUST_START')`.
      - Insert into `agent_step_attempts` (`organization_id`, `workstream_id`, `namespace_id`, `workflow_id`, `step_id`, `attempt_id`, `agent_id`, `status`, `revision`, `payload`, `created_at`, `updated_at`).
    - Insert event record into `agent_step_attempt_events`:
      - `event_id`: random UUID or deterministic event id.
      - `event_type`: attempt.status or 'attempt_appended'.
      - `payload`: JSON.stringify(attempt).
    - Return `attempt`.
- **Export Factory**: `createSqlAgentStepAttemptRepository(client: SqlClient, options?: SqlAgentStepAttemptRepositoryOptions)`

---

### 2. `SqlAgentStepResultRepository` (`factory/src/adapters/persistence/sql/sql-agent-step-result-repository.ts`)

- **Port implemented**: `AgentStepResultRepository` from `../../../ports/persistence/agent-step-result-repository.js`
- **Domain Imports & Helpers**:
  - `randomBytes`, `randomUUID` from `node:crypto`
  - `canonicalAgentStepResultJson`, `isSafeAgentStepResultId`, `sha256`, `validateAgentStepResultBusiness`, `type AgentStepResultCapabilityIdentity`, `type AgentStepResultCapabilityIssued`, `type AgentStepResultLedgerEvent`, `type AgentStepResultObservedIdentity`, `type AgentStepResultSubmitted` from `../../../domain/agent-attempt/agent-step-result.js`
  - `DEFAULT_ORGANIZATION_ID`, `DEFAULT_WORKSTREAM_ID`, `parseJsonColumn`, `type SqlClient` from `./db.js`
  - `withTransaction` from `./unit-of-work.ts`
- **Constructor & Options**:
  ```ts
  export interface SqlAgentStepResultRepositoryOptions {
    organizationId?: string
    workstreamId?: string
    clock?: () => Date
    ttlMs?: number
  }
  ```
- **Methods**:
  - `issue(namespaceId: string, storageId: string, identity: AgentStepResultCapabilityIdentity): Promise<AgentStepResultIssueResult>`
    - Validate identity fields (`attemptId`, `workflowId`, `stepId`, `namespaceId`, `caseId`, `agentName`) using `isSafeAgentStepResultId`. Check `identity.namespaceId === namespaceId` and briefHash matches `/^sha256:[0-9a-f]{64}$/`. If invalid, throw `new Error('INVALID_RESULT_CAPABILITY_IDENTITY')`.
    - Check inside transaction / query `result_capabilities` for existing attempt capability (where `organization_id`, `workstream_id`, `namespace_id`, `workflow_id`, `step_id`, `attempt_id` matches).
      - If exists: compare capability identity fields (`attemptId`, `workflowId`, `stepId`, `namespaceId`, `caseId`, `agentName`, `briefHash`).
      - If all match: throw `new Error('RESULT_CAPABILITY_ALREADY_ISSUED')`.
      - If mismatch: throw `new Error('RESULT_CAPABILITY_IDENTITY_CONFLICT')`.
    - Generate `token = randomBytes(32).toString('base64url')`, `tokenHash = sha256(token)`.
    - Compute `now = this.#clock()`, `expiresAt = new Date(now.getTime() + ttlMs).toISOString()`.
    - Insert into `result_capabilities`:
      - `organization_id`, `workstream_id`, `namespace_id`, `workflow_id` (`identity.workflowId`), `step_id` (`storageId` / `identity.stepId`), `attempt_id` (`identity.attemptId`), `result_id` (`''`), `capability_id` (`randomUUID()`), `capability_type` (`'agent_step_submit'`), `payload` JSONB containing `AgentStepResultCapabilityIssued` object with `tokenHash`, `issuedAt`, `expiresAt`, etc.
    - Return `{ token, expiresAt }`.

  - `submit(token: string, business: unknown, observed: Partial<AgentStepResultObservedIdentity> = {}): Promise<AgentStepResultSubmitResult>`
    - Validate `business` payload using `validateAgentStepResultBusiness(business)` -> if false, return `{ ok: false, code: 'RESULT_SCHEMA_INVALID' }`.
    - Query `result_capabilities` using `tokenHash = sha256(token)`. If not found, return `{ ok: false, code: 'RESULT_CAPABILITY_INVALID' }`.
    - Parse capability issued record from `payload`.
    - Verify observed identity (`attemptId`, `caseId`, `agentName` if present) matches issued identity. If mismatch, return `{ ok: false, code: 'RESULT_IDENTITY_MISMATCH' }`.
    - Compute `resultHash = sha256(canonicalAgentStepResultJson(business))`.
    - Execute inside `withTransaction(this.#client, async (tx) => { ... })`:
      - Query `agent_step_results` for `attempt_id = issued.attemptId`.
      - If existing result row exists:
        - Parse `existing` result or read `semantic_signature` column.
        - If `storedResultHash === resultHash`: return `{ ok: true, idempotent: true, result: storedResult }`.
        - If `storedResultHash !== resultHash`: return `{ ok: false, code: 'RESULT_SEMANTIC_COLLISION' }`.
      - Check expiry: `if (this.#clock().getTime() > Date.parse(issued.expiresAt)) return { ok: false, code: 'RESULT_CAPABILITY_EXPIRED' }`.
      - Construct `AgentStepResultSubmitted` domain object with `resultId = randomUUID()`, `resultHash`, `submittedAt = this.#clock().toISOString()`, `status = business.status`, etc.
      - Insert into `agent_step_results`:
        - `organization_id`, `workstream_id`, `namespace_id`, `workflow_id`, `step_id`, `attempt_id`, `result_id`, `result_status` (`business.status === 'succeeded' ? 'success' : 'failure'`), `semantic_signature` (`resultHash`), `payload` (`result`).
      - Update `agent_step_attempts`:
        - Set status to terminal status (`'completed'` if business.status === 'succeeded' else `'failed'`), revision = revision + 1, updated_at = CURRENT_TIMESTAMP for matching attempt PK.
      - Write to `outbox_events` (V4 outbox table) within same transaction:
        - `organization_id`, `id` (`randomUUID()`), `workstream_id`, `event_type` (`'result_submitted'`), `payload` (`{ attemptId: issued.attemptId, resultId: result.resultId, status: result.status }`), `status` (`'pending'`).
      - Return `{ ok: true, idempotent: false, result }`.

  - `getByAttempt(namespaceId: string, storageId: string, attemptId: string): Promise<AgentStepResultSubmitted | null>`
    - Query `agent_step_results` where `organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND step_id = $4 AND attempt_id = $5`.
    - Return `parseJsonColumn<AgentStepResultSubmitted>(row.payload)` or `null`.

  - `list(namespaceId: string, storageId: string): Promise<AgentStepResultLedgerEvent[]>`
    - Query `result_capabilities` and `agent_step_results` for namespace/storage scope, returning list of ledger events (`AgentStepResultLedgerEvent[]`) ordered by `created_at ASC`.

- **Export Factory**: `createSqlAgentStepResultRepository(client: SqlClient, options?: SqlAgentStepResultRepositoryOptions)`

---

### 3. `SqlOracleExecutionRepository` (`factory/src/adapters/persistence/sql/sql-oracle-execution-repository.ts`)

- **Port implemented**: `OracleExecutionRepository` from `../../../ports/persistence/oracle-execution-repository.js`
- **Domain & DB Imports**:
  - `type OracleDefinition` from `../../../domain/oracle/oracle-definition.js`
  - `DEFAULT_ORGANIZATION_ID`, `DEFAULT_WORKSTREAM_ID`, `parseJsonColumn`, `type SqlClient` from `./db.js`
  - `withTransaction` from `./unit-of-work.ts`
- **Constructor & Options**:
  ```ts
  export interface SqlOracleExecutionRepositoryOptions {
    organizationId?: string
    workstreamId?: string
  }
  ```
- **Methods**:
  - `list(): Promise<OracleDefinition[]>`:
    - Query `oracle_executions` where `organization_id = $1 AND workstream_id = $2`.
    - Return list of parsed `OracleDefinition` items from JSON payloads or fallback/registry definitions.
  - `get(id: string): Promise<OracleDefinition | null>`:
    - Query `oracle_executions` for execution or oracle definition by id. Return parsed payload or `null`.
  - Amendment 5 support method / terminalization helper:
    - Support terminalizing an `oracle_execution` inside `withTransaction`: when terminalizing (e.g. status transition to 'succeeded' or 'failed'), update associated `artifacts` availability_status to `'available'` inside the same transaction if an `artifact_id` is linked.
- **Export Factory**: `createSqlOracleExecutionRepository(client: SqlClient, options?: SqlOracleExecutionRepositoryOptions)`

---

### 4. Conformance Test Suite (`factory/tests/test-conformance-agent-step-oracle.mjs`)

- Offline standalone Node script.
- Uses `createInMemorySqlClient` from `./support/in-memory-sql-client.mjs` and filesystem store helpers.
- Run both Filesystem adapters and SQL adapters against the same scenarios:
  1. Attempt lifecycle: start attempt -> attempt transition -> invalid transition rejection (`INVALID_AGENT_STEP_ATTEMPT_TRANSITION`) -> identity conflict rejection (`AGENT_STEP_ATTEMPT_IDENTITY_CONFLICT`) -> namespace mismatch rejection (`AGENT_STEP_ATTEMPT_NAMESPACE_MISMATCH`).
  2. Result capability issuance: invalid identity handling (`INVALID_RESULT_CAPABILITY_IDENTITY`), duplicate issuance rejection (`RESULT_CAPABILITY_ALREADY_ISSUED`), capability identity conflict (`RESULT_CAPABILITY_IDENTITY_CONFLICT`).
  3. Result submission & Idempotency: valid submission -> duplicate identical submission returns `{ ok: true, idempotent: true }` -> divergent submission returns `{ ok: false, code: 'RESULT_SEMANTIC_COLLISION' }` -> identity mismatch returns `RESULT_IDENTITY_MISMATCH`.
  4. Atomicity & Rollback test: verify that if a transaction fails (e.g., attempt status update failure), outbox event and result insertion are rolled back, leaving SQL client state clean.
  5. Error parity: verify exact same error codes (`AGENT_STEP_ATTEMPT_NAMESPACE_MISMATCH`, `AGENT_STEP_ATTEMPT_IDENTITY_CONFLICT`, `INVALID_AGENT_STEP_ATTEMPT_TRANSITION`, `AGENT_STEP_ATTEMPT_MUST_START`, `INVALID_RESULT_CAPABILITY_IDENTITY`, `RESULT_CAPABILITY_ALREADY_ISSUED`, `RESULT_CAPABILITY_IDENTITY_CONFLICT`, `RESULT_SCHEMA_INVALID`, `RESULT_CAPABILITY_INVALID`, `RESULT_IDENTITY_MISMATCH`, `RESULT_SEMANTIC_COLLISION`, `RESULT_CAPABILITY_EXPIRED`) across filesystem and SQL adapters.
- Run command to verify: `node factory/tests/test-conformance-agent-step-oracle.mjs`. Must exit 0.

---

## Verification Plan

1. Execute conformance test suite:
   ```bash
   node factory/tests/test-conformance-agent-step-oracle.mjs
   ```
2. Run existing repository & schema tests to verify no regressions:
   ```bash
   node factory/tests/test-sql-repository-ports-adapters.mjs
   node factory/tests/test-repository-contract.mjs
   node factory/tests/test-v6-migration-schema.mjs
   ```
3. Run affected test command per repository guidelines:
   ```bash
   pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
   ```

---

## Deliverables & Paths

- Plan location 1 (builder copy): `/work/data/sessions/341d8d2f/context_handoff/plan.md`
- Plan location 2 (repo record): `specs/341d8d2f_postgres_adapters_conformance_suite.md`
