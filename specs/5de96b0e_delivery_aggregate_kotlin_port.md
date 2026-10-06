# Spec: Port Aggregate A5 "delivery" to Kotlin/Spring Boot (`factory-service`)

## Overview

This specification details the port of Aggregate A5 **Delivery** from Node/TypeScript (`factory/src/domain/delivery`, `factory/src/adapters/delivery`, `factory/src/adapters/persistence/sql/sql-delivery-repository.ts`, `factory/src/application/delivery`) to Kotlin/Spring Boot in `factory-service/`.

The delivery aggregate manages governed delivery workflows:
1. Pure delivery & delivery operation definitions, policies, validation, normalization, and canonical hashing (`sha256` canonical JSON).
2. Append-only delivery journal (`delivery_journal`) and optimistic-locking delivery snapshots (`deliveries`).
3. Evidence stores, Git control plane integration, and Pull Request ports/adapters.
4. HTTP control plane under `/api/factory/workflows/{workflowId}/delivery/...`.

---

## CONSTRAINTS & RULES

1. **NO-TOUCH ZONE**:
   - MUST NOT modify any Node files or JS/TS scripts.
   - MUST NOT touch Flyway migrations `V1` through `V7`.
   - MUST NOT modify existing aggregate Kotlin code (`artifact`, `oracle`, `workunit`, `worker`, `lease`, `environment`).
2. **SQL MIGRATION**:
   - All schema additions MUST be placed strictly in `factory-service/src/main/resources/db/migration/V8__delivery.sql`.
3. **KOTLIN PACKAGE LOCATION**:
   - All new Kotlin code MUST reside under `factory-service/src/main/kotlin/io/whozoss/factory/delivery/`.
   - All integration tests MUST reside under `factory-service/src/test/kotlin/io/whozoss/factory/delivery/`.
4. **CRITICAL SPRING TEST RULE**:
   - ALL integration tests MUST extend `DomainIntegrationTest` (or `PostgresContainerSpec` through context reuse).
   - DO NOT introduce custom `@SpringBootTest` configurations, custom webEnvironment overrides, or custom `@TestPropertySource` / `@MockBean` annotations that fragment the Spring Test context cache.
5. **VERIFICATION & OPENAPI**:
   - Run `./gradlew clean test` from `factory-service/` (or root) to verify all unit and integration tests pass in a single test run without HikariPool connection eviction errors.
   - Regenerate OpenAPI spec using `./gradlew generateOpenApiDocs --no-configuration-cache` from `factory-service/` and verify using `./check-openapi-spec.sh`.

---

## 1. Migration V8 (`V8__delivery.sql`)

File: `factory-service/src/main/resources/db/migration/V8__delivery.sql`

Conventions (matching V1-V7):
- Every row is tenant-scoped by non-null `organization_id` (DEFAULT 'default') and `workstream_id` (DEFAULT 'default').
- Composite PKs ensure multi-tenant isolation.
- `deliveries` table has columns:
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `namespace_id VARCHAR(255) NOT NULL`
  - `delivery_id VARCHAR(255) NOT NULL`
  - `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
  - `stage VARCHAR(64) NOT NULL`
  - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  - `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  - `PRIMARY KEY (organization_id, workstream_id, namespace_id, delivery_id)`
- `delivery_journal` table has columns:
  - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  - `namespace_id VARCHAR(255) NOT NULL`
  - `delivery_id VARCHAR(255) NOT NULL`
  - `record_sequence INTEGER NOT NULL`
  - `record_id VARCHAR(255) NOT NULL`
  - `record_type VARCHAR(64) NOT NULL`
  - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  - `PRIMARY KEY (organization_id, workstream_id, namespace_id, delivery_id, record_sequence)`
  - `CONSTRAINT delivery_journal_delivery_fk FOREIGN KEY (organization_id, workstream_id, namespace_id, delivery_id) REFERENCES deliveries(organization_id, workstream_id, namespace_id, delivery_id) ON DELETE CASCADE`
- Index: `idx_delivery_journal_lookup` on `delivery_journal (organization_id, workstream_id, namespace_id, delivery_id, created_at)`
- Trigger: Attach `set_updated_at()` trigger function (created in V2) on `deliveries`.

---

## 2. Domain & Ports (`io.whozoss.factory.delivery.*`)

### Domain (`io.whozoss.factory.delivery.domain`)
- **Canonical Hashing Helper**: Pure Jackson / Kotlin utility that sorts JSON object keys recursively (dropping nulls/absents, keeping array orders) and calculates SHA-256 hex digest (`canonicalHash` or `canonicalDeliveryHash`).
- **DeliveryDefinition**:
  - `DELIVERY_STAGES`: `['implementation-ready', 'artifact-ready', 'release-approved', 'deployed', 'production-verified']`
  - `DELIVERY_EVIDENCE_KINDS`: `['implementation-result', 'artifact', 'oracle-result', 'human-decision', 'deployment-result', 'smoke-result', 'rollback-result']`
  - Models: `DeliveryResponsibility`, `DeliveryRequiredEvidence`, `DeliveryCheckpoint`, `DeliveryDefinition`.
  - Functions: `validateDeliveryDefinition(input: Map<String, Any?>)`, `hashDeliveryDefinition(definition)`, `defaultDeliveryDefinition()`.
- **DeliveryPolicy**:
  - `DELIVERY_INITIAL_STAGE`: `'implementation-ready'`
  - Models: `DeliveryPromotionRequest`, `DeliveryExecutionContext`, `DeliveryPromotionSnapshot`, `DeliveryEvidenceItem`, `HashedDeliveryDefinition`, `DeliveryPromotionDecision`.
  - Functions: `validateDeliveryPromotionRequest(...)`, `deliveryScopeHash(...)`, `deliverySemanticHash(...)`, `evaluateDeliveryPromotion(...)`, `applyDeliveryPromotion(...)`.
- **DeliveryOperationDefinition**:
  - `DELIVERY_OPERATION_KINDS`: `['deployment', 'production-verification', 'rollback', 'rollback-verification']`
  - `DELIVERY_OPERATION_STATES`: `['pending', 'running', 'succeeded', 'failed', 'indeterminate']`
  - Models: `DeliveryArtifactRef`, `DeliveryReleaseRef`, `DeliveryDeploymentRef`, `NormalizedDeliveryOperationRequest`, `DeliveryOperationObservation`, `DeliveryOperationRecord`.
  - Functions: `normalizeDeliveryOperationRequest(...)`, `deriveDeliveryOperationIdentity(...)`, `validateDeliveryOperationRecord(...)`, `validateDeliveryOperationTransition(...)`.
- **DeliveryOperationPolicy**:
  - Models: `DeliveryOperationPolicySnapshot`, `DeliveryOperationPolicyTarget`, `DeliveryOperationPolicyEvaluation`, `DeliveryOperationPolicyDecision`.
  - Functions: `evaluateDeliveryOperationPolicy(...)`, `resolveDeliveryVerificationRequest(...)`.
- **Domain Exceptions**:
  - `DeliveryException` extending `FactoryException` with codes: `INVALID_DELIVERY_DEFINITION`, `INVALID_DELIVERY_REQUEST`, `DELIVERY_NOT_FOUND`, `REVISION_CONFLICT`, `DELIVERY_SCOPE_MISMATCH`, `DELIVERY_DEFINITION_MISMATCH`, `ILLEGAL_PROMOTION`, `ACTOR_NOT_AUTHORIZED`, `EVIDENCE_NOT_FOUND`, `EVIDENCE_SCOPE_MISMATCH`, `PASS_EVIDENCE_REQUIRED`, `IDEMPOTENCY_KEY_COLLISION`, `PULL_REQUEST_NOT_CONFIGURED`, `DELIVERY_TARGET_NOT_FOUND`, `DELIVERY_TARGET_HASH_MISMATCH`, `DELIVERY_OPERATION_INDETERMINATE`, etc.

### Ports & Adapters (`io.whozoss.factory.delivery.port`, `.persistence`, `.adapter`)
- **DeliveryRepository** (`io.whozoss.factory.delivery.persistence.DeliveryRepository`):
  - Interface methods matching the Node `DeliveryRepository` / `SqlDeliveryRepository`:
    - `read(scope: TenantScope, namespaceId: String, deliveryId: String): DeliverySnapshot?`
    - `create(scope: TenantScope, snapshot: DeliverySnapshot): DeliveryStoreWriteResult`
    - `promote(scope: TenantScope, input: DeliveryStorePromoteInput): DeliveryStoreWriteResult`
    - `readWithOperations(scope: TenantScope, namespaceId: String, deliveryId: String): DeliveryWithOperations?`
    - `inspectDeliveryOperations(scope: TenantScope, namespaceId: String, deliveryId: String): DeliveryOperationProjection`
    - `createRollbackRequest(scope: TenantScope, input: DeliveryStoreRollbackRequestInput): DeliveryStoreWriteResult`
    - `approveRollbackRequest(scope: TenantScope, namespaceId: String, deliveryId: String, rollbackRequestId: String, approval: DeliveryStoreRollbackApprovalInput): DeliveryStoreWriteResult`
    - `createDeliveryOperation(scope: TenantScope, input: DeliveryStoreOperationInput): DeliveryStoreWriteResult`
    - `recordDeliveryOperation(...)`, `startDeliveryOperation(...)`, `reconcileDeliveryOperation(...)`
    - `hasIndeterminateOperation(scope: TenantScope, namespaceId: String, deliveryId: String): Boolean`
    - `updateSnapshot(...)`
- **SqlDeliveryRepository** (`io.whozoss.factory.delivery.persistence.SqlDeliveryRepository`):
  - Implements `DeliveryRepository` using `NamedParameterJdbcTemplate` and Spring `@Transactional`.
  - Implements optimistic locking (`revision`), JSONB serialization/deserialization with Jackson, append-only `delivery_journal` appending with incremental `record_sequence`, and idempotency checking (`IDEMPOTENCY_KEY_COLLISION` on scope/semantic hash mismatches).
- **DeliveryEvidenceStore / Service**:
  - `DeliveryEvidenceStore`: Reads evidence from `workflow_evidence` table (introduced in V5) for the given scope, workflow, or delivery context.
- **Git & PR Ports**:
  - `DeliveryGitControlPlane`: Port for git checkpoint and push. Stub implementation `StubDeliveryGitControlPlane`.
  - `DeliveryPullRequestAdapter`: Port for pull request operations. Returns `PULL_REQUEST_NOT_CONFIGURED` if not configured (e.g. stubbed implementation `StubDeliveryPullRequestAdapter`).
  - `DeliveryTargetRegistry`: Port/stub for resolving delivery target configurations and targets.

---

## 3. Application & Controller (`io.whozoss.factory.delivery.service`, `.web`)

### Service Layer (`io.whozoss.factory.delivery.service`)
- `DeliveryService`: Orchestrates resolution of workflow environments, delivery definitions, workflow snapshots, promotion policy evaluation, and repository writes.
- `DeliveryOperationService`: Orchestrates normalized operation preparation, target binding, operation policy evaluation, execution via adapters/stubs, and journal state recording/reconciliation.

### Controller Layer (`io.whozoss.factory.delivery.web`)
- **Base Endpoint**: `/api/factory/workflows/{workflowId}/delivery`
- **Routes**:
  - `GET /api/factory/workflows/{workflowId}/delivery` -> Get status / snapshot with operations
  - `POST /api/factory/workflows/{workflowId}/delivery/checkpoint` -> Create git checkpoint
  - `POST /api/factory/workflows/{workflowId}/delivery/push` -> Push git branch
  - `POST /api/factory/workflows/{workflowId}/delivery/pull-request` -> Create/check PR (returns 503 `PULL_REQUEST_NOT_CONFIGURED` if PR adapter is unconfigured)
  - `POST /api/factory/workflows/{workflowId}/delivery/promote` -> Evaluate & apply promotion
  - `POST /api/factory/workflows/{workflowId}/delivery/evidence` -> Record delivery evidence item
  - `POST /api/factory/workflows/{workflowId}/delivery/deploy` -> Create & execute deployment operation
  - `POST /api/factory/workflows/{workflowId}/delivery/verify` -> Create & execute production verification operation
  - `POST /api/factory/workflows/{workflowId}/delivery/deployment/reconcile` -> Reconcile operation outcome
  - `POST /api/factory/workflows/{workflowId}/delivery/rollbacks` -> Create rollback request
  - `POST /api/factory/workflows/{workflowId}/delivery/rollbacks/{rollbackRequestId}/approve` -> Approve rollback request
  - `POST /api/factory/workflows/{workflowId}/delivery/rollbacks/{rollbackRequestId}/execute` -> Execute rollback operation
  - `POST /api/factory/workflows/{workflowId}/delivery/rollbacks/{rollbackRequestId}/verify` -> Verify rollback operation
- **Trust Context & Authentication**:
  - Uses `@Parameter(hidden = true) trustContext: TrustContext?` resolved from `TrustContextArgumentResolver`.
  - Resolves `TenantScope` using `TenantScopeProvider.scopeOf(trustContext)`.
  - If `trustContext` is missing or unauthenticated, throws `UnauthenticatedException` (mapping to HTTP 401 `TRUST_CONTEXT_UNAVAILABLE` or `UNAUTHENTICATED`).
  - Strict rejection of untrusted body fields (e.g. forbidden system fields like `worktreePath`, `command`, `credentials`).
- **Response Format**:
  - Success: `ResponseEntity.ok(DataEnvelope(data))` or `201 Created` with `{ "data": ... }`.
  - Error: Processed via `FactoryExceptionHandler` into `{ "error": { "code": "...", "message": "...", "details": ... } }`.

---

## 4. Integration Tests (`factory-service/src/test/kotlin/io/whozoss/factory/delivery/`)

- Test classes:
  1. `DeliveryRepositoryIntegrationTest`: Tests `SqlDeliveryRepository` snapshot persistence, journal appends, optimistic locking revision failures, rollback request approval, and idempotency collision checks (`IDEMPOTENCY_KEY_COLLISION`).
  2. `DeliveryPromotionPolicyIntegrationTest`: Tests ordered stage promotions, definition mismatch, evidence gating, scope verification, and actor authorization checks.
  3. `DeliveryOperationIntegrationTest`: Tests operation normalization, state machine transitions (pending -> running -> succeeded/failed), indeterminate reconciliation, and rollback execution.
  4. `DeliveryControllerHttpTest`: Tests HTTP endpoints under `/api/factory/workflows/{workflowId}/delivery/...` using `MockMvc` or `TestRestTemplate`, verifying headers, TrustContext resolution, 401 unauthenticated handling, 503 `PULL_REQUEST_NOT_CONFIGURED` when PR is unconfigured, and successful `{ "data": ... }` responses.
- **CRITICAL REIFICATION**:
  - ALL test classes MUST extend `DomainIntegrationTest` (or inherit from `PostgresContainerSpec`).
  - Clean slate logic in `DomainIntegrationTest.resetControlPlane()` updated to also purge `delivery_journal` and `deliveries` tables for `ORGANIZATION_ID`.

---

## 5. Verification Steps

1. Run Gradle clean and test suite:
   ```bash
   cd factory-service
   ./gradlew clean test
   ```
   Ensure zero context cache evictions, zero Hikari pool starvation, and 100% test pass rate across all packages.

2. Regenerate OpenAPI Spec & Validate:
   ```bash
   cd factory-service
   ./gradlew generateOpenApiDocs --no-configuration-cache
   ./check-openapi-spec.sh
   ```

---

## Summary of Files to Create

1. `factory-service/src/main/resources/db/migration/V8__delivery.sql`
2. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/domain/DeliveryDefinition.kt`
3. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/domain/DeliveryPolicy.kt`
4. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/domain/DeliveryOperationDefinition.kt`
5. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/domain/DeliveryOperationPolicy.kt`
6. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/domain/DeliveryExceptions.kt`
7. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/persistence/DeliveryRepository.kt`
8. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/persistence/SqlDeliveryRepository.kt`
9. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/port/DeliveryEvidenceStore.kt`
10. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/port/DeliveryGitControlPlane.kt`
11. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/port/DeliveryPullRequestAdapter.kt`
12. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/port/DeliveryTargetRegistry.kt`
13. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/service/DeliveryService.kt`
14. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/service/DeliveryOperationService.kt`
15. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/web/DeliveryDtos.kt`
16. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/web/DeliveryController.kt`
17. `factory-service/src/main/kotlin/io/whozoss/factory/delivery/web/DeliveryOperationController.kt`
18. `factory-service/src/test/kotlin/io/whozoss/factory/delivery/DeliveryRepositoryIntegrationTest.kt`
19. `factory-service/src/test/kotlin/io/whozoss/factory/delivery/DeliveryPromotionPolicyIntegrationTest.kt`
20. `factory-service/src/test/kotlin/io/whozoss/factory/delivery/DeliveryOperationIntegrationTest.kt`
21. `factory-service/src/test/kotlin/io/whozoss/factory/delivery/DeliveryControllerHttpTest.kt`
22. Update `factory-service/src/test/kotlin/io/whozoss/factory/DomainIntegrationTest.kt` (to clean `delivery_journal` and `deliveries`).
