# Implementation Plan - Factory Engine First Phase 2 (Sub-tasks 4 and 5)

## Overview

This plan covers Phase 2 implementation for the `factory-engine-first` initiative in `factory-service`, comprising two main sub-tasks:
1. **Sub-task 4: Auto Oracles**:
   - Match `OracleDefinition.applicable` (by `workflowTypes` and `stepIds`) for each step during session DAG execution in `SessionRunService`.
   - Invoke `OracleExecutionService.run` at appropriate execution points in `SessionRunService`.
   - Record the evidence item `oracle-result` in `workflow_evidence` log.
   - Gate step transitions appropriately based on oracle results, aligning with `WorkflowTransitionPolicy`.
2. **Sub-task 5: Auto Human Resumption**:
   - In `WorkflowService.replyInteraction`, after successfully processing a human interaction reply and closing the interaction, trigger automatic session resumption via `SessionRunService.runSession` instead of requiring a manual `/continue`.

All changes remain within `factory-service` and maintain strict compatibility with the persistence/JDBC layer and target-repo `factory/verification.json` loading rules.

---

## Technical Analysis & Target Files

### 1. Sub-task 4: Auto Oracles

#### Domain Rules & Matching Logic
- An `OracleDefinition` contains `applicable: OracleApplicableCondition(workflowTypes: List<String>, stepIds: List<String>)`.
- Matching rules:
  - If `applicable.workflowTypes` is not empty, the current session's `workflowType` must be contained in `applicable.workflowTypes`.
  - If `applicable.stepIds` is not empty, the current step's `id` must be contained in `applicable.stepIds`.
  - If both are empty, the oracle applies universally. If both are specified, both conditions must match.
- Matching oracles should be retrieved from `OracleDefinitionRegistry.list()` (or matching lookup method).

#### Execution & Evidence Flow in `SessionRunService`
- In `SessionRunService.executeStep(...)` (or immediately after step execution / capability resolution):
  1. Identify matching oracle definitions for the session's `workflowType` and current `step.id`.
  2. For each matching oracle definition, invoke `oracleExecutionService.run(scope, command)` with `OracleRunCommand(workflowId, stepId, oracleId, namespaceId, idempotencyKey)`.
  3. `OracleExecutionService.run` records the execution and publishes evidence via `OracleEvidencePublisher` (which appends `workflow_evidence` with `kind = "oracle-result"` and `outcome = "pass"` / `"fail"`).
  4. If `OracleEvidencePublisher` is default-wired to `WorkflowEvidenceRepository` (or if `SessionRunService` / `OracleExecutionService` ensures `oracle-result` evidence is present), `WorkflowTransitionPolicy` can validate matching `oracle-result` evidence for step completion or gating.
  5. If any required applicable oracle execution fails (`OracleExecutionStatus.FAILED`), the step outcome must be classified as `FAILED` (or gated according to `WorkflowTransitionPolicy`), setting step status to `FAILED`.

#### Component Wiring
- Inject `OracleExecutionService` and `OracleDefinitionRegistry` into `SessionRunService`.
- Make `OracleEvidencePublisher` component in `factory-service` delegate to `WorkflowEvidenceRepository.append` to write `kind = "oracle-result"` evidence directly into `workflow_evidence` table with facts (`oracleId`, `oracleVersion`, `stepId`, `outcome`).

---

### 2. Sub-task 5: Auto Human Resumption

#### Domain Rules & Flow
- Currently, when a user replies to an interaction via `WorkflowService.replyInteraction(...)`:
  - It records `human-decision` evidence.
  - It transitions the human step to `completed` or `failed`.
  - It updates interaction status to `closed`.
  - It publishes SSE event.
  - It returns HTTP result, but leaves any downstream ready steps pending until `/continue` (or `runSession`) is explicitly called.
- Target Behavior:
  - After closing the interaction and publishing SSE in `WorkflowService.replyInteraction(...)`, trigger automatic session resumption via `sessionRunService.runSession(scope, namespaceId, workflowId, repoRoot)`.
  - Note: `replyInteraction` resolves `repoRoot` using `agentOsProxyClient.resolveRepoRoot(namespaceId, null)` or falls back to `Path.of(".")` (matching `OutboxDrainWorker.resolveRepoRoot`).
  - To prevent long-running downstream turns inside the same HTTP transaction, invocation can be performed cleanly, or handled gracefully within session execution bounds without breaking existing `replyInteraction` return contracts.

---

## Detailed Implementation Steps

### Step 1: Wire Oracle Evidence Publishing
- **File**: `factory-service/src/main/kotlin/io/whozoss/factory/oracle/publisher/DefaultOracleEvidencePublisher.kt` (or inside `oracle/publisher/`)
- Implement `OracleEvidencePublisher` Spring `@Component`:
  ```kotlin
  @Component
  class DefaultOracleEvidencePublisher(
      private val evidenceRepository: WorkflowEvidenceRepository,
  ) : OracleEvidencePublisher {
      override fun recordEvidence(
          scope: TenantScope,
          namespaceId: String,
          workflowId: String,
          stepId: String,
          oracleId: String,
          outcome: String,
          facts: Map<String, Any?>,
      ): String {
          val evidenceId = UUID.randomUUID().toString()
          val item = WorkflowEvidenceItem(
              evidenceId = evidenceId,
              namespaceId = namespaceId,
              workflowId = workflowId,
              stepId = stepId,
              kind = "oracle-result",
              outcome = outcome, // "pass" or "fail"
              source = mapOf("kind" to "factory-oracle", "runtimeId" to "factory-dashboard", "oracleId" to oracleId),
              facts = facts,
              idempotencyKey = "oracle-run:$workflowId:$stepId:$oracleId",
              createdAt = null,
          )
          evidenceRepository.append(scope, namespaceId, workflowId, item)
          return evidenceId
      }
  }
  ```

### Step 2: Integrate Auto Oracles into `SessionRunService`
- **File**: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt`
- Inject `OracleDefinitionRegistry` and `OracleExecutionService` (optional or autowired).
- Add helper method `evaluateAndRunOracles(scope, namespaceId, workflowId, workflowType, stepId)`:
  1. Retrieve all registered definitions via `oracleDefinitionRegistry.list()`.
  2. Filter applicable oracles:
     ```kotlin
     val matching = registry.list().filter { def ->
         val typesMatch = def.applicable.workflowTypes.isEmpty() || workflowType in def.applicable.workflowTypes
         val stepsMatch = def.applicable.stepIds.isEmpty() || stepId in def.applicable.stepIds
         typesMatch && stepsMatch
     }
     ```
  3. For each matching oracle:
     - Invoke `oracleExecutionService.run(scope, OracleRunCommand(workflowId, stepId, def.id, namespaceId, idempotencyKey = "dag-oracle:$workflowId:$stepId:${def.id}"))`.
     - Check status: if status != `OracleExecutionStatus.SUCCEEDED`, return false / failure.
  4. In `executeStep(...)`:
     - Retrieve `workflowType` from `activeInstance(scope, namespaceId, workflowId).workflowType`.
     - Execute primary step capability.
     - If step succeeded (or code/agent completed), run applicable oracles via `evaluateAndRunOracles(...)`.
     - If any oracle failed, override terminal status to `FAILED`.

### Step 3: Implement Auto Resumption in `WorkflowService.replyInteraction`
- **File**: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`
- Inject `SessionRunService` and `AgentOsProxyClient` (or `ObjectProvider<SessionRunService>`).
- In `WorkflowService.replyInteraction(...)`:
  - After interaction is updated to `closed` and SSE is published:
    ```kotlin
    val repoRoot = resolveRepoRoot(namespaceId)
    sessionRunService.runSession(scope, namespaceId, workflowId, repoRoot)
    ```
  - Helper `resolveRepoRoot`:
    ```kotlin
    private fun resolveRepoRoot(namespaceId: String): Path =
        runCatching { agentOsProxyClient.resolveRepoRoot(namespaceId, null) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: Path.of(".")
    ```

### Step 4: Add Unit and Integration Tests
- **Oracle Matching & Execution Tests**:
  - Add test in `SessionSequencerIntegrationTest.kt` or dedicated `AutoOracleIntegrationTest.kt`:
    - Define workflow with step matching an applicable oracle.
    - Verify oracle execution is triggered, `oracle-result` evidence item is saved, and step completed/failed according to oracle verdict.
- **Auto Resumption Tests**:
  - Add/update tests in `WorkflowServiceIntegrationTest.kt` or `SessionSequencerIntegrationTest.kt`:
    - Start session with human checkpoint step and downstream agent/code step.
    - Call `replyInteraction` with `approve`.
    - Verify downstream step automatically executes and session advances without requiring manual call to `/continue` or `runSession`.

---

## Verification Plan

### Automated Tests
Run target test suites:
- `pnpm nx test factory-service`
- Specific test classes:
  - `SessionSequencerIntegrationTest`
  - `WorkflowServiceIntegrationTest`
  - `OracleExecutionRepositoryTest` / `OracleControllerIntegrationTest`

### Quality Checks
- `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`
- `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`
