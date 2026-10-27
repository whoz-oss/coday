# Implementation Plan - Lot C: PROPAGATION ET RÉCUPÉRATION AgentOS de la famille de cases

This document presents the detailed execution plan for Lot C of the durable case family feature in the Kotlin `factory` module (`factory/factory-service/`).

## Context & Objectives

Lot A and Lot B are already integrated. Lot B persisted `rootCaseId` and `parentCaseId` on `DurableAgentAttempt` and `WorkflowInstance`.
Lot C connects the AgentOS adapter (`AgentOsExecutionAdapter`, `DefaultAgentOsExecutionAdapter`, `TrustedCaseBinding`) and recovery/retry flows (`CapabilityExecutionService`, `BridgeRecoveryWorker`) to carry and respect this case family identity. It enforces deterministic recovery rules and identical parentage behavior across nominal dispatch, recovery/re-drive, and retry/re-attempt execution paths.

---

## Proposed Changes

### 1. Data Contract & Adapter Identity Propagation

#### A. Update `TrustedCaseBinding.kt`
- Location: `factory/factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/TrustedCaseBinding.kt`
- Add optional `parentCaseId: String? = null` field to `TrustedCaseBinding` data class.

#### B. Update `AgentOsExecutionAdapter.kt`
- Location: `factory/factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/AgentOsExecutionAdapter.kt`
- Update historical `createOrRecoverExecution(...)` signature (or add parameter with default `parentCaseId: String? = null`):
  ```kotlin
  fun createOrRecoverExecution(
      namespaceId: String,
      workflowId: String,
      stepId: String,
      externalUserId: String?,
      attemptId: String,
      capabilityToken: String?,
      caseId: String,
      parentCaseId: String? = null,
  ): CaseHandle
  ```
- Update `AgentRuntimeAdapter.createOrRecoverExecution(binding: TrustedCaseBinding, ...)` default implementation to pass `parentCaseId = binding.parentCaseId` to the underlying historical method call.

#### C. Update `DefaultAgentOsExecutionAdapter.kt`
- Location: `factory/factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/DefaultAgentOsExecutionAdapter.kt`
- Update inner `ExecutionRecord` data class:
  ```kotlin
  private data class ExecutionRecord(
      val caseId: String,
      val namespaceId: String?,
      val externalUserId: String?,
      val capabilityToken: String?,
      val agentName: String? = null,
      val runtimeId: String? = null,
      val environmentRef: String? = null,
      val environmentRevision: Int? = null,
      val parentCaseId: String? = null,
  )
  ```
- Update `ExecutionRecord.toBinding(attemptId: String)` to include `parentCaseId = parentCaseId`.
- In `createOrRecoverExecution(binding, ...)`:
  - Add `if (!binding.parentCaseId.isNullOrBlank()) body["parentCaseId"] = binding.parentCaseId` to POST `/api/cases` body payload.
  - Store `parentCaseId` in `ExecutionRecord`.
  - Pass `parentCaseId` when adapting historical `createOrRecoverExecution` overload.

---

### 2. Deterministic Recovery / Cache-loss Reconciliation in DefaultAgentOsExecutionAdapter

When `DefaultAgentOsExecutionAdapter.executions` map is cold/empty (e.g. process restart or cache loss) and `createOrRecoverExecution` is invoked for an attempt not in memory:

#### Cold Cache Check Logic (`createOrRecoverExecution`):
1. Check `executions[binding.attemptId]`. If found, return cached `CaseHandle(..., recovered = true)`.
2. If not found in `executions`, perform deterministic lookup check on AgentOS before calling POST `/api/cases`:
   - Send `GET /api/cases/{binding.caseId}` (with `X-External-User-Id` if `binding.externalUserId` is present).
   - **If `GET` returns 200 OK** (case exists remotely):
     - Extract remote case properties: `remoteNamespaceId` (from `"namespaceId"` field), `remoteParentId` (from `"parentCaseId"` or `"parentId"` field in response JSON).
     - **Compatibility Check**:
       - `namespaceMatches`: `binding.namespaceId == null || remoteNamespaceId == null || binding.namespaceId == remoteNamespaceId`
       - `parentMatches`: `binding.parentCaseId == remoteParentId` (handling `null` vs `null` correctly).
     - **If compatible**:
       - Store `ExecutionRecord` in `executions` cache (with `resolvedCaseId`, `binding.namespaceId`, `binding.parentCaseId`, etc.).
       - Call `registry.register(record.toBinding(binding.attemptId))`.
       - Return `CaseHandle(binding.caseId, binding.namespaceId, recovered = true)`.
       - **Do NOT** issue `POST /api/cases`. Do NOT alter or recreate `caseId`.
     - **If incompatible** (different `namespaceId` or different `parentCaseId`):
       - Throw `IllegalStateException("Case mismatch / non-adoptable existing case: expected parentCaseId '${binding.parentCaseId}', found '$remoteParentId' / namespace mismatch")`.
   - **If `GET` returns 404 Not Found** (or fails with 404 status):
     - Proceed to standard `POST /api/cases` creation logic with POST body containing `"parentCaseId" = binding.parentCaseId`.
   - **Handling 409 Conflict / Race on `POST /api/cases`**:
     - If `POST /api/cases` returns 409 Conflict (or client exception indicating existing case):
       - Re-query `GET /api/cases/{binding.caseId}` and re-check compatibility. Adopt if compatible, throw if incompatible. Never duplicate or mutate `caseId`.

---

### 3. Identity & Parentage Preservation Across Dispatch & Recovery

#### A. Nominal Dispatch (`CapabilityExecutionService.kt`)
- Location: `factory/factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt`
- In `executeRemoteTurn(...)`:
  - When constructing `TrustedCaseBinding` for `adapter.createOrRecoverExecution(binding, workflowId, step.id)`:
    - Pass `parentCaseId = reservation.parentCaseId` (or `persisted.parentCaseId`) along with `caseId`, `namespaceId`, `attemptId`, `capabilityToken`, `agentName`.
    - For entry agent step: `parentCaseId` is `null` (root case).
    - For sub-cases: `parentCaseId` is `rootCaseId` (from Lot B reservation/attempt).

#### B. Recovery Worker (`BridgeRecoveryWorker.kt`)
- Location: `factory/factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/BridgeRecoveryWorker.kt`
- In `redrive(candidate)`:
  - Instead of calling historical `adapter.createOrRecoverExecution(...)` with bare arguments, pass `parentCaseId = attempt.parentCaseId` (or build a `TrustedCaseBinding` with `attempt.parentCaseId`).
  - In `startTurn` inside `redrive`: ensure binding/call retains `attempt.parentCaseId` if needed.
  - Verify that no recovery path drops `parentCaseId`.

#### C. Retry & Business Re-attempt (`AgentStepQuestionService.kt`)
- Verify `AgentStepQuestionService.kt`:
  - Successor attempt registration (already implemented in Lot B): retains `rootCaseId` and `parentCaseId` from predecessor attempt.
  - Ensure callers (e.g. `CapabilityExecutionService`) passing binding for retry attempts retain `parentCaseId` on `TrustedCaseBinding`.

---

## Boundaries & Verification Plan

### Strict Scope Boundaries
- Touch ONLY files in `factory/factory-service/`:
  - `TrustedCaseBinding.kt`
  - `AgentOsExecutionAdapter.kt`
  - `DefaultAgentOsExecutionAdapter.kt`
  - `CapabilityExecutionService.kt`
  - `BridgeRecoveryWorker.kt`
  - Associated unit/integration test files in `factory-service/src/test/`
- DO NOT touch `agentos/` plugins or AgentOS SDK.
- DO NOT alter step result contracts (Lot E).
- DO NOT touch UI files.

### Verification Plan

1. **Unit Tests in `DefaultAgentOsExecutionAdapterTest.kt`**:
   - `parentCaseId` included in POST `/api/cases` body payload.
   - Cold cache recovery via `GET /api/cases/{id}`:
     - Remote case exists & compatible => adoption with `recovered = true`, no POST.
     - Remote case exists & incompatible (`parentCaseId` mismatch or `namespaceId` mismatch) => throws `IllegalStateException`.
     - Remote case returns 404 => proceeds to POST `/api/cases` with `parentCaseId`.
   - Handling 409 Conflict / race condition reconciliation.

2. **Unit Tests in `BridgeRecoveryWorkerTest.kt` / `BridgeRecoveryWorkerLegacyCaseIdTest.kt`**:
   - Verify `redrive` passes `parentCaseId` to `adapter.createOrRecoverExecution`.

3. **Integration Tests in `CapabilityExecutionServiceTest.kt` / `DurableCaseFamilyIntegrationTest.kt`**:
   - Verify `parentCaseId` is correctly populated on `TrustedCaseBinding` passed to adapter during nominal execution and retries.

4. **Automated Test Suite Execution**:
   - Run `pnpm nx test factory-service` to verify all test suites pass without regression.
