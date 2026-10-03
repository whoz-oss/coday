# Implementation Plan: Port Aggregate A6 (Workflow + SSE) to Kotlin/Spring Boot

This plan details the step-by-step port of Aggregate A6 (Workflow definitions, instances, projections, evidence logs, human interactions, and SSE streaming) from Node/TypeScript (`factory/`) to Kotlin/Spring Boot (`factory-service/`).

---

## 1. Database & Migrations

### Current Status
- `V1__init_workflow_pilot_schema.sql` creates `workflow_definitions` and `workflow_instances` (with `revision`, `status`, `instance_json`, `projection_json`, `hash`, etc.).
- `V3__workflow_core.sql` creates `workflow_projections` and `workflow_code_transitions`.
- `V5__evidence_and_interaction.sql` creates `workflow_evidence`, `human_interactions`, and `human_interaction_events`.
- All required tables already exist in V1, V3, and V5.
- Composite primary keys are scoped by `(organization_id, workstream_id, namespace_id, workflow_id)`.
- Foreign keys cascade on delete.

### Action
- Do **NOT** alter or rename V1..V8.
- Create `factory-service/src/main/resources/db/migration/V9__workflow.sql` if any additional table/index/column is needed for workflow aggregate compliance (e.g., `IF NOT EXISTS` indexes for performance, such as index on `workflow_projections (organization_id, workstream_id, namespace_id, updated_at)` or `workflow_evidence (organization_id, workstream_id, namespace_id, workflow_id)`).

---

## 2. Package `io.whozoss.factory.workflow`

Create package directory `factory-service/src/main/kotlin/io/whozoss/factory/workflow/` with domain models, repositories, services, controllers, and SSE hub.

### 2.1 Canonical Key Sorting Hash Algorithm (`CanonicalHash.kt`)
Port canonical key sorting and SHA-256 calculation matching Node TS `canonicalize` / `canonicalizeWorkflowDefinition`:
1. `canonicalize(value: Any?): Any?`
   - If `value` is `Map<*, *>` (or Jackson `ObjectNode`/JsonObject): filter out entries where value is `null`, sort keys alphabetically (`compareTo` or natural String ordering), recursively canonicalize each value, and return a `LinkedHashMap`.
   - If `value` is `Collection<*>` (or Jackson `ArrayNode`): recursively canonicalize each element.
   - If `value` is primitive/string/number/boolean: return as-is.
2. `canonicalizeJson(value: Any?): String`
   - Serialize the canonicalized object tree to compact JSON using Jackson `ObjectMapper` with no indentation and default key ordering.
3. `sha256Hex(input: String): String`
   - SHA-256 digest formatted as a lowercase hex string.
4. `workflowStartCommandHash(value: Any?): String`
   - Computes SHA-256 of `canonicalizeJson(value)`.
5. `workflowProjectionHash(projection: Map<String, Any?>): String`
   - Computes SHA-256 of `canonicalizeJson(projection)`.

### 2.2 Domain Models
Define Kotlin data classes for domain entities and transfer objects:
- `WorkflowDefinition(organizationId, workstreamId, namespaceId, workflowId, type, version, schemaJson, createdBy, createdAt, updatedAt)`
- `WorkflowInstance(organizationId, workstreamId, namespaceId, workflowId, revision, status, hash, instanceJson, projectionJson, createdAt, updatedAt)`
- `WorkflowProjection(organizationId, workstreamId, namespaceId, workflowId, revision, hash, status, state, contextJson, createdAt, updatedAt)`
- `WorkflowEvidence(organizationId, workstreamId, namespaceId, workflowId, evidenceId, stepId, type, payloadJson, createdBy, createdAt)`
- `HumanInteraction(organizationId, workstreamId, namespaceId, workflowId, interactionId, stepId, type, title, description, schemaJson, status, responseJson, createdBy, createdAt, updatedAt)`
- `HumanInteractionEvent(organizationId, workstreamId, namespaceId, workflowId, interactionId, eventId, eventType, actorId, payloadJson, createdAt)`
- `WorkflowTransition(organizationId, workstreamId, namespaceId, workflowId, transitionId, stepId, action, fromState, toState, payloadJson, createdAt)`
- `WorkflowCodeTransition(organizationId, workstreamId, namespaceId, workflowId, transitionId, stepId, code, errorJson, createdAt)`
- Metric models: `WorkflowTimingMetrics`, `WorkflowRetriesMetrics`, `WorkflowAggregatedMetrics`.

### 2.3 Repositories (`WorkflowRepository.kt`, `WorkflowEvidenceRepository.kt`, `HumanInteractionRepository.kt`)
Use `NamedParameterJdbcTemplate` and composite tenant key scoping `(organization_id, workstream_id, namespace_id, ...)` via `TenantScope`:

1. **`WorkflowRepository`**:
   - `saveDefinition(...)` / `findDefinition(...)` / `listDefinitions(...)`
   - `findInstance(...)` / `saveInstance(...)`
   - `findProjection(...)` / `upsertProjection(...)`:
     - Implement optimistic locking support (`expectedRevision` parameter).
     - If `expectedRevision` is provided:
       `UPDATE workflow_projections SET revision = revision + 1, hash = :hash, status = :status, state = :state, context_json = :contextJson, updated_at = NOW() WHERE organization_id = :org AND workstream_id = :ws AND namespace_id = :ns AND workflow_id = :wf AND revision = :expectedRevision`
     - If rows affected == 0, throw `WorkflowOptimisticLockException` (maps to 409 OPTIMISTIC_LOCK_CONFLICT or REVISION_MISMATCH).
   - `purgeWorkflow(...)` / `restoreWorkflow(...)`: Delete from or mark soft-deleted in projections/instances.
   - `getTimingMetrics(...)` / `getRetriesMetrics(...)` / `getAggregatedMetrics(...)`

2. **`WorkflowEvidenceRepository`**:
   - `appendEvidence(...)`: Insert row into `workflow_evidence`. Append-only (no update/delete).
   - `listEvidence(...)`: Query by tenant scope + workflowId, sorted by `created_at ASC`.

3. **`HumanInteractionRepository`**:
   - `createInteraction(...)`: Insert row into `human_interactions` with status `PENDING`.
   - `findInteraction(...)` / `listInteractions(...)`
   - `replyInteraction(...)`: Update status to `COMPLETED`, record `response_json`, insert event into `human_interaction_events`.

### 2.4 Service & Transactional Atomicity (`WorkflowService.kt`)
Implement core workflow operations with strict transactional atomicity (`@Transactional`):

- **Atomic Human Interaction Reply & Transition**:
  When replying to a human interaction or executing a workflow transition:
  1. Record interaction reply in `human_interactions` and append `human_interaction_events`.
  2. Append evidence to `workflow_evidence`.
  3. Update projection / state transition in `workflow_projections` and `workflow_instances` with `expectedRevision` check.
  All executed within **ONE single `@Transactional` method** using `NamedParameterJdbcTemplate` and `TenantScope`.

- **Event Publishing for SSE**:
  After transaction commits (or within service), publish `WorkflowProjectionSseEvent` to notify active SSE listeners:
  - `workflow-projection-updated`
  - `workflow-projection-removed`
  - `workflow-projection-restored`
  - `workflow-projection-purged`

---

## 3. REST Controllers (`factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/`)

Create controllers mapped under `/api/factory/...` matching `factory/dashboard/openapi.json`.
Extract `TrustContext` via `TrustContextExtractor` / `TrustContext` bean in controller methods (return 401 `TRUST_CONTEXT_UNAVAILABLE` when missing). Format success responses in `{ "data": ... }`. Errors are automatically handled by `FactoryExceptionHandler`.

### Endpoints List
1. **`GET /api/factory/workflow-definitions`**: List workflow definitions.
2. **`POST /api/factory/workflow-definitions`**: Register/upsert a workflow definition.
3. **`GET /api/factory/workflow-definitions/{definitionId}`**: Get definition detail.
4. **`POST /api/factory/workflows/start`**: Start workflow instance. Calculate canonical hash, insert `workflow_instances` & initial `workflow_projections`.
5. **`POST /api/factory/workflows/run`**: Run full workflow execution.
6. **`POST /api/factory/workflows/continue`**: Continue paused workflow execution.
7. **`POST /api/factory/workflows/transitions`**: Record transition.
8. **`POST /api/factory/workflows/code-transitions`**: Record code execution transition.
9. **`GET /api/factory/workflows/{workflowId}/projection`**: Get projection v1/v2 for workflow.
10. **`POST /api/factory/workflows/{workflowId}/purge`**: Purge workflow instance and projection. Broadcast `workflow-projection-purged`.
11. **`POST /api/factory/workflows/{workflowId}/restore`**: Restore purged workflow. Broadcast `workflow-projection-restored`.
12. **`GET /api/factory/workflows/{workflowId}/retries`**: Get retries metrics for workflow.
13. **`GET /api/factory/workflows/{workflowId}/timing`**: Get timing metrics for workflow.
14. **`GET /api/factory/workflows/metrics`**: Get aggregated metrics across workflows.
15. **`GET /api/factory/workflows/{workflowId}/evidence`**: Get append-only evidence log for workflow.
16. **`POST /api/factory/workflows/{workflowId}/evidence`**: Append evidence to workflow log.
17. **`GET /api/factory/workflows/{workflowId}/interactions`**: List human interactions for workflow.
18. **`POST /api/factory/workflows/{workflowId}/interactions`**: Create human interaction request.
19. **`POST /api/factory/workflows/{workflowId}/interactions/{interactionId}/reply`**: Reply to human interaction request atomically (reply + evidence + transition in ONE transaction).

---

## 4. SSE Stream (`GET /api/factory/workflows/stream`) & Hub

### Implementation (`WorkflowSseHub.kt` & `WorkflowSseController.kt`)
1. **Endpoint**: `GET /api/factory/workflows/stream`
   - Annotated with `@Operation(hidden = true)` to exclude from OpenAPI client generator.
   - Return `SseEmitter`.
   - Headers: `Content-Type: text/event-stream`, `Cache-Control: no-cache, no-transform`, `Connection: keep-alive`, `X-Accel-Buffering: no`.
2. **Heartbeat**:
   - Scheduled task or executor sending `: heartbeat\n\n` comment frame every 30 seconds to connected emitters.
3. **Framing Format**:
   - Exact framing matching Node `workflow-projection-sse.mjs`:
     ```
     event:<event-name>
     data:<json-payload>

     ```
   - Named events:
     - `workflow-projection-updated`
     - `workflow-projection-removed`
     - `workflow-projection-restored`
     - `workflow-projection-purged`
4. **Subscription**:
   - Filter events by tenant scope `(organizationId, workstreamId, namespaceId)` derived from `TrustContext`.

---

## 5. Integration Tests (`factory-service/src/test/kotlin/io/whozoss/factory/workflow/`)

**Constraint**: ALL integration tests MUST extend `DomainIntegrationTest` to reuse Spring Test Context and avoid HikariPool connection eviction / context churn.

Write tests covering:
1. `CanonicalHashTest.kt`: Test unit key recursive sorting and SHA-256 hashing vs Node TS output vectors.
2. `WorkflowOptimisticLockIntegrationTest.kt`: Test `expectedRevision` optimistic locking behavior on `workflow_projections` (verifying success on expected revision matching and exception on stale revision).
3. `WorkflowEvidenceIntegrationTest.kt`: Test append-only evidence log behavior (verify evidence items cannot be mutated or deleted).
4. `WorkflowAtomicInteractionIntegrationTest.kt`: Test atomic interaction -> evidence -> transition in a single transaction (simulating failure to verify complete rollback).
5. `WorkflowSseIntegrationTest.kt`: Test SSE endpoint stream connection, event framing format (`event:\ndata:\n\n`), heartbeat comment framing (`: heartbeat\n\n`), and operation hiding in OpenAPI.

---

## 6. OpenAPI Spec Regeneration

1. Ensure `@Operation(hidden = true)` is applied to `GET /api/factory/workflows/stream`.
2. Run `./gradlew test` and verify OpenAPI generation task or run OpenAPI spec generation task in `factory-service/` to refresh openapi specs.

---

## Verification Plan

### Test Commands
Run from repo root:
```bash
# Run factory-service tests via Gradle
cd factory-service && ./gradlew clean test

# Run affected tests via Nx baseline
pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
```

### Key Assertions
- All workflow REST endpoints return `{ "data": ... }` envelopes.
- Missing `TrustContext` yields HTTP 401 with code `TRUST_CONTEXT_UNAVAILABLE`.
- Stale revision on workflow projection update yields HTTP 409 with conflict error.
- SSE stream outputs exact `: heartbeat\n\n` comments and named event frames.
- All integration tests pass extending `DomainIntegrationTest`.
