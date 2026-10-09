# Implementation Plan - Lot E: Step Results Extension, Amendment Counter, and NEEDS_RESEARCH Routing

This document details the architectural and implementation plan for Lot E in the Kotlin module `factory/` (`factory-service`).

---

## 1. Objectives & Boundaries

### Core Objectives
1. **Authoritative Result Channel**: `FACTORY_WORKER__submit_step_result` (`AgentStepResultService` / `AgentStepResultController`) remains the single source of truth for step results. IDLE state alone must NEVER derive step success.
2. **Three Verdicts Support**: Add support for `PASS`, `FAIL`, and `NEEDS_RESEARCH` across domain models, JSON validation, parsing, and result persistence. `NEEDS_RESEARCH` blocks the step, preserves findings/artifacts/proof, prevents launching dependent steps, and does NOT seal the workflow run as terminal `FAIL`.
3. **Authoritative Amendment Counter**: Carry `amendmentSeq: Long` on the workflow instance, incremented atomically upon accepting plan change amendments. When submitting a result with `expected_amendment_seq`, perform compare-and-set validation; reject stale submissions with HTTP 409 `STALE_AMENDMENT_SEQUENCE`. Include `expected_amendment_seq` in the semantic hash identity of the result.
4. **Automatic Searcher Routing for `NEEDS_RESEARCH`**: When a step result yields `NEEDS_RESEARCH`, automatically route execution to a Searcher agent attempt to perform research, then re-arm the initial step attempt (`A_v2`) on the same worktree/sub-case while preserving original `NEEDS_RESEARCH` proof.
5. **Worker Contract Alignment**: Extend consumed tool/DTO schemas on the Factory side without modifying `agentos/agentos-factory-bridge-plugin` or the AgentOS SDK.
6. **Integrity Guarantees**: Preserve identity verification, single-submission budget, idempotent replay, and collision detection.

---

## 2. Technical Scope & File Changes

### Task 1: Domain Models, Validation, & Parsing
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/AgentStepResultModels.kt`**:
  - Extend `AgentStepResultStatus` enum with `NEEDS_RESEARCH`.
  - Add `expectedAmendmentSeq: Long? = null` to `AgentStepResultBusiness` and `AgentStepResultSubmitted`.
  - Add error code `STALE_AMENDMENT_SEQUENCE = "STALE_AMENDMENT_SEQUENCE"` to `AgentAttemptErrorCodes`.
  - Add `StaleAmendmentSequenceException` (HTTP 409).
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/AgentStepResultValidation.kt`**:
  - Update `STATUSES` set to include `"NEEDS_RESEARCH"`.
  - Update `BUSINESS_FIELDS` set to include `"expected_amendment_seq"` and `"expectedAmendmentSeq"`.
  - Validate `expected_amendment_seq` / `expectedAmendmentSeq` as optional non-negative integer (`Long >= 0`).
  - Update `parseBusiness` to extract `expectedAmendmentSeq` into `AgentStepResultBusiness`.
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/CanonicalJsonHash.kt`**:
  - Include `expectedAmendmentSeq` in `canonicalizeAgentStepResult` mapping so different amendment sequences yield different SHA-256 hashes.

### Task 2: Authoritative Amendment Counter & CAS Validation
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowModels.kt`**:
  - Add `val amendmentSeq: Long = 0L` to `WorkflowInstanceRecord`.
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/WorkflowInstanceNode.kt`**:
  - Add `var amendmentSeq: Long = 0L` to `WorkflowInstanceNode`.
  - Map `amendmentSeq` in `toDomain()` and `fromDomain()`.
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/WorkflowRepository.kt` & `Neo4jWorkflowRepository.kt`**:
  - Add `incrementAmendmentSeq(scope: TenantScope, namespaceId: String, workflowId: String): Long` to `WorkflowRepository`.
  - Implement Cypher atomic increment: `MATCH (n:WorkflowInstance {id: $id}) SET n.amendmentSeq = coalesce(n.amendmentSeq, 0) + 1 RETURN n.amendmentSeq`.
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/planchange/service/PlanChangeProposalService.kt`**:
  - When a proposal decision transitions to an accepted state (e.g. `APPROVED`), invoke `workflowRepository.incrementAmendmentSeq(...)`.
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/AgentStepResultService.kt`**:
  - In `submit()`, if `business.expectedAmendmentSeq != null`:
    - Retrieve active `WorkflowInstanceRecord` via `workflowRepository`.
    - Compare `business.expectedAmendmentSeq` with `instance.amendmentSeq`.
    - If `business.expectedAmendmentSeq != instance.amendmentSeq`, throw `StaleAmendmentSequenceException` (HTTP 409).

### Task 3: Automatic Searcher Routing & Orchestration
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/SessionSequencer.kt`**:
  - Ensure `NEEDS_RESEARCH` status on a step blocks dependent steps without marking the workflow session as terminally failed while searcher routing is in progress.
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt`**:
  - Map `AgentStepResultStatus.NEEDS_RESEARCH` in `onResultSubmitted` / capability evaluation.
  - Trigger automatic Searcher step attempt execution carrying missing research details derived from findings and summary.
  - Re-arm the original attempt as attempt `v2` under the same worktree / sub-case upon Searcher completion.
  - Ensure IDLE attempt state NEVER completes a step without an explicit `PASS` result submission.
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/ResultChannelRecoveryWorker.kt`**:
  - Update recovery reconciliation to handle `NEEDS_RESEARCH` results without marking attempts as `ATTEMPT_FAILED`.

### Task 4: DTOs & Web Layer
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/web/AgentStepResultDtos.kt`**:
  - Add `expected_amendment_seq: Long? = null` and `expectedAmendmentSeq: Long? = null` to `AgentStepResultRequest`.
- **`factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/web/AgentStepResultController.kt` & `FactoryStepResultBindingController.kt`**:
  - Wire DTO updates to pass `expectedAmendmentSeq` into `AgentStepResultService`.
  - Document 409 `STALE_AMENDMENT_SEQUENCE` response.

---

## 3. Verification & Test Plan

### Unit & Integration Tests
1. **`AgentStepResultValidationTest`**:
   - Verify `PASS`, `FAIL`, and `NEEDS_RESEARCH` are accepted.
   - Verify `expected_amendment_seq` validation (non-negative integer).
   - Verify unknown status values or negative amendment sequence are rejected.
2. **`AgentStepResultServiceIntegrationTest` & `AgentStepResultDurabilityTest`**:
   - Verify `NEEDS_RESEARCH` submission persists findings, claims, and artifacts.
   - Verify result submission with valid `expectedAmendmentSeq` matches workflow `amendmentSeq`.
   - Verify result submission with stale `expectedAmendmentSeq` throws `StaleAmendmentSequenceException` (HTTP 409).
   - Verify single-submission budget, identity check failures, and idempotent replay / collision detection when `expectedAmendmentSeq` differs.
3. **`CapabilityExecutionServiceTest` / `SessionRunServiceTest`**:
   - Test `NEEDS_RESEARCH` result triggers automatic Searcher execution and re-arms attempt `v2` on the same worktree.
   - Verify workflow run is NOT sealed as terminal `FAIL` on `NEEDS_RESEARCH`.
   - Verify IDLE state alone NEVER completes a step as success.
