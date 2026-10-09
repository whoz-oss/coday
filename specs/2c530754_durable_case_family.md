# Lot B: Durable Case Family (rootCaseId / parentCaseId) Plan

## Overview
This plan implements Lot B (Durable Case Family) for plugin-driven execution runs in `factory/` (`factory-service`).
The goal is to maintain case/worktree continuity across step dispatches, retries, and crashes within a workflow execution run without altering external transport layers (Lot C), step result contracts (Lot E), or UI components.

---

## Requirements & Design Analysis

### 1. Models & Persistence

#### A. `DurableAgentAttempt.kt`
- Add two optional fields to `DurableAgentAttempt`:
  - `rootCaseId: String? = null`
  - `parentCaseId: String? = null`

#### B. `DurableAgentAttemptNode.kt`
- Add `val rootCaseId: String? = null` and `val parentCaseId: String? = null` to `DurableAgentAttemptNode`.
- Update `toDomain()` mapping to set `rootCaseId = rootCaseId` and `parentCaseId = parentCaseId`.
- Update `fromDomain()` mapping in `companion object` to copy `attempt.rootCaseId` and `attempt.parentCaseId`.

#### C. `SpringDataNeo4jDurableAgentAttemptRepository.kt`
- In `register` `@Query`, add `a.rootCaseId = $rootCaseId` and `a.parentCaseId = $parentCaseId` under `ON CREATE SET`.
- Add parameters `rootCaseId: String?` and `parentCaseId: String?` to `fun register(...)`.

#### D. `Neo4jDurableAgentAttemptRepository.kt`
- Update all calls to `attempts.register(...)` to pass `attempt.rootCaseId` and `attempt.parentCaseId`.

#### E. `WorkflowInstanceRecord.kt`
- Add `val rootCaseId: String? = null` to `WorkflowInstanceRecord`.

#### F. `WorkflowInstanceNode.kt`
- Add `val rootCaseId: String? = null` to `WorkflowInstanceNode`.
- Update `toDomain(objectMapper)` to populate `rootCaseId = rootCaseId`.
- Update `fromDomain(...)` to map `record.rootCaseId`.

#### G. `WorkflowRepository.kt` & `Neo4jWorkflowRepository.kt` & `SpringDataNeo4jWorkflowRepositories.kt`
- Add atomic query in `SpringDataNeo4jWorkflowInstanceRepository`:
  ```kotlin
  @Query(
      """
      MATCH (i:WorkflowInstance {id: $id})
      WHERE i.status = 'active' AND i.rootCaseId IS NULL
      SET i.rootCaseId = $rootCaseId,
          i.revision = i.revision + 1,
          i.updatedAt = $updatedAt
      RETURN count(i) AS updated
      """
  )
  fun reserveRootCase(
      @Param("id") id: String,
      @Param("rootCaseId") rootCaseId: String,
      @Param("updatedAt") updatedAt: Instant,
  ): Long
  ```
- Declare in `WorkflowRepository`:
  ```kotlin
  fun reserveRootCase(scope: TenantScope, namespaceId: String, workflowId: String, rootCaseId: String): Boolean
  ```
- Implement in `Neo4jWorkflowRepository`:
  Calls `instances.reserveRootCase(instanceId(scope, namespaceId, workflowId), rootCaseId, Instant.now()) > 0`. If `rootCaseId` is already set and equals the passed `rootCaseId`, or if atomic CAS succeeds, return true.

---

### 2. Execution Orchestration in `CapabilityExecutionService.kt`

#### A. Entry Step Candidate Check & Validation
- Identify candidate entry agent steps in the workflow definition/projection: steps with `responsibility.kind == ResponsibilityKind.AGENT` and `dependsOn.isEmpty()`.
- **Refusal rule**: If candidate entry agent steps count == 0 or candidate entry agent steps count > 1, **REFUSE explicitly with a clear error** (e.g. `IllegalStateException("Workflow execution run requires exactly 1 entry agent step, found $count")` or dedicated exception/outcome).

#### B. Concurrency Control & Root Case Reservation
- BEFORE any remote calls for agent steps in `resolveAgentViaAdapter`:
  - Check `workflowInstance.rootCaseId`.
  - If null, generate a unique root case ID (e.g. `case-root-$workflowId` or `case-${UUID.randomUUID()}`) and call `workflowRepository.reserveRootCase(scope, namespaceId, workflowId, candidateRootCaseId)`.
  - Re-read the workflow instance after reservation attempt to get the winning `rootCaseId` atomically.
  - Two concurrent runs in the SAME namespace must have distinct root cases (ensured because each workflow instance has its own `rootCaseId`).

#### C. Case ID & Parent Case ID Assignment Logic
- First agent step (`step.dependsOn.isEmpty()`):
  - `caseId` = reserved `rootCaseId` (or step's initial case ID set to `rootCaseId`).
  - `rootCaseId` = reserved `rootCaseId`.
  - `parentCaseId` = null.
- Subsequent agent steps (`step.dependsOn.isNotEmpty()`):
  - `caseId` = stable step case ID (or step-specific case ID).
  - `rootCaseId` = workflow instance's `rootCaseId`.
  - `parentCaseId` = workflow instance's `rootCaseId`.
- Retry of a failed step (`existing != null` with previous attempt):
  - Use the SAME worktree / root case (`rootCaseId` = instance's `rootCaseId`).
  - Create a NEW sub-case (`caseId` = new unique sub-case ID, e.g. `case-$workflowId-${step.id}-retry-$attemptNumber` or UUID).
  - Set `parentCaseId` = instance's `rootCaseId`.
  - **NEVER reuse a failed case ID, NEVER change worktree.**

#### D. Crash Recovery & Legacy Compatibility
- On crash recovery (resuming an attempt or step execution):
  - Re-read persisted `rootCaseId` and `parentCaseId` from workflow instance / attempt records rather than recreating them.
  - **Strict compatibility**: DO NOT convert legacy runs without `rootCaseId` into new case families (keep `rootCaseId` and `parentCaseId` as `null` for legacy runs where `workflowInstance.rootCaseId` is null and no rootCaseId was persisted).

---

### 3. Verification & Tests

Create/update unit and integration tests in `factory-service`:
1. `DurableCaseFamilyIntegrationTest.kt` (or within existing `CapabilityExecutionIntegrationTest.kt` / `DurableAgentOsBridgeIntegrationTest.kt`):
   - **Concurrent dispatch**: Exactly one root case reserved on workflow instance.
   - **Crash after reservation + resumption**: Workflow resumes with exact same root case without recreating.
   - **Retry of failed step**: Generates new sub-case `caseId`, retains same `rootCaseId` and sets `parentCaseId = rootCaseId`.
   - **Isolation between runs**: Two runs in same namespace have distinct root cases / worktrees.
   - **Legacy data read**: Legacy workflow instance without `rootCaseId` remains `null`.
   - **Refusal rule**: Workflow with 0 or >1 candidate entry agent steps refuses execution explicitly.

---

## File Modification Plan

### Target Files to Touch:
1. `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/DurableAgentAttempt.kt`
2. `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/DurableAgentAttemptNode.kt`
3. `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/SpringDataNeo4jDurableAgentAttemptRepository.kt`
4. `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/Neo4jDurableAgentAttemptRepository.kt`
5. `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowModels.kt` (`WorkflowInstanceRecord`)
6. `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/WorkflowInstanceNode.kt`
7. `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/SpringDataNeo4jWorkflowRepositories.kt`
8. `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/WorkflowRepository.kt`
9. `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/Neo4jWorkflowRepository.kt`
10. `factory/factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt`
11. `factory/factory-service/src/test/kotlin/io/whozoss/factory/capability/DurableCaseFamilyIntegrationTest.kt` (New Test File)

---

## Execution Verification Command
- `pnpm nx test factory-service`
