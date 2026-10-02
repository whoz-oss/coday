# Exposer HTTP GET /api/factory/workflows/{workflowId}/attempts dans factory-service

## Overview
Exposer un nouvel endpoint HTTP GET `/api/factory/workflows/{workflowId}/attempts` dans `WorkflowController.kt` de `factory-service` afin de permettre au Cockpit V2 de lire la liste des tentatives (`DurableAgentAttempt`) associées à un workflow.

Chaque tentative est récupérée via `DurableAgentAttemptService.findByWorkflow(caller.scope, caller.namespaceId, workflowId)` et cartographiée vers un DTO de lecture restreint (`DurableAgentAttemptDto`) excluant strictement tous les secrets et données internes (`ownerToken`, `capabilityToken`, `commandId`, `brief`, `leaseExpiresAt`, `lastObservedEventId`, `turnCorrelation`). Le résultat est enveloppé dans `WorkflowDataEnvelope(listOfDtos)`.

## Files to Modify / Create

1. **New DTO class**: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/DurableAgentAttemptDto.kt` (or inside `WorkflowModels.kt` or `DurableAgentAttempt.kt` - prefer creating a dedicated DTO file in `agentattempt/domain` or `workflow/domain`, or inline/dataclass in `DurableAgentAttemptDto.kt`).
2. **Controller**: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt`
3. **Tests**: `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowControllerHttpTest.kt`

---

## Detailed Implementation Plan

### 1. DTO Definition (`DurableAgentAttemptDto`)

Location: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/DurableAgentAttemptDto.kt` (or `io.whozoss.factory.workflow.domain.DurableAgentAttemptDto`)

```kotlin
package io.whozoss.factory.agentattempt.domain

import java.time.Instant

/**
 * Bounded read DTO for durable agent attempts exposed over the public API.
 * Excludes sensitive execution tokens and internal lease/recovery data.
 */
data class DurableAgentAttemptDto(
    val attemptId: String,
    val stepId: String,
    val attemptNumber: Int,
    val agentName: String,
    val status: String,
    val caseId: String,
    val failureCode: String? = null,
    val resultEvidenceId: String? = null,
    val revision: Int,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
)

fun DurableAgentAttempt.toDto(): DurableAgentAttemptDto = DurableAgentAttemptDto(
    attemptId = attemptId,
    stepId = stepId,
    attemptNumber = attemptNumber,
    agentName = agentName,
    status = status.dbValue,
    caseId = caseId,
    failureCode = failureCode,
    resultEvidenceId = resultEvidenceId,
    revision = revision,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
)
```

**Fields Verification**:
- `attemptId`: String
- `stepId`: String
- `attemptNumber`: Int
- `agentName`: String
- `status`: String (`attempt.status.dbValue`)
- `caseId`: String
- `failureCode`: String?
- `resultEvidenceId`: String?
- `revision`: Int
- `createdAt`: Instant
- `startedAt`: Instant?
- `completedAt`: Instant?

**Excluded fields**:
- `ownerToken`, `capabilityToken`, `commandId`, `brief`, `leaseExpiresAt`, `lastObservedEventId`, `turnCorrelation`, `namespaceId`, `workflowId`, `updatedAt`.

---

### 2. Update `WorkflowController.kt`

Location: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt`

#### Constructor Update
Inject `DurableAgentAttemptService`:
```kotlin
class WorkflowController(
    private val service: WorkflowService,
    private val sessionRunService: SessionRunService,
    private val sessionRunSubmissionService: SessionRunSubmissionService,
    private val sessionProperties: SessionProperties,
    private val tenantScopeProvider: TenantScopeProvider,
    private val agentOsProxyClient: AgentOsProxyClient,
    private val durableAgentAttemptService: DurableAgentAttemptService,
    private val bridgeCancellationService: BridgeCancellationService? = null,
)
```

#### New Endpoint Implementation
Add endpoint to `WorkflowController`:
```kotlin
    @GetMapping(path = ["/{workflowId}/attempts"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "List execution attempts for a workflow.")
    fun listAttempts(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<List<DurableAgentAttemptDto>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        val attempts = durableAgentAttemptService.findByWorkflow(caller.scope, caller.namespaceId, workflowId)
        return WorkflowDataEnvelope(attempts.map { it.toDto() })
    }
```

**Notes on Response**:
- When `durableAgentAttemptService.findByWorkflow` returns empty list, `attempts.map { it.toDto() }` produces `emptyList()`.
- `WorkflowDataEnvelope(emptyList())` serializes to JSON as `{ "data": [] }` with HTTP status 200 OK.

---

### 3. Update Integration Tests in `WorkflowControllerHttpTest.kt`

Location: `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowControllerHttpTest.kt`

Add test cases in `WorkflowControllerHttpTest`:

1. **Autowired Dependency**: `@Autowired private lateinit var durableAgentAttemptService: DurableAgentAttemptService`
2. **Test 1: Reading workflow attempts when attempts exist**:
   - Register a definition and/or start a workflow or register attempts directly via `durableAgentAttemptService.register(...)`.
   - Register 2 attempts for a `workflowId` (e.g., `wf-attempts-test`).
   - `DurableAgentAttempt` setup:
     - Include secret/internal fields: `ownerToken = "secret-owner"`, `capabilityToken = "secret-cap"`, `brief = "secret-brief"`, `commandId = "cmd-123"`, `leaseExpiresAt = Instant.now()`, `lastObservedEventId = "evt-99"`, `turnCorrelation = "corr-1"`.
   - Execute `GET /api/factory/workflows/{workflowId}/attempts?namespaceId={namespace}` with test restTemplate.
   - Assert response status is 200 OK.
   - Assert `data` is a List of maps.
   - Assert fields present: `attemptId`, `stepId`, `attemptNumber`, `agentName`, `status`, `caseId`, `failureCode`, `resultEvidenceId`, `revision`, `createdAt`, `startedAt`, `completedAt`.
   - Assert `status` matches `dbValue` string (e.g. `"pending"` or `"succeeded"`).
   - Assert sensitive fields are **NOT** present in the JSON response (`ownerToken`, `capabilityToken`, `brief`, `leaseExpiresAt`, `commandId`, `lastObservedEventId`, `turnCorrelation`).
3. **Test 2: Reading workflow attempts when no attempts exist (Empty list degradation)**:
   - Execute `GET /api/factory/workflows/wf-nonexistent-attempts/attempts?namespaceId={namespace}`.
   - Assert response status is 200 OK.
   - Assert response JSON is `{ "data": [] }`.

---

## Verification & Test Plan

1. Execute the target Gradle test suite:
   ```bash
   cd factory-service && ./gradlew test --tests "io.whozoss.factory.workflow.WorkflowControllerHttpTest"
   ```
2. Verify that existing tests in `WorkflowControllerHttpTest` pass without regression.
3. Verify newly added HTTP test cases for workflow attempts pass.
