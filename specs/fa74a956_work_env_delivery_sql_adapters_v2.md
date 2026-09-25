# Implementation Plan: PostgreSQL Adapters for Work-Environment and Delivery + Conformance Test Suite

## Overview
Implement two concrete PostgreSQL adapter repositories in TypeScript conforming to existing ports:
1. `SqlWorkEnvironmentRepository` in `factory/src/adapters/persistence/sql/sql-work-environment-repository.ts` implementing `WorkEnvironmentRepository`.
2. `SqlDeliveryRepository` in `factory/src/adapters/persistence/sql/sql-delivery-repository.ts` implementing `DeliveryRepository`.
3. Add a cross-adapter conformance test suite in `factory/tests/test-conformance-work-environment-delivery.mjs` verifying strict behavioral parity (return values, error codes) between filesystem reference implementations and SQL adapters, using an in-memory `SqlClient`.

## Strict Perimeter Constraints
ONLY create or touch:
- `factory/src/adapters/persistence/sql/sql-work-environment-repository.ts`
- `factory/src/adapters/persistence/sql/sql-delivery-repository.ts`
- `factory/tests/test-conformance-work-environment-delivery.mjs`

DO NOT TOUCH:
- `factory/src/adapters/persistence/sql/db.ts`
- `factory/src/adapters/persistence/sql/unit-of-work.ts`
- `factory/tests/support/in-memory-sql-client.mjs`
- `factory/src/adapters/persistence/sql/index.ts`
- `factory/src/adapters/persistence/index.ts`
- `factory/src/ports/`
- Any other adapter, migration, generated bundle, or `agentos/**`

---

## Detailed Components & Design

### 1. `SqlWorkEnvironmentRepository` (`factory/src/adapters/persistence/sql/sql-work-environment-repository.ts`)

#### Interface & Construction
- Implements `WorkEnvironmentRepository` port.
- Accepts `SqlClient` and optional `{ organizationId?: string, workstreamId?: string }` options (defaulting to `DEFAULT_ORGANIZATION_ID`, `DEFAULT_WORKSTREAM_ID`).
- Re-exports factory function `createSqlWorkEnvironmentRepository(client, options)`.

#### Schema & Mappings
- Uses V6 table `work_environments` (`organization_id`, `workstream_id`, `environment_id`, `env_type`, `status`, `revision`, `payload`, `created_at`, `updated_at`).
- `payload` stores the `WorkUnitEnvironment` JSON object.
- Mapping state mapping:
  - `WorkUnitEnvironment` `lifecycleState` (`provisioning`, `active`, `completed`, `abandoned`, `error`, `removed`) maps to table column `status`:
    - `provisioning` -> `'provisioning'`
    - `active` -> `'ready'` / `'busy'` (or store lifecycle state as text in `status` if check constraint permits, or map `provisioning` -> `provisioning`, `active` -> `ready`, `completed`/`abandoned`/`error` -> `busy`/`decommissioned`, or store full state in `payload`).
    - Note on CHECK constraint on `work_environments.status`: `CHECK (status IN ('provisioning', 'ready', 'busy', 'decommissioned'))`.
    - Map `WorkUnitEnvironment.lifecycleState`:
      - `'provisioning'` -> `'provisioning'`
      - `'active'` -> `'ready'`
      - `'completed'` | `'abandoned'` | `'error'` -> `'busy'`
      - `'removed'` -> `'decommissioned'`
    - Always read source of truth for `lifecycleState` from `payload` JSON (`parseJsonColumn<WorkUnitEnvironment>(row.payload)`).
- Optimistic locking uses the `revision` column.

#### Domain Validation & Error Code Parity
- Uses pure domain functions:
  - `validateNamespaceId` (or validation logic matching store) -> `INVALID_NAMESPACE`
  - Validation of `environmentId` format -> `INVALID_ENVIRONMENT`
  - `validateWorkUnitEnvironment` -> returns `ValidationFailure` when invalid.
- Error codes matched strictly with `ENVIRONMENT_STORE_ERROR_CODES`:
  - `INVALID_NAMESPACE`: thrown / returned on namespace validation failure.
  - `INVALID_ENVIRONMENT`: thrown / returned on bad environment id format.
  - `NOT_FOUND`: returned as `{ ok: false, error: { code: 'NOT_FOUND' } }` on missing environment.
  - `REVISION_CONFLICT`: returned as `{ ok: false, error: { code: 'REVISION_CONFLICT' } }` when `expectedRevision` mismatches current revision.
  - `INVALID_TRANSITION`: returned when immutability of immutable fields is violated or state transition is forbidden.
  - Transitions allowed (same as filesystem store):
    - `provisioning` -> `provisioning` | `active` | `error`
    - `active` -> `completed` | `abandoned` | `error`
    - `completed` | `abandoned` | `error` -> `removed`

#### Method Contracts
1. `paths(namespaceId, environmentId)`:
   - Validates namespace and environment ID format (throws `INVALID_NAMESPACE` or `INVALID_ENVIRONMENT` as store does).
   - Returns path structure `{ directory, snapshot, events, pending }` without doing filesystem I/O, matching virtual digest paths.
2. `read(namespaceId, environmentId)`:
   - Queries `work_environments` by tenant (`organization_id`, `workstream_id`, `namespace_id` via payload or primary key) and `environment_id`.
   - Returns `{ revision, environmentHash, environment }` or `null` if absent / decommissioned/removed (if omitted). Note: calculate `environmentHash` using SHA-256 of canonical JSON of `environment`.
3. `list(namespaceId, filter?)`:
   - Queries all rows in `work_environments` for the tenant matching `namespaceId` in `payload`.
   - Filters by `filter.states` if specified.
   - Returns array of `EnvironmentSnapshot`.
4. `reserve(environment)`:
   - Validates `environment` with `validateWorkUnitEnvironment`.
   - Reads existing environment. If exists:
     - If JSON identical, return `{ ok: true, changed: false, snapshot }`.
     - Else return `{ ok: false, error: { code: 'INVALID_TRANSITION' } }`.
   - Insert new row into `work_environments` with `revision = 1`.
   - Use `withTransaction` if linked `work_units` / `work_unit_leases` entries or outbox events are written.
   - Returns `{ ok: true, changed: true, snapshot }`.
5. `transition(namespaceId, environmentId, next, options?)`:
   - Reads existing row. If missing -> `{ ok: false, error: { code: 'NOT_FOUND' } }`.
   - Checks `expectedRevision` -> if mismatch -> `{ ok: false, error: { code: 'REVISION_CONFLICT' } }`.
   - Checks JSON deep equality -> if unchanged -> `{ ok: true, changed: false, snapshot }`.
   - Validates immutable fields & allowed lifecycle state transition -> if illegal -> `{ ok: false, error: { code: 'INVALID_TRANSITION' } }`.
   - Validates `next` using `validateWorkUnitEnvironment`.
   - Atomically updates row: `UPDATE work_environments SET revision = revision + 1, payload = $1, status = $2, updated_at = $3 WHERE organization_id = $4 AND workstream_id = $5 AND environment_id = $6 AND revision = $7`.
   - If rowCount === 0 -> `{ ok: false, error: { code: 'REVISION_CONFLICT' } }`.
   - Returns `{ ok: true, changed: true, snapshot }`.

---

### 2. `SqlDeliveryRepository` (`factory/src/adapters/persistence/sql/sql-delivery-repository.ts`)

#### Interface & Construction
- Implements `DeliveryRepository` port.
- Accepts `SqlClient` and optional options `{ organizationId?: string, workstreamId?: string }`.
- Re-exports factory function `createSqlDeliveryRepository(client, options)`.

#### Table Design & Schema
- Table mappings: `deliveries` (snapshot) and `delivery_journal` / `delivery_operations` (journal records).
- Since `deliveries` / `delivery_journal` table schemas exist in SQL or can be simulated with JSONB payloads in SQL tables:
  - `deliveries` (`organization_id`, `workstream_id`, `namespace_id`, `delivery_id`, `revision`, `stage`, `payload`, `created_at`, `updated_at`).
  - `delivery_journal` (`organization_id`, `workstream_id`, `namespace_id`, `delivery_id`, `record_id`, `record_type`, `payload`, `created_at`).
  - (Note: `in-memory-sql-client.mjs` supports generic tables with standard SQL `INSERT`, `SELECT`, `UPDATE`, `DELETE`).

#### Domain Helpers & Validation
- Pure domain helpers imported from domain modules:
  - `evaluateDeliveryPromotion`, `applyDeliveryPromotion`, `deliveryScopeHash`, `deliverySemanticHash` from `../../domain/delivery/delivery-policy.js`
  - `normalizeDeliveryOperationRequest`, `deriveDeliveryOperationIdentity`, `validateDeliveryOperationRecord`, `validateDeliveryOperationTransition` from `../../domain/delivery/delivery-operation-definition.js`
- Error codes matched strictly with `DeliveryStore` / filesystem error codes:
  - `INVALID_DELIVERY_SCOPE`, `DELIVERY_NOT_FOUND`, `DELIVERY_IDENTITY_CONFLICT`, `INVALID_DELIVERY_SNAPSHOT`, `REVISION_CONFLICT`, `IDEMPOTENCY_KEY_COLLISION`, `DELIVERY_SCOPE_MISMATCH`, `ROLLBACK_REQUEST_NOT_FOUND`, `ROLLBACK_REQUEST_ALREADY_DECIDED`, `DELIVERY_OPERATION_NOT_FOUND`, `DELIVERY_OPERATION_INDETERMINATE`, etc.

#### Method Implementation Details
1. `read(namespaceId, deliveryId)`:
   - Selects from `deliveries` by tenant, namespace, deliveryId.
   - Returns `DeliverySnapshot` or `null`.
2. `create(input)`:
   - Validates scope & input snapshot shape (`schemaVersion === '1'`, UUID namespaceId, SAFE deliveryId/workflowId, etc.). Throws `INVALID_DELIVERY_SCOPE` or `INVALID_DELIVERY_SNAPSHOT` as necessary.
   - Checks if snapshot exists. If exists with identical payload -> `{ ok: true, changed: false, snapshot }`. If exists with different payload -> throws/returns `DELIVERY_IDENTITY_CONFLICT`.
   - Inserts row into `deliveries`.
3. `promote(input)`:
   - Reads existing snapshot. Uses `evaluateDeliveryPromotion` and `applyDeliveryPromotion`.
   - Validates revision / scope.
   - Uses `withTransaction` to update `deliveries` snapshot and append record to `delivery_journal`.
4. `readWithOperations(namespaceId, deliveryId)`:
   - Reads snapshot and queries `delivery_journal` for matching `deliveryOperations` and `rollbackRequests`.
5. `inspectDeliveryOperations(namespaceId, deliveryId)`:
   - Queries `delivery_journal` for deliveryId and projects records into `{ history, operations, rollbackRequests, rollbackRequestHistory, unresolvedIndeterminate }`.
6. `createRollbackRequest(input)` / `approveRollbackRequest(...)`:
   - Validates idempotency key, scope hash, semantic hash.
   - Appends journal record atomically inside `withTransaction`.
7. `createDeliveryOperation(input)` / `recordDeliveryOperation(...)` / `startDeliveryOperation(...)` / `reconcileDeliveryOperation(...)`:
   - Uses `normalizeDeliveryOperationRequest`, `deriveDeliveryOperationIdentity`, `validateDeliveryOperationTransition`.
   - Checks for unresolved indeterminate operations if applicable.
   - Appends operation transition to `delivery_journal` and updates operation projections within transaction.
8. `hasIndeterminateOperation(namespaceId, deliveryId)`:
   - Inspects delivery operations and checks if `unresolvedIndeterminate.length > 0`.
9. `updateSnapshot(namespaceId, deliveryId, patch, operationInput)`:
   - Uses `withTransaction` to apply patch to snapshot in `deliveries` table and write matching operation record to `delivery_journal`.

---

### 3. Conformance Test Suite (`factory/tests/test-conformance-work-environment-delivery.mjs`)

#### Architecture & Pattern (matching `test-conformance-agent-step-oracle.mjs` and `test-conformance-evidence-interaction.mjs`)
- Loads TS adapters dynamically or bundles them in-memory using `esbuild`.
- Uses `createInMemorySqlClient()` for SQL adapters and temporary directory filesystem stores for Filesystem adapters (`createFilesystemWorkEnvironmentRepository`, `createFilesystemDeliveryRepository`).
- Executes identical test scenarios against both Filesystem adapters and SQL adapters.
- Directly compares error codes thrown / returned for invalid operations across both implementations to guarantee 100% contract parity.

#### Key Scenario Coverage:
1. **Work Environment Scenarios**:
   - Path resolution (virtual digest matching).
   - Lifecycle transitions: `reserve` -> `transition` (provisioning -> active -> completed -> removed).
   - Idempotent replay of `reserve` and `transition` with identical payload.
   - Conflict detection: `REVISION_CONFLICT`, `INVALID_TRANSITION`, `NOT_FOUND`, `INVALID_NAMESPACE`, `INVALID_ENVIRONMENT`.
   - List filtering by namespace and state.
2. **Delivery Scenarios**:
   - Snapshot creation and idempotent replay vs `DELIVERY_IDENTITY_CONFLICT`.
   - Promotion evaluation and revision bumping.
   - Delivery operations: creation, transition, start, reconciliation, indeterminate state handling (`DELIVERY_OPERATION_INDETERMINATE`).
   - Rollback request lifecycle: creation, scope/semantic hash checks, approval, and duplicate approval rejection (`ROLLBACK_REQUEST_ALREADY_DECIDED`).
   - Inspection of operations journal (`inspectDeliveryOperations`, `readWithOperations`).
   - Atomic update of snapshot + journal via `updateSnapshot`.
3. **SQL-Specific Transaction Atomicity**:
   - Simulates injected faults inside unit-of-work / transaction to verify that multi-table writes (snapshot + journal) roll back cleanly.
4. **Parity Check**:
   - Asserts `assert.deepEqual(sqlCodes, filesystemCodes)` across all tested error scenarios.

---

## Verification Plan

### Automated Checks
1. Compile & Type Check / Bundling:
   - Ensure `esbuild` bundles both adapters cleanly without TypeScript compilation errors.
2. Direct Conformance Suite Execution:
   - Run: `node factory/tests/test-conformance-work-environment-delivery.mjs`
   - Expect: Output `Result: XX passed, 0 failed` and exit code `0`.
3. Existing Test Regression Check:
   - Run existing related tests to ensure no regressions:
     - `node factory/tests/test-sql-repository-ports-adapters.mjs`
     - `node factory/tests/test-conformance-agent-step-oracle.mjs`
     - `node factory/tests/test-conformance-evidence-interaction.mjs`
     - `node factory/tests/test-work-unit-environment.mjs`
     - `node factory/tests/test-delivery-phase9.mjs`

---

## Notes for Builder
- Remember to strictly observe the file boundary: only write to the 3 perimeter files!
- Use standard Node import conventions and TypeScript syntax for adapters, and `.mjs` with Node test assertions for the conformance test.
