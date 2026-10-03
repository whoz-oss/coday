# Plan: Step 8 (Lot H) AgentOS-Factory Bridge Life-Cycle Completion

## Overview
This plan details the implementation of Step 8 (Lot H): AgentOS-Factory Bridge Life-Cycle Completion in `factory-service` and `agentos-factory-bridge-plugin`.

The goal is to close all remaining lifecycle edge cases in the durable execution bridge:
1. **Startup Recovery Worker**: Automatically scan non-terminal agent attempt records on Factory boot across tenant scopes, inspect leases and AgentOS status, resume observation or reconcile events, and finalize terminal states or re-run turns only if proven unaccepted.
2. **Command Idempotency**: Reinforce deduplication by `attemptId` across outbox submissions, capability execution, and AgentOS dispatch. Ensure identical requests yield idempotent replays and payload variations under the same `attemptId` fail with explicit collision errors.
3. **Observation Timeout & Escalation Policy**: Enforce that observation timeouts produce an initial `INDETERMINATE` state, followed by a deterministic escalation chain (REST snapshot reconcile -> SSE reconnect -> explicit kill -> post-kill reconcile -> terminal state with proof, NEVER implicit success).
4. **Explicit Cancellation Endpoint (`requestCancel`)**: Provide a first-class HTTP cancellation route `POST /api/factory/workflows/{workflowId}/attempts/{attemptId}/cancel` that triggers adapter `interrupt`/`kill`, post-kill reconciliation, and moves the attempt into `INTERRUPTED` under lease fencing.
5. **Comprehensive Automated Test Coverage**: Unit and integration tests in Spring Boot / Neo4j testing harness verifying restart recovery, crash reconciliation, lease fencing, timeout escalation, and explicit cancellation behavior.

---

## Architectural Boundaries & Frozen Decisions
- **AgentOS Core Integrity**: Zero changes to core AgentOS engine classes (`caseEvent`, `caseFlow`, etc.) or internal endpoints. Interactivity with AgentOS occurs exclusively through existing adapter APIs (`/api/cases`, `/api/cases/{id}/messages`, `/api/cases/{id}/kill`, `/api/case-events/by-parentId/{id}`).
- **SSE Protocol & Deduplication**: History replay + client-side event deduplication by `eventId` via `EventCheckpoint` / high-water mark stores (`HighWaterMarkStore` in Factory, `FactorySseHighWaterMarkStore` in bridge plugin). Server cursors are NOT used.
- **Idempotency Identity**: Keyed by `attemptId` at the Factory boundary (`DurableAgentAttempt`, outbox events, `CapabilityExecutionService`, `AgentOsExecutionAdapter`).
- **Legacy Fallback Compatibility**: `HttpAgentOsProxyClient` remains active and untouched for non-adapter legacy polling fallback paths.

---

## Step-by-Step Implementation Plan

### Task 1: Non-Terminal Query Port & Startup Bridge Recovery Worker
**Target Directory**: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/`

1. **Extend DurableAgentAttempt Persistence Port for Querying Non-Terminal Attempts**:
   - Update `DurableAgentAttemptRepository`: add method `fun findNonTerminal(limit: Int = 100): List<DurableAgentAttempt>`.
   - Update `SpringDataNeo4jDurableAgentAttemptRepository`: add `@Query` to find nodes where `NOT a.status IN ['succeeded', 'failed', 'indeterminate', 'interrupted']`.
   - Update `Neo4jDurableAgentAttemptRepository`: implement `findNonTerminal(limit)` mapping `DurableAgentAttemptNode`s to domain `DurableAgentAttempt`s.
   - Update `DurableAgentAttemptService`: expose `@Transactional(readOnly = true) fun findNonTerminal(limit: Int = 100)`.

2. **Create `BridgeRecoveryWorker` Service**:
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/BridgeRecoveryWorker.kt`
   - Annotated with `@Component` and listens to `@EventListener(ApplicationReadyEvent::class)`.
   - Injection: `DurableAgentAttemptService`, `AgentOsExecutionAdapter`, `CapabilityExecutionService`, `WorkflowRepository`, transaction manager / template.
   - **Recovery Algorithm (`recoverOnStartup()`)**:
     1. Query non-terminal `DurableAgentAttempt` records via `durableAgentAttemptService.findNonTerminal()`.
     2. For each attempt, check lease status (`leaseExpiresAt`).
     3. Call `adapter.reconcile(attempt.caseId)` to inspect AgentOS event history and current case status.
     4. **Branching Logic**:
        - If AgentOS case history contains a derived terminal verdict (`Succeeded`, `Failed`, `Interrupted`), record evidence and finalize attempt to corresponding terminal state (`SUCCEEDED`, `FAILED`, `INTERRUPTED`) using a fresh lease owner token under short transaction.
        - If AgentOS case is active / non-quiescent (`RUNNING`, `WAITING_HUMAN`, `STARTING`), resume SSE observation via `adapter.observeTurn(...)` using `HighWaterMarkStore` / checkpointing to process replayed events with `eventId` deduplication, then finalize based on observed verdict.
        - If AgentOS case shows turn was **never accepted / started** (proven via `reconcile` showing no status/message event matching turn start), and attempt is non-terminal: rerun turn ONLY if turn start unaccepted.

---

### Task 2: Factory Command Idempotency & Payload Collision Reinforcement
**Target Directory**: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/` & `io/whozoss/factory/capability/`

1. **Reinforce Deduplication in `CapabilityExecutionService` & `OutboxDrainService`**:
   - Check existing registration and attempt lookup logic in `reserveAgentAttempt`.
   - Ensure that if a submission / execution request comes in with the same `attemptId`:
     - Compare payload / command content / step inputs.
     - If `attemptId` matches and payload / parameters differ from stored attempt fields (or stored command hash): throw explicit `IdempotencyKeyCollisionException` (409 Conflict).
     - If `attemptId` matches and payload is identical: return existing execution attempt / result idempotently without re-dispatching to AgentOS.
2. **Outbox Replay Guarantee**:
   - Ensure outbox event generation and consumption for `RESULT_SUBMITTED` and execution dispatches ignore duplicate submissions under the same `attemptId`.

---

### Task 3: Observation Timeout Policy & Escalation Chain
**Target Directory**: `factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/` & `io/whozoss/factory/capability/`

1. **Enhance `executeRemoteTurn` and `AgentOsExecutionAdapter` Escalation**:
   - Update `CapabilityExecutionService.executeRemoteTurn` timeout / error handling:
   - On SSE / observation timeout or stream disconnection:
     1. Mark / derive state intermediate/provisional status as `INDETERMINATE`.
     2. **Step 1 (REST Snapshot)**: Execute `adapter.reconcile(caseId)`. If a definitive verdict (`Succeeded` / `Failed`) is returned, finalize with that verdict.
     3. **Step 2 (SSE Reconnection)**: Attempt short-budget SSE reconnection with checkpoint deduplication.
     4. **Step 3 (Explicit Interrupt/Kill)**: If case remains non-quiescent and timeout policy dictates termination, invoke `adapter.kill(caseId)`.
     5. **Step 4 (Post-Kill Reconcile)**: Execute `adapter.reconcile(caseId)`.
     6. **Step 5 (Terminal Finalization)**: Finalize attempt state ONLY with sufficient proof from post-kill reconcile (e.g., `AgentOsExecutionVerdict.Interrupted` or `Failed`). **NEVER** implicitly transition to `SUCCEEDED` or success on timeout/kill.

---

### Task 4: Explicit Business Cancellation Endpoint (`requestCancel`)
**Target Directories**:
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/`

1. **Create/Update Cancellation Command Endpoint**:
   - Route: `POST /api/factory/workflows/{workflowId}/attempts/{attemptId}/cancel`
   - Query / Body parameters: `namespaceId` (optional/required per scope), `expectedRevision` (fencing parameter for optimistic concurrency).
   - Controller behavior:
     1. Resolve caller `TenantScope` and `TrustContext`.
     2. Invoke cancellation logic in `DurableAgentAttemptService` or dedicated bridge cancellation service.
2. **Cancellation Lifecycle Execution**:
   - Validate attempt exists and is non-terminal.
   - Validate `expectedRevision` if supplied.
   - Invoke `agentOsExecutionAdapter.interrupt(caseId, reason = "User requested cancellation")` or `adapter.kill(caseId)`.
   - Run post-kill `adapter.reconcile(caseId)`.
   - Call `durableAgentAttemptService.finalize(...)` moving attempt to `AgentAttemptStatus.INTERRUPTED` under active lease owner token.
   - Confirm note: Closing SSE client connection / browser tab alone does NOT trigger cancellation; cancellation requires explicit HTTP request.

---

### Task 5: Comprehensive Automated Integration & Unit Tests
**Target Directory**: `factory-service/src/test/kotlin/io/whozoss/factory/`

1. **Startup Recovery Tests (`BridgeRecoveryWorkerTest.kt` / `DurableAgentOsBridgeIntegrationTest.kt`)**:
   - Test restart during active turn: verify observation is resumed without creating duplicate AgentOS cases or double turn start messages.
   - Test crash after AgentOS result but before Factory commit: verify startup recovery runs single reconciliation and finalizes to `SUCCEEDED`/`FAILED`.
2. **Fencing Tests**:
   - Test worker that lost its lease due to expiration / preemption: verify finalization fails with `ATTEMPT_LEASE_FENCED` (409 Conflict).
3. **Timeout Escalation Tests**:
   - Test SSE timeout: verify transition flow `INDETERMINATE` -> REST snapshot -> reconnect attempt -> kill -> terminal status based on proof (never implicit `SUCCEEDED`).
4. **Cancellation Tests**:
   - Test `POST /api/factory/workflows/{workflowId}/attempts/{attemptId}/cancel`: verify explicit call interrupts turn, invokes post-kill reconcile, and persists durable `INTERRUPTED` state under lease fencing.
   - Test SSE disconnect: verify stream close does NOT cancel running execution attempt.

---

## Verification & Acceptance
- Run full test suite in `factory-service`:
  `cd factory-service && ./gradlew test`
- All tests pass cleanly without breaking existing features or Spring contexts.
