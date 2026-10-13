# Implementation Plan - Lot B: Durable Case Family (rootCaseId / parentCaseId)

## Overview

This plan details the changes required for Lot B: Durable Case Family (`rootCaseId` / `parentCaseId`) for plugin-driven execution runs in `factory/`.

The goal is to maintain case/worktree continuity across step dispatches, retries, and crashes within a workflow execution run. The first (entry) agent step resolves/creates a root case (corresponding to the root worktree resolved by AgentOS / AgentOS adapter), which is atomically reserved on the workflow instance. Subsequent steps and retries link back to this case family (`rootCaseId` and `parentCaseId`). Concurrent dispatches for the same workflow instance resolve to a single root case, while separate execution runs (even in the same namespace) receive distinct root cases.

---

## Technical Design & Architectural Summary

### 1. Domain & Persistence Extensions

#### `DurableAgentAttempt.kt`
- Add two nullable properties with default `null`:
  ```kotlin
  val rootCaseId: String? = null,
  val parentCaseId: String? = null,
  ```

#### `DurableAgentAttemptNode.kt`
- Add `@Nullable` properties `val rootCaseId: String? = null` and `val parentCaseId: String? = null`.
- Update `toDomain()` mapping to pass `rootCaseId` and `parentCaseId`.
- Update `fromDomain()` mapping to pass `attempt.rootCaseId` and `attempt.parentCaseId`.

#### `SpringDataNeo4jDurableAgentAttemptRepository.kt`
- Update `@Query` for `register`:
  - Cypher `ON CREATE SET`:
    ```cypher
    a.rootCaseId = $rootCaseId,
    a.parentCaseId = $parentCaseId,
    ```
  - Method parameters: add `rootCaseId: String?` and `parentCaseId: String?`.

#### `Neo4jDurableAgentAttemptRepository.kt` & `DurableAgentAttemptRepository.kt`
- Update calls to `attempts.register(...)` passing `attempt.rootCaseId` and `attempt.parentCaseId`.

#### `WorkflowInstanceRecord` & `WorkflowInstanceNode`
- Carry `rootCaseId` on the workflow instance state.
- `WorkflowInstanceRecord`: Add `val rootCaseId: String? = null`.
- `WorkflowInstanceNode`:
  - Add `val rootCaseId: String? = null`.
  - Update `toDomain(objectMapper)` to include `rootCaseId = rootCaseId`.
  - Update `fromDomain(scope, record, objectMapper)` to include `rootCaseId = record.rootCaseId`.
- `WorkflowRepository`:
  - Add `reserveRootCase(scope: TenantScope, namespaceId: String, workflowId: String, rootCaseId: String): WorkflowInstanceRecord` or atomic update mechanism in `Neo4jWorkflowRepository`.
  - In `Neo4jWorkflowRepository`, implement atomic reservation: if `rootCaseId` is already present on `WorkflowInstanceNode`, return the existing instance unchanged (preserving the existing `rootCaseId`). If null, set `i.rootCaseId = $rootCaseId`.

### 2. Execution Orchestration in `CapabilityExecutionService.kt`

#### Entry Step Identification & Refusal
- Define entry agent step rule: An agent step is an entry step if its `step.dependsOn.isEmpty()`.
- Identify entry candidate agent steps in the definition:
  - Count agent steps with `step.dependsOn.isEmpty()`.
  - When starting/dispatching, if there are **zero or multiple** candidate entry agent steps (`steps.filter { it.responsibility.kind == AGENT && it.dependsOn.isEmpty() }`), explicitly refuse execution by throwing an exception or returning a `CapabilityExecution` with failure/deferred outcome and clear error message (e.g., `"Invalid entry agent step configuration: expected exactly 1 entry agent step with no dependencies, found N"`).

#### Root Case Reservation & Concurrency
- Concurrency control: When an agent step is about to run, check if `workflowInstance.rootCaseId` is set.
- If null and step is entry step, generate candidate root case ID (e.g. `stableCaseId(workflowId, step.id)` or unique run case ID).
- Atomically reserve `rootCaseId` on `WorkflowInstanceNode` using atomic Neo4j query/transaction (`reserveRootCase`).
- Concurrent dispatches for the same workflow instance attempt `reserveRootCase`: Neo4j atomic update ensures exactly ONE root case ID is written and returned to both threads.

#### Case Family Hierarchy (`rootCaseId` / `parentCaseId` / `caseId`)
- **First / Entry Agent Step (Attempt 1)**:
  - `rootCaseId` = reserved workflow `rootCaseId`
  - `parentCaseId` = null (or `rootCaseId` per case model, default null for root step, `caseId` = `rootCaseId`).
- **Subsequent Steps in the same run**:
  - `rootCaseId` = workflow `rootCaseId`
  - `parentCaseId` = workflow `rootCaseId`
  - `caseId` = step sub-case ID or `stableCaseId(workflowId, step.id)`
- **Retry of a step (Attempt N+1)**:
  - Uses the SAME worktree (`rootCaseId` remains workflow `rootCaseId`).
  - Creates a NEW sub-case ID for `caseId` (never reuse a failed case ID).
  - Sets `parentCaseId = rootCaseId`.

#### Crash Recovery & Compatibility
- When re-driving an attempt after a crash, read persisted `rootCaseId`/`parentCaseId` from the attempt / workflow instance record. Do NOT re-generate or overwrite.
- **Strict Compatibility**: Legacy runs without `rootCaseId` (null) are loaded safely as `null`. DO NOT implicitly convert old runs into new case families.

#### Isolation
- Execution runs of the SAME namespace have distinct `workflowId`s and thus distinct root cases/worktrees.

---

## Proposed Changes

### Domain & Persistence Layer

#### [MODIFY] `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/DurableAgentAttempt.kt`
- Add `rootCaseId: String? = null` and `parentCaseId: String? = null` properties.

#### [MODIFY] `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/DurableAgentAttemptNode.kt`
- Add `val rootCaseId: String? = null` and `val parentCaseId: String? = null`.
- Update `toDomain()` and `fromDomain()`.

#### [MODIFY] `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/SpringDataNeo4jDurableAgentAttemptRepository.kt`
- Update Cypher query in `register` to include `a.rootCaseId = $rootCaseId` and `a.parentCaseId = $parentCaseId`.
- Add parameters `rootCaseId: String?` and `parentCaseId: String?`.

#### [MODIFY] `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/Neo4jDurableAgentAttemptRepository.kt`
- Pass `attempt.rootCaseId` and `attempt.parentCaseId` to `attempts.register(...)`.

#### [MODIFY] `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowModels.kt`
- Add `val rootCaseId: String? = null` to `WorkflowInstanceRecord`.

#### [MODIFY] `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/WorkflowInstanceNode.kt`
- Add `val rootCaseId: String? = null`.
- Update `toDomain()` and `fromDomain()`.

#### [MODIFY] `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/WorkflowRepository.kt`
- Add `fun reserveRootCase(scope: TenantScope, namespaceId: String, workflowId: String, rootCaseId: String): WorkflowInstanceRecord`.

#### [MODIFY] `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/Neo4jWorkflowRepository.kt` & `SpringDataNeo4jWorkflowRepositories.kt`
- Implement atomic `reserveRootCase` in Neo4j using Cypher `MATCH (i:WorkflowInstance {id: $id}) SET i.rootCaseId = COALESCE(i.rootCaseId, $rootCaseId) RETURN i`.

### Orchestration & Execution Service

#### [MODIFY] `factory/factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt`
- Add entry step validation (`steps.filter { it.responsibility.kind == AGENT && it.dependsOn.isEmpty() }`). If size != 1, refuse dispatch with clear error.
- At reservation time (`reserveAgentAttempt`):
  - Reserve or retrieve `rootCaseId` from workflow instance via `reserveRootCase`.
  - Calculate `parentCaseId` (`null` for entry step attempt 1 if root case, or `rootCaseId` for subsequent steps / retries).
  - Populate `rootCaseId` and `parentCaseId` on `DurableAgentAttempt`.

### Tests

#### [MODIFY/CREATE] Test Suite (`CapabilityExecutionServiceTest.kt` / `DurableAgentOsBridgeIntegrationTest.kt` / `DurableCaseFamilyIntegrationTest.kt`)
- Add tests covering the 6 required scenarios:
  1. Concurrent dispatch creates exactly one root case.
  2. Crash after root case reservation resumes with the same root case without recreating.
  3. Retry of first/failed step uses same worktree and creates a new sub-case (`caseId` differs, `rootCaseId` same).
  4. Isolation between two runs in the same namespace (distinct root cases/worktrees).
  5. Reading legacy data without `rootCaseId` works safely (`null` `rootCaseId`, strict compatibility).
  6. Refusal when zero or multiple candidate entry agent steps are present (`step.dependsOn.isEmpty()`).

---

## Verification Plan

### Automated Tests
- Run factory test suite via Nx:
  `pnpm nx test factory-service`
- Run affected tests baseline check:
  `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`
- Verify linting & compilation:
  `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`
  `pnpm nx affected -t build --base="$(cat /work/data/baseline)"`
