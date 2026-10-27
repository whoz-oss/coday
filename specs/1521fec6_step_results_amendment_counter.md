# Lot E Implementation Plan: Step Results Extension, Amendment Counter, and NEEDS_RESEARCH Verdict Routing

This plan covers extending the step result submission contract in Kotlin module `factory/` (`factory-service`), implementing authoritative amendment sequence control, and adding automatic routing from `NEEDS_RESEARCH` to Searcher while preserving existing identity checks, single-submission budgets, and idempotency guarantees.

---

## 1. Overview & Architectural Boundaries

### Perimeter & Constraints
- **Strictly within `factory/factory-service`**: Touches domain models, validation, persistence, service layer, controllers, and workflow execution/recovery workers in Kotlin.
- **DO NOT modify** `agentos/agentos-factory-bridge-plugin` or AgentOS SDK (#1418).
- **DO NOT modify** UI or frontend code.
- **DO NOT implement** workspace pre-check (Lot F).
- **DO NOT modify** Lot B root case reservation or Lot C propagation.
- **Authoritative Channel**: `FACTORY_WORKER__submit_step_result` / `/api/factory/agent-step-results` remains sole step result source of truth. Step success is NEVER derived from `IDLE` state alone.
- **Claims vs Verified Diff**: Re-uses existing fields (`artifacts`, `findings`, `claims.modifiedFiles`). Explicitly treats `claims.modifiedFiles` as DECLARED claims, distinct from verified diff.

---

## 2. Detailed Technical Changes

### Task 1: Authoritative Result Channel & Three Verdicts

#### 1.1 Data Models (`AgentStepResultModels.kt`)
- **Enum Extension**: In `AgentStepResultStatus`, add `NEEDS_RESEARCH`. Wire name is `"NEEDS_RESEARCH"`.
  ```kotlin
  enum class AgentStepResultStatus {
      PASS,
      FAIL,
      NEEDS_RESEARCH,
      ;
      companion object {
          fun fromWire(value: String?): AgentStepResultStatus? = entries.firstOrNull { it.name == value }
      }
  }
  ```
- **Amendment Sequence Field**: Extend `AgentStepResultBusiness` and `AgentStepResultSubmitted` with `expectedAmendmentSeq: Long? = null`:
  - `AgentStepResultBusiness(..., val expectedAmendmentSeq: Long? = null)`
  - `AgentStepResultSubmitted(..., val expectedAmendmentSeq: Long? = null)`

#### 1.2 Validation & Parsing (`AgentStepResultValidation.kt`)
- Extend `STATUSES` set: `private val STATUSES = setOf("PASS", "FAIL", "NEEDS_RESEARCH")`.
- Extend `BUSINESS_FIELDS` set: `private val BUSINESS_FIELDS = setOf("status", "summary", "artifacts", "claims", "findings", "expected_amendment_seq", "expectedAmendmentSeq")`.
- Validate `expected_amendment_seq` / `expectedAmendmentSeq` in `validateBusiness`: if present, it must be an integral number >= 0.
- Update `parseBusiness`: extract `expected_amendment_seq` (or `expectedAmendmentSeq`) as `Long?` and map it into `AgentStepResultBusiness`.

#### 1.3 Error Codes & Exceptions (`AgentStepResultModels.kt`)
- In `AgentAttemptErrorCodes`, add:
  ```kotlin
  const val STALE_AMENDMENT_SEQUENCE = "STALE_AMENDMENT_SEQUENCE"
  ```
- Add exception class:
  ```kotlin
  class StaleAmendmentSequenceException(
      message: String = "The expected amendment sequence does not match the current workflow sequence",
      details: Any? = null,
  ) : AgentAttemptException(AgentAttemptErrorCodes.STALE_AMENDMENT_SEQUENCE, 409, message, details)
  ```

---

### Task 2: Authoritative Amendment Counter

#### 2.1 Workflow Instance & Persistence (`WorkflowModels.kt`, `WorkflowInstanceNode.kt`, `WorkflowRepository.kt`)
- Add `amendmentSeq: Long = 0L` to `WorkflowInstanceRecord`.
- Add `amendmentSeq: Long = 0L` column/field to `WorkflowInstanceNode` (denormalized on the SDN entity).
- Provide a atomic CAS / increment method in `WorkflowRepository` / `Neo4jWorkflowRepository`:
  ```kotlin
  fun incrementAmendmentSeq(scope: TenantScope, namespaceId: String, workflowId: String): Long
  ```
  Executes Cypher `MATCH (n:WorkflowInstance ...) SET n.amendmentSeq = coalesce(n.amendmentSeq, 0) + 1 RETURN n.amendmentSeq`.
- Trigger counter increment whenever an amendment / plan change is accepted (e.g. in `PlanChangeProposalService` or `WorkflowService`).

#### 2.2 Result Submission Sequence Compare-And-Set (`Neo4jAgentStepResultRepository.kt` & `AgentStepResultService.kt`)
- When `business.expectedAmendmentSeq` is non-null:
  - Fetch current workflow instance `amendmentSeq` for `(namespaceId, workflowId)`.
  - Compare `expectedAmendmentSeq` with current `amendmentSeq`.
  - If mismatch: throw `StaleAmendmentSequenceException` (HTTP 409).
- **Semantic Signature Hash**:
  - Include `expectedAmendmentSeq` in `CanonicalJsonHash.hash(business)` / `resultHash` calculation so divergent amendment sequence submissions produce different result hashes.

---

### Task 3: Automatic Routing NEEDS_RESEARCH -> SEARCHER

#### 3.1 Step Outcome & Attempt Status (`AgentStepResultModels.kt`, `DurableAgentAttempt.kt`)
- Ensure `NEEDS_RESEARCH` treats step as BLOCKED / NEEDS_RESEARCH, proof/evidence preserved, dependants NOT launched.
- Do NOT seal the run as terminal `FAIL` when `NEEDS_RESEARCH` is submitted.
- In `CapabilityExecutionService.kt` / `CapabilityModels.kt`:
  - Add `CapabilityOutcome.AgentNeedsResearch(stepId, persona, summary, findings, artifacts, evidenceId)` or handle `NEEDS_RESEARCH` status in `AgentCompleted`/`AgentNeedsResearch`.

#### 3.2 Session Sequencer & Step Routing (`SessionSequencer.kt` & `SessionRunService.kt`)
- In `SessionSequencer.kt`, add status support or handling for `NEEDS_RESEARCH` / blocked attempt re-arming.
- When `NEEDS_RESEARCH` is received for step attempt `A`:
  1. Record step `A` result / evidence without marking workflow run as terminal `FAILED`.
  2. Automatically launch a Searcher attempt/step carrying missing research prompt/context derived from findings/summary.
  3. Once Searcher attempt completes, re-arm initial attempt `A` as attempt `A_v2` (new attempt = new sub-case on the same worktree, consistent with Lot B).
  4. Preserve original `NEEDS_RESEARCH` evidence/proof.

#### 3.3 Recovery Workers (`ResultChannelRecoveryWorker.kt`, `DurableAgentAttemptService.kt`)
- Ensure `ResultChannelRecoveryWorker` handles `NEEDS_RESEARCH` submitted results by routing or keeping them pending re-arm rather than terminalizing to `ATTEMPT_FAILED`.

---

### Task 4: Worker Schemas & DTO Bindings

#### 4.1 Request DTOs & Controller (`AgentStepResultDtos.kt`, `AgentStepResultController.kt`, `FactoryStepResultBindingController.kt`)
- Update `AgentStepResultRequest` / `ObservedIdentityRequest` if needed to accept `expected_amendment_seq` / `expectedAmendmentSeq`.
- OpenAPI Annotations in `AgentStepResultController.kt`: Add 409 `STALE_AMENDMENT_SEQUENCE` description.

---

## 3. Verification Plan

### Test File Coverage & New Tests
1. **`AgentStepResultValidationTest.kt`**:
   - Verify `PASS`, `FAIL`, `NEEDS_RESEARCH` are accepted.
   - Verify invalid status values are rejected.
   - Verify `expected_amendment_seq` >= 0 validation.
2. **`AgentStepResultServiceIntegrationTest.kt` / `AgentStepResultDurabilityTest.kt`**:
   - Test `NEEDS_RESEARCH` submission and persistence.
   - Test obsolete result rejection: wrong `expectedAmendmentSeq` throws `StaleAmendmentSequenceException` (409).
   - Test matching `expectedAmendmentSeq` succeeds.
   - Test identity mismatch / bad identity rejection.
   - Test double submission / single submission budget enforcement.
   - Test semantic collision vs idempotent replay (including `expectedAmendmentSeq` in hash).
3. **`CapabilityExecutionServiceTest.kt` / `SessionRunServiceTest.kt`**:
   - Test `NEEDS_RESEARCH` verdict triggers Searcher turn and re-arms attempt without sealing run.
   - Test that IDLE state alone NEVER derives step success.
4. **Execution Command**:
   - Run tests via `./gradlew :factory-service:test` inside `factory/` directory.
